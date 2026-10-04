// WASAPI output for Tryptify on Windows.
//
// The desktop replacement for Android's AudioTrack sink: a shared- or
// exclusive-mode, event-driven render stream on any endpoint, with the
// device-change signal the player needs to re-route. Exclusive mode is the
// bit-perfect path — the engine's float output is dithered/truncated to the
// DAC's native PCM width in Kotlin and handed here untouched, at the file's
// sample rate, past the Windows mixer. Shared mode asks the audio engine to
// convert (AUTOCONVERTPCM), so any rate and width plays through the system
// mix.
//
// Threading: every JNI entry initialises COM for its calling thread (the JVM
// does not), so the Kotlin sink may call from whichever thread owns it. The
// write path allocates nothing after open(): the staging buffer for exclusive
// mode is sized at open time.
//
// Kotlin side: tf.monochrome.desktop.audio.wasapi.WasapiNative.

#define INITGUID
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <objbase.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <functiondiscoverykeys_devpkey.h>
#include <ksmedia.h>
#include <avrt.h>
#include <propvarutil.h>

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

namespace {

// ── Formats ─────────────────────────────────────────────────────────────────
// Mirrors WasapiNative.FORMAT_* on the Kotlin side.
enum SampleFormat : int {
    kPcm16 = 0,      // 16-bit signed, 2-byte container
    kPcm24 = 1,      // 24-bit signed, packed 3-byte container
    kPcm24In32 = 2,  // 24 valid bits, left-justified in a 4-byte container
    kPcm32 = 3,      // 32-bit signed
    kFloat32 = 4,    // IEEE float
};

struct FormatSpec {
    WORD bitsPerSample;
    WORD validBits;
    bool isFloat;
};

bool describeFormat(int format, FormatSpec& out) {
    switch (format) {
        case kPcm16: out = {16, 16, false}; return true;
        case kPcm24: out = {24, 24, false}; return true;
        case kPcm24In32: out = {32, 24, false}; return true;
        case kPcm32: out = {32, 32, false}; return true;
        case kFloat32: out = {32, 32, true}; return true;
        default: return false;
    }
}

DWORD channelMaskFor(int channels) {
    switch (channels) {
        case 1: return KSAUDIO_SPEAKER_MONO;
        case 2: return KSAUDIO_SPEAKER_STEREO;
        case 4: return KSAUDIO_SPEAKER_QUAD;
        case 6: return KSAUDIO_SPEAKER_5POINT1;
        case 8: return KSAUDIO_SPEAKER_7POINT1_SURROUND;
        default: {
            // Fill the low bits so the count matches; WASAPI only needs the
            // popcount to agree with nChannels.
            DWORD mask = 0;
            for (int i = 0; i < channels && i < 32; ++i) mask |= (1u << i);
            return mask;
        }
    }
}

void buildWaveFormat(WAVEFORMATEXTENSIBLE& wf, int sampleRate, int channels, const FormatSpec& spec) {
    std::memset(&wf, 0, sizeof wf);
    wf.Format.wFormatTag = WAVE_FORMAT_EXTENSIBLE;
    wf.Format.nChannels = static_cast<WORD>(channels);
    wf.Format.nSamplesPerSec = static_cast<DWORD>(sampleRate);
    wf.Format.wBitsPerSample = spec.bitsPerSample;
    wf.Format.nBlockAlign = static_cast<WORD>(channels * spec.bitsPerSample / 8);
    wf.Format.nAvgBytesPerSec = wf.Format.nSamplesPerSec * wf.Format.nBlockAlign;
    wf.Format.cbSize = sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX);
    wf.Samples.wValidBitsPerSample = spec.validBits;
    wf.dwChannelMask = channelMaskFor(channels);
    wf.SubFormat = spec.isFloat ? KSDATAFORMAT_SUBTYPE_IEEE_FLOAT : KSDATAFORMAT_SUBTYPE_PCM;
}

// ── Errors ──────────────────────────────────────────────────────────────────
thread_local std::string g_lastError;

void setError(const char* what, HRESULT hr) {
    char buf[256];
    std::snprintf(buf, sizeof buf, "%s (hr=0x%08lx)", what, static_cast<unsigned long>(hr));
    g_lastError = buf;
}

void setError(const char* what) { g_lastError = what; }

// ── COM per call ────────────────────────────────────────────────────────────
struct ComScope {
    HRESULT hr;
    ComScope() : hr(CoInitializeEx(nullptr, COINIT_MULTITHREADED)) {}
    ~ComScope() {
        // S_OK and S_FALSE both mean we hold a reference; RPC_E_CHANGED_MODE
        // means the thread was already initialised another way and we must
        // not uninitialise it.
        if (hr == S_OK || hr == S_FALSE) CoUninitialize();
    }
};

template <typename T>
struct ComPtr {
    T* p = nullptr;
    ComPtr() = default;
    ComPtr(const ComPtr&) = delete;
    ComPtr& operator=(const ComPtr&) = delete;
    ~ComPtr() { reset(); }
    void reset() { if (p) { p->Release(); p = nullptr; } }
    T** put() { reset(); return &p; }
    void** putVoid() { reset(); return reinterpret_cast<void**>(&p); }
    T* operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

// ── Device enumeration ──────────────────────────────────────────────────────
bool makeEnumerator(ComPtr<IMMDeviceEnumerator>& out) {
    HRESULT hr = CoCreateInstance(CLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL,
                                  IID_IMMDeviceEnumerator, out.putVoid());
    if (FAILED(hr)) { setError("CoCreateInstance(MMDeviceEnumerator)", hr); return false; }
    return true;
}

std::wstring deviceId(IMMDevice* device) {
    LPWSTR id = nullptr;
    if (FAILED(device->GetId(&id)) || id == nullptr) return L"";
    std::wstring out(id);
    CoTaskMemFree(id);
    return out;
}

std::wstring deviceName(IMMDevice* device) {
    ComPtr<IPropertyStore> props;
    if (FAILED(device->OpenPropertyStore(STGM_READ, props.put()))) return L"";
    PROPVARIANT value;
    PropVariantInit(&value);
    std::wstring out;
    if (SUCCEEDED(props->GetValue(PKEY_Device_FriendlyName, &value)) && value.vt == VT_LPWSTR && value.pwszVal) {
        out = value.pwszVal;
    }
    PropVariantClear(&value);
    return out;
}

// Resolves an endpoint id (or null/empty for the default render endpoint).
bool openDevice(IMMDeviceEnumerator* enumerator, const std::wstring& id, ComPtr<IMMDevice>& out) {
    HRESULT hr = id.empty()
        ? enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, out.put())
        : enumerator->GetDevice(id.c_str(), out.put());
    if (FAILED(hr)) { setError(id.empty() ? "GetDefaultAudioEndpoint" : "GetDevice", hr); return false; }
    return true;
}

// ── Device-change notifications ─────────────────────────────────────────────
// One global listener; the Kotlin side polls nativeDeviceGeneration() and
// re-enumerates when it moves. Polling a counter from the player's tick is
// simpler and safer than calling back into the JVM from a COM thread.
std::atomic<uint64_t> g_deviceGeneration{1};

class NotificationClient final : public IMMNotificationClient {
public:
    ULONG STDMETHODCALLTYPE AddRef() override { return 1; }
    ULONG STDMETHODCALLTYPE Release() override { return 1; }
    HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void** ppv) override {
        if (riid == IID_IUnknown || riid == IID_IMMNotificationClient) {
            *ppv = static_cast<IMMNotificationClient*>(this);
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    HRESULT STDMETHODCALLTYPE OnDeviceStateChanged(LPCWSTR, DWORD) override { bump(); return S_OK; }
    HRESULT STDMETHODCALLTYPE OnDeviceAdded(LPCWSTR) override { bump(); return S_OK; }
    HRESULT STDMETHODCALLTYPE OnDeviceRemoved(LPCWSTR) override { bump(); return S_OK; }
    HRESULT STDMETHODCALLTYPE OnDefaultDeviceChanged(EDataFlow flow, ERole role, LPCWSTR) override {
        if (flow == eRender && role == eMultimedia) bump();
        return S_OK;
    }
    HRESULT STDMETHODCALLTYPE OnPropertyValueChanged(LPCWSTR, const PROPERTYKEY) override { return S_OK; }
private:
    static void bump() { g_deviceGeneration.fetch_add(1, std::memory_order_relaxed); }
};

NotificationClient g_notificationClient;
ComPtr<IMMDeviceEnumerator> g_notificationEnumerator;  // keeps the registration alive
std::once_flag g_notificationOnce;

void ensureNotifications() {
    std::call_once(g_notificationOnce, [] {
        // Registered once, from a thread that holds COM for the call. The
        // enumerator object lives for the process; MMDevice delivers the
        // callbacks on its own thread regardless of who registered.
        if (makeEnumerator(g_notificationEnumerator)) {
            g_notificationEnumerator->RegisterEndpointNotificationCallback(&g_notificationClient);
        }
    });
}

// ── The stream ──────────────────────────────────────────────────────────────
struct Stream {
    ComPtr<IAudioClient> client;
    ComPtr<IAudioRenderClient> render;
    ComPtr<IAudioClock> clock;
    HANDLE event = nullptr;
    bool exclusive = false;
    int sampleRate = 0;
    int channels = 0;
    int format = 0;
    UINT32 bytesPerFrame = 0;
    UINT32 bufferFrames = 0;   // endpoint buffer, in frames
    UINT32 periodFrames = 0;   // frames the device consumes per event
    UINT64 clockFrequency = 0;
    std::atomic<uint64_t> writtenFrames{0};
    bool started = false;
    // Exclusive mode fills whole buffers per event, so partial writes are
    // staged until a buffer's worth is there.
    std::vector<uint8_t> staging;
    size_t stagedBytes = 0;
    std::mutex mutex;

    ~Stream() {
        if (client && started) client->Stop();
        if (event) CloseHandle(event);
    }
};

REFERENCE_TIME framesToReferenceTime(UINT32 frames, int sampleRate) {
    return static_cast<REFERENCE_TIME>(10000.0 * 1000.0 / sampleRate * frames + 0.5);
}

UINT32 referenceTimeToFrames(REFERENCE_TIME t, int sampleRate) {
    return static_cast<UINT32>(static_cast<double>(t) * sampleRate / 10000000.0 + 0.5);
}

Stream* streamFrom(jlong handle) { return reinterpret_cast<Stream*>(static_cast<intptr_t>(handle)); }

// Initialises an IAudioClient for the stream, handling the exclusive-mode
// buffer alignment dance: if the requested period does not land on the
// device's frame alignment WASAPI answers AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED
// with the size it would accept, and the client has to be thrown away and
// re-activated with that size.
bool initialiseClient(IMMDevice* device, Stream& s, const WAVEFORMATEXTENSIBLE& wf, int bufferMillis) {
    for (int attempt = 0; attempt < 2; ++attempt) {
        HRESULT hr = device->Activate(IID_IAudioClient, CLSCTX_ALL, nullptr, s.client.putVoid());
        if (FAILED(hr)) { setError("IMMDevice::Activate(IAudioClient)", hr); return false; }

        REFERENCE_TIME defaultPeriod = 0, minPeriod = 0;
        s.client->GetDevicePeriod(&defaultPeriod, &minPeriod);

        REFERENCE_TIME duration = static_cast<REFERENCE_TIME>(bufferMillis) * 10000;
        DWORD flags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
        REFERENCE_TIME period = 0;
        if (s.exclusive) {
            // Event-driven exclusive: buffer == period, at least the device
            // minimum, and the engine rejects anything over half a second.
            if (attempt == 0) {
                period = std::max(duration, minPeriod);
                period = std::min<REFERENCE_TIME>(period, 5000000);
            } else {
                period = framesToReferenceTime(s.bufferFrames, s.sampleRate);
            }
            duration = period;
        } else {
            // Shared: let the engine convert rate and width; keep our buffer
            // comfortably larger than the engine period.
            flags |= AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY;
            duration = std::max(duration, defaultPeriod * 3);
        }

        hr = s.client->Initialize(s.exclusive ? AUDCLNT_SHAREMODE_EXCLUSIVE : AUDCLNT_SHAREMODE_SHARED,
                                  flags, duration, period,
                                  reinterpret_cast<const WAVEFORMATEX*>(&wf), nullptr);
        if (hr == AUDCLNT_E_BUFFER_SIZE_NOT_ALIGNED && s.exclusive && attempt == 0) {
            UINT32 aligned = 0;
            s.client->GetBufferSize(&aligned);
            s.bufferFrames = aligned;
            s.client.reset();
            continue;
        }
        if (FAILED(hr)) { setError("IAudioClient::Initialize", hr); return false; }

        hr = s.client->GetBufferSize(&s.bufferFrames);
        if (FAILED(hr)) { setError("IAudioClient::GetBufferSize", hr); return false; }
        s.periodFrames = s.exclusive ? s.bufferFrames : referenceTimeToFrames(defaultPeriod, s.sampleRate);
        return true;
    }
    setError("exclusive buffer alignment did not converge");
    return false;
}

Stream* openStream(const std::wstring& id, int sampleRate, int channels, int format, bool exclusive, int bufferMillis) {
    FormatSpec spec{};
    if (!describeFormat(format, spec)) { setError("unknown sample format"); return nullptr; }
    if (sampleRate < 8000 || sampleRate > 1536000 || channels < 1 || channels > 32) {
        setError("unsupported rate or channel count");
        return nullptr;
    }
    bufferMillis = std::clamp(bufferMillis, 3, 2000);

    ComPtr<IMMDeviceEnumerator> enumerator;
    if (!makeEnumerator(enumerator)) return nullptr;
    ensureNotifications();
    ComPtr<IMMDevice> device;
    if (!openDevice(enumerator.p, id, device)) return nullptr;

    WAVEFORMATEXTENSIBLE wf;
    buildWaveFormat(wf, sampleRate, channels, spec);

    auto* s = new Stream();
    s->exclusive = exclusive;
    s->sampleRate = sampleRate;
    s->channels = channels;
    s->format = format;
    s->bytesPerFrame = wf.Format.nBlockAlign;

    if (exclusive) {
        // Exclusive mode takes exactly the format or nothing; there is no
        // closest match to negotiate with.
        ComPtr<IAudioClient> probe;
        HRESULT hr = device->Activate(IID_IAudioClient, CLSCTX_ALL, nullptr, probe.putVoid());
        if (FAILED(hr)) { setError("IMMDevice::Activate", hr); delete s; return nullptr; }
        hr = probe->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, reinterpret_cast<const WAVEFORMATEX*>(&wf), nullptr);
        // Released before the real client is initialised: some drivers (and
        // Wine) open the endpoint for a probe and answer DEVICE_IN_USE to an
        // exclusive Initialize while it is alive.
        probe.reset();
        if (hr != S_OK) { setError("format not supported in exclusive mode", hr); delete s; return nullptr; }
    }

    if (!initialiseClient(device.p, *s, wf, bufferMillis)) { delete s; return nullptr; }

    s->event = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    if (!s->event) { setError("CreateEvent failed"); delete s; return nullptr; }
    HRESULT hr = s->client->SetEventHandle(s->event);
    if (FAILED(hr)) { setError("IAudioClient::SetEventHandle", hr); delete s; return nullptr; }
    hr = s->client->GetService(IID_IAudioRenderClient, s->render.putVoid());
    if (FAILED(hr)) { setError("GetService(IAudioRenderClient)", hr); delete s; return nullptr; }
    hr = s->client->GetService(IID_IAudioClock, s->clock.putVoid());
    if (SUCCEEDED(hr)) s->clock->GetFrequency(&s->clockFrequency);

    if (exclusive) {
        s->staging.assign(static_cast<size_t>(s->bufferFrames) * s->bytesPerFrame, 0);
        s->stagedBytes = 0;
    }
    return s;
}

// Pushes one full endpoint buffer (exclusive) or as much as fits (shared).
// Returns frames pushed, or -1 on error. Must hold s.mutex.
int pushFrames(Stream& s, const uint8_t* data, UINT32 frames) {
    BYTE* dst = nullptr;
    HRESULT hr = s.render->GetBuffer(frames, &dst);
    if (FAILED(hr)) { setError("IAudioRenderClient::GetBuffer", hr); return -1; }
    std::memcpy(dst, data, static_cast<size_t>(frames) * s.bytesPerFrame);
    hr = s.render->ReleaseBuffer(frames, 0);
    if (FAILED(hr)) { setError("IAudioRenderClient::ReleaseBuffer", hr); return -1; }
    s.writtenFrames.fetch_add(frames, std::memory_order_relaxed);
    return static_cast<int>(frames);
}

bool waitForSpace(Stream& s, DWORD timeoutMs) {
    DWORD r = WaitForSingleObject(s.event, timeoutMs);
    if (r == WAIT_OBJECT_0) return true;
    setError(r == WAIT_TIMEOUT ? "timed out waiting for the audio engine" : "WaitForSingleObject failed");
    return false;
}

// Blocking write of `frames` frames from `src`. Returns frames consumed from
// src (always all of them on success) or a negative error code.
int writeFrames(Stream& s, const uint8_t* src, UINT32 frames) {
    std::lock_guard<std::mutex> lock(s.mutex);
    if (!s.render) { setError("stream closed"); return -2; }
    const size_t frameBytes = s.bytesPerFrame;

    if (s.exclusive) {
        UINT32 remaining = frames;
        const uint8_t* cursor = src;
        const size_t bufferBytes = static_cast<size_t>(s.bufferFrames) * frameBytes;
        while (remaining > 0) {
            const size_t room = bufferBytes - s.stagedBytes;
            const size_t take = std::min(room, static_cast<size_t>(remaining) * frameBytes);
            std::memcpy(s.staging.data() + s.stagedBytes, cursor, take);
            s.stagedBytes += take;
            cursor += take;
            remaining -= static_cast<UINT32>(take / frameBytes);
            if (s.stagedBytes == bufferBytes) {
                // Before Start() the whole buffer is free; afterwards each
                // event hands us one buffer to refill.
                if (s.started && !waitForSpace(s, 2000)) return -3;
                if (pushFrames(s, s.staging.data(), s.bufferFrames) < 0) return -4;
                s.stagedBytes = 0;
            }
        }
        return static_cast<int>(frames);
    }

    UINT32 remaining = frames;
    const uint8_t* cursor = src;
    while (remaining > 0) {
        UINT32 padding = 0;
        HRESULT hr = s.client->GetCurrentPadding(&padding);
        if (FAILED(hr)) { setError("IAudioClient::GetCurrentPadding", hr); return -4; }
        UINT32 available = s.bufferFrames > padding ? s.bufferFrames - padding : 0;
        if (available == 0) {
            if (!s.started) { setError("shared buffer full before start"); return -5; }
            if (!waitForSpace(s, 2000)) return -3;
            continue;
        }
        const UINT32 n = std::min(available, remaining);
        if (pushFrames(s, cursor, n) < 0) return -4;
        cursor += static_cast<size_t>(n) * frameBytes;
        remaining -= n;
    }
    return static_cast<int>(frames);
}

// Exclusive mode: pad the staged partial buffer with silence and push it, so
// the tail of a track is heard. Must hold s.mutex.
bool flushStaged(Stream& s) {
    if (!s.exclusive || s.stagedBytes == 0) return true;
    const size_t bufferBytes = static_cast<size_t>(s.bufferFrames) * s.bytesPerFrame;
    std::memset(s.staging.data() + s.stagedBytes, 0, bufferBytes - s.stagedBytes);
    if (s.started && !waitForSpace(s, 2000)) return false;
    const bool ok = pushFrames(s, s.staging.data(), s.bufferFrames) >= 0;
    s.stagedBytes = 0;
    return ok;
}

std::wstring toWide(JNIEnv* env, jstring str) {
    if (str == nullptr) return L"";
    const jchar* chars = env->GetStringChars(str, nullptr);
    if (chars == nullptr) return L"";
    const jsize length = env->GetStringLength(str);
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(length));
    env->ReleaseStringChars(str, chars);
    return out;
}

jstring toJava(JNIEnv* env, const std::wstring& str) {
    return env->NewString(reinterpret_cast<const jchar*>(str.data()), static_cast<jsize>(str.size()));
}

thread_local HANDLE g_mmcssHandle = nullptr;

}  // namespace

extern "C" {

#define WASAPI_JNI(ret, name) JNIEXPORT ret JNICALL Java_tf_monochrome_desktop_audio_wasapi_WasapiNative_##name

// Each entry: "<endpoint id>\u0001<friendly name>\u0001<1 if default else 0>".
WASAPI_JNI(jobjectArray, nativeListDevices)(JNIEnv* env, jobject) {
    ComScope com;
    ComPtr<IMMDeviceEnumerator> enumerator;
    jclass stringClass = env->FindClass("java/lang/String");
    if (!makeEnumerator(enumerator)) return env->NewObjectArray(0, stringClass, nullptr);
    ensureNotifications();

    std::wstring defaultId;
    {
        ComPtr<IMMDevice> def;
        if (SUCCEEDED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, def.put()))) defaultId = deviceId(def.p);
    }
    ComPtr<IMMDeviceCollection> devices;
    if (FAILED(enumerator->EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE, devices.put()))) {
        return env->NewObjectArray(0, stringClass, nullptr);
    }
    UINT count = 0;
    devices->GetCount(&count);
    std::vector<std::wstring> rows;
    for (UINT i = 0; i < count; ++i) {
        ComPtr<IMMDevice> device;
        if (FAILED(devices->Item(i, device.put()))) continue;
        const std::wstring id = deviceId(device.p);
        rows.push_back(id + L"\u0001" + deviceName(device.p) + L"\u0001" + (id == defaultId ? L"1" : L"0"));
    }
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(rows.size()), stringClass, nullptr);
    for (size_t i = 0; i < rows.size(); ++i) {
        jstring row = toJava(env, rows[i]);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), row);
        env->DeleteLocalRef(row);
    }
    return out;
}

WASAPI_JNI(jstring, nativeDefaultDeviceId)(JNIEnv* env, jobject) {
    ComScope com;
    ComPtr<IMMDeviceEnumerator> enumerator;
    if (!makeEnumerator(enumerator)) return nullptr;
    ComPtr<IMMDevice> def;
    if (FAILED(enumerator->GetDefaultAudioEndpoint(eRender, eMultimedia, def.put()))) return nullptr;
    return toJava(env, deviceId(def.p));
}

WASAPI_JNI(jboolean, nativeIsFormatSupported)(JNIEnv* env, jobject, jstring deviceIdStr, jint sampleRate,
                                              jint channels, jint format, jboolean exclusive) {
    ComScope com;
    FormatSpec spec{};
    if (!describeFormat(format, spec)) return JNI_FALSE;
    ComPtr<IMMDeviceEnumerator> enumerator;
    if (!makeEnumerator(enumerator)) return JNI_FALSE;
    ComPtr<IMMDevice> device;
    if (!openDevice(enumerator.p, toWide(env, deviceIdStr), device)) return JNI_FALSE;
    ComPtr<IAudioClient> client;
    if (FAILED(device->Activate(IID_IAudioClient, CLSCTX_ALL, nullptr, client.putVoid()))) return JNI_FALSE;
    WAVEFORMATEXTENSIBLE wf;
    buildWaveFormat(wf, sampleRate, channels, spec);
    if (exclusive) {
        return client->IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, reinterpret_cast<const WAVEFORMATEX*>(&wf), nullptr) == S_OK
            ? JNI_TRUE : JNI_FALSE;
    }
    // Shared mode converts anything PCM on Windows 10+; report what the engine
    // itself would accept natively so the caller can prefer it.
    WAVEFORMATEX* closest = nullptr;
    HRESULT hr = client->IsFormatSupported(AUDCLNT_SHAREMODE_SHARED, reinterpret_cast<const WAVEFORMATEX*>(&wf), &closest);
    if (closest) CoTaskMemFree(closest);
    return hr == S_OK ? JNI_TRUE : JNI_FALSE;
}

WASAPI_JNI(jlong, nativeOpen)(JNIEnv* env, jobject, jstring deviceIdStr, jint sampleRate, jint channels,
                              jint format, jboolean exclusive, jint bufferMillis) {
    ComScope com;
    Stream* s = openStream(toWide(env, deviceIdStr), sampleRate, channels, format, exclusive == JNI_TRUE, bufferMillis);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(s));
}

// [sampleRate, channels, format, bufferFrames, periodFrames, exclusive, bytesPerFrame]
WASAPI_JNI(jintArray, nativeDescribe)(JNIEnv* env, jobject, jlong handle) {
    Stream* s = streamFrom(handle);
    jint values[7] = {0, 0, 0, 0, 0, 0, 0};
    if (s) {
        values[0] = s->sampleRate;
        values[1] = s->channels;
        values[2] = s->format;
        values[3] = static_cast<jint>(s->bufferFrames);
        values[4] = static_cast<jint>(s->periodFrames);
        values[5] = s->exclusive ? 1 : 0;
        values[6] = static_cast<jint>(s->bytesPerFrame);
    }
    jintArray out = env->NewIntArray(7);
    env->SetIntArrayRegion(out, 0, 7, values);
    return out;
}

WASAPI_JNI(jboolean, nativeStart)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->started) return JNI_TRUE;
    if (s->exclusive && s->writtenFrames.load() == 0) {
        // The engine needs a primed buffer before Start in exclusive mode;
        // whatever is staged goes out, silence-padded if short.
        // Zeroed from stagedBytes even when nothing is staged: after a reset
        // the buffer still holds the previous position's audio.
        const size_t bufferBytes = static_cast<size_t>(s->bufferFrames) * s->bytesPerFrame;
        std::memset(s->staging.data() + s->stagedBytes, 0, bufferBytes - s->stagedBytes);
        if (pushFrames(*s, s->staging.data(), s->bufferFrames) < 0) return JNI_FALSE;
        s->stagedBytes = 0;
    }
    HRESULT hr = s->client->Start();
    if (FAILED(hr)) { setError("IAudioClient::Start", hr); return JNI_FALSE; }
    s->started = true;
    return JNI_TRUE;
}

WASAPI_JNI(jboolean, nativeStop)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (!s->started) return JNI_TRUE;
    HRESULT hr = s->client->Stop();
    if (FAILED(hr)) { setError("IAudioClient::Stop", hr); return JNI_FALSE; }
    s->started = false;
    return JNI_TRUE;
}

// Stop and discard everything queued (a seek, a track change with no crossfade).
WASAPI_JNI(jboolean, nativeReset)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->started) { s->client->Stop(); s->started = false; }
    HRESULT hr = s->client->Reset();
    if (FAILED(hr)) { setError("IAudioClient::Reset", hr); return JNI_FALSE; }
    s->stagedBytes = 0;
    s->writtenFrames.store(0);
    return JNI_TRUE;
}

// Push the staged tail (exclusive mode) padded with silence. Shared mode has
// nothing staged and returns true.
WASAPI_JNI(jboolean, nativeFlush)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(s->mutex);
    return flushStaged(*s) ? JNI_TRUE : JNI_FALSE;
}

// Blocking. `buffer` is a direct ByteBuffer holding interleaved frames in the
// stream's format starting at offsetBytes. Returns frames written or <0.
WASAPI_JNI(jint, nativeWrite)(JNIEnv* env, jobject, jlong handle, jobject buffer, jint offsetBytes, jint frames) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) { setError("null stream"); return -1; }
    if (frames <= 0) return 0;
    auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (base == nullptr) { setError("nativeWrite needs a direct ByteBuffer"); return -1; }
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    const jlong needed = static_cast<jlong>(offsetBytes) + static_cast<jlong>(frames) * s->bytesPerFrame;
    if (offsetBytes < 0 || needed > capacity) { setError("nativeWrite range exceeds the buffer"); return -1; }
    return writeFrames(*s, base + offsetBytes, static_cast<UINT32>(frames));
}

// Frames of free space that a write would not block on right now.
WASAPI_JNI(jint, nativeAvailableFrames)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return 0;
    std::lock_guard<std::mutex> lock(s->mutex);
    if (s->exclusive) {
        const size_t bufferBytes = static_cast<size_t>(s->bufferFrames) * s->bytesPerFrame;
        return static_cast<jint>((bufferBytes - s->stagedBytes) / s->bytesPerFrame);
    }
    UINT32 padding = 0;
    if (FAILED(s->client->GetCurrentPadding(&padding))) return 0;
    return static_cast<jint>(s->bufferFrames > padding ? s->bufferFrames - padding : 0);
}

WASAPI_JNI(jlong, nativeWrittenFrames)(JNIEnv*, jobject, jlong handle) {
    Stream* s = streamFrom(handle);
    return s ? static_cast<jlong>(s->writtenFrames.load(std::memory_order_relaxed)) : 0;
}

// Frames the endpoint has rendered since Start, from IAudioClock.
WASAPI_JNI(jlong, nativePlayedFrames)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s || !s->clock || s->clockFrequency == 0) return -1;
    UINT64 position = 0;
    if (FAILED(s->clock->GetPosition(&position, nullptr))) return -1;
    return static_cast<jlong>(static_cast<double>(position) * s->sampleRate / static_cast<double>(s->clockFrequency));
}

WASAPI_JNI(jint, nativeLatencyFrames)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return 0;
    REFERENCE_TIME latency = 0;
    if (FAILED(s->client->GetStreamLatency(&latency))) return 0;
    jint frames = static_cast<jint>(referenceTimeToFrames(latency, s->sampleRate));
    if (s->exclusive) frames += static_cast<jint>(s->bufferFrames);  // the second (in-flight) buffer
    return frames;
}

WASAPI_JNI(void, nativeClose)(JNIEnv*, jobject, jlong handle) {
    ComScope com;
    Stream* s = streamFrom(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> lock(s->mutex);
        if (s->started) { s->client->Stop(); s->started = false; }
        s->render.reset();
        s->clock.reset();
        s->client.reset();
    }
    delete s;
}

WASAPI_JNI(jstring, nativeLastError)(JNIEnv* env, jobject) {
    return env->NewStringUTF(g_lastError.c_str());
}

WASAPI_JNI(jlong, nativeDeviceGeneration)(JNIEnv*, jobject) {
    ComScope com;
    ensureNotifications();
    return static_cast<jlong>(g_deviceGeneration.load(std::memory_order_relaxed));
}

// MMCSS: raise the calling thread (the sink's writer) to the "Pro Audio"
// scheduling class for the life of the thread, as a native player would.
WASAPI_JNI(jboolean, nativeEnterProAudio)(JNIEnv*, jobject) {
    if (g_mmcssHandle) return JNI_TRUE;
    DWORD taskIndex = 0;
    g_mmcssHandle = AvSetMmThreadCharacteristicsW(L"Pro Audio", &taskIndex);
    if (g_mmcssHandle) AvSetMmThreadPriority(g_mmcssHandle, AVRT_PRIORITY_HIGH);
    return g_mmcssHandle ? JNI_TRUE : JNI_FALSE;
}

WASAPI_JNI(void, nativeLeaveProAudio)(JNIEnv*, jobject) {
    if (g_mmcssHandle) { AvRevertMmThreadCharacteristics(g_mmcssHandle); g_mmcssHandle = nullptr; }
}

}  // extern "C"
