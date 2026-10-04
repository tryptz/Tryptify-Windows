// System Media Transport Controls: the Windows side of what Android's
// MediaSession gave the app. It is what the keyboard's media keys, the
// volume flyout's "now playing" card, the lock screen and Bluetooth headset
// buttons talk to. Without it a desktop player is deaf to all of them.
//
// Written against C++/WinRT, which ships with the Windows SDK, so it builds
// with MSVC only; the MinGW cross build leaves it out and the app runs
// without it (SmtcNative.isAvailable is false).
//
// Threading: every call from Kotlin arrives on one dedicated thread (see
// SmtcNative.kt), which this file joins to the multithreaded apartment.
// Button and seek events arrive on the WinRT thread pool and are forwarded
// to the Kotlin callback after attaching that thread to the JVM.

#include <jni.h>

#include <windows.h>
#include <systemmediatransportcontrolsinterop.h>

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.h>
#include <winrt/Windows.Storage.Streams.h>

#include <chrono>
#include <cstdio>
#include <memory>
#include <string>

using namespace winrt;
using namespace winrt::Windows::Media;
using winrt::Windows::Foundation::TimeSpan;
using winrt::Windows::Foundation::Uri;
using winrt::Windows::Storage::StorageFile;
using winrt::Windows::Storage::Streams::RandomAccessStreamReference;

namespace {

JavaVM* g_vm = nullptr;

JNIEnv* attachedEnv() {
    JNIEnv* env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_8) == JNI_OK) return env;
    // Thread-pool threads are reused, so they stay attached (as daemons, so
    // they never hold the JVM open).
    if (g_vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void**>(&env), nullptr) != JNI_OK) return nullptr;
    return env;
}

// Owned jointly by the handle Kotlin holds and by the two event handlers:
// revoking an event does not wait for a handler already running on the
// thread pool, so the session (and the JVM callback it calls) must outlive
// whichever of them lets go last.
struct Session {
    SystemMediaTransportControls smtc{nullptr};
    event_token buttonToken{};
    event_token seekToken{};
    jobject callback = nullptr;   // global ref: SmtcNative.Callback
    jmethodID onButton = nullptr;
    jmethodID onSeek = nullptr;

    ~Session() {
        if (callback == nullptr) return;
        if (JNIEnv* env = attachedEnv()) env->DeleteGlobalRef(callback);
    }
};

using SessionPtr = std::shared_ptr<Session>;

Session* sessionOf(jlong handle) {
    auto* holder = reinterpret_cast<SessionPtr*>(handle);
    return holder == nullptr ? nullptr : holder->get();
}

thread_local bool t_apartment = false;

void ensureApartment() {
    if (t_apartment) return;
    try {
        init_apartment(apartment_type::multi_threaded);
    } catch (hresult_error const& e) {
        // RPC_E_CHANGED_MODE: the thread already chose an apartment, which is
        // fine for these agile objects.
        if (e.code() != RPC_E_CHANGED_MODE) throw;
    }
    t_apartment = true;
}

std::wstring toWide(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const jchar* chars = env->GetStringChars(s, nullptr);
    std::wstring out(reinterpret_cast<const wchar_t*>(chars), static_cast<size_t>(env->GetStringLength(s)));
    env->ReleaseStringChars(s, chars);
    return out;
}

void report(const char* what, hresult_error const& e) {
    std::fprintf(stderr, "W/SmtcNative: %s failed: 0x%08X %ls\n", what,
                 static_cast<unsigned>(e.code()), e.message().c_str());
}

constexpr int64_t kTicksPerMs = 10'000;  // TimeSpan counts 100 ns ticks

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_8;
}

JNIEXPORT jlong JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeAttach(JNIEnv* env, jclass, jlong hwnd, jobject callback) {
    try {
        ensureApartment();
        auto interop = get_activation_factory<SystemMediaTransportControls, ISystemMediaTransportControlsInterop>();
        SystemMediaTransportControls smtc{nullptr};
        check_hresult(interop->GetForWindow(reinterpret_cast<HWND>(hwnd), guid_of<SystemMediaTransportControls>(), put_abi(smtc)));

        auto session = std::make_shared<Session>();
        session->smtc = smtc;
        session->callback = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        session->onButton = env->GetMethodID(cls, "onButton", "(I)V");
        session->onSeek = env->GetMethodID(cls, "onSeek", "(J)V");

        smtc.IsEnabled(true);
        smtc.IsPlayEnabled(true);
        smtc.IsPauseEnabled(true);
        smtc.IsStopEnabled(true);
        smtc.IsNextEnabled(true);
        smtc.IsPreviousEnabled(true);
        smtc.PlaybackStatus(MediaPlaybackStatus::Stopped);

        session->buttonToken = smtc.ButtonPressed(
            [session](SystemMediaTransportControls const&, SystemMediaTransportControlsButtonPressedEventArgs const& args) {
                JNIEnv* e = attachedEnv();
                if (e == nullptr) return;
                e->CallVoidMethod(session->callback, session->onButton, static_cast<jint>(args.Button()));
                if (e->ExceptionCheck()) e->ExceptionClear();
            });
        session->seekToken = smtc.PlaybackPositionChangeRequested(
            [session](SystemMediaTransportControls const&, PlaybackPositionChangeRequestedEventArgs const& args) {
                JNIEnv* e = attachedEnv();
                if (e == nullptr) return;
                e->CallVoidMethod(session->callback, session->onSeek, static_cast<jlong>(args.RequestedPlaybackPosition().count() / kTicksPerMs));
                if (e->ExceptionCheck()) e->ExceptionClear();
            });
        return reinterpret_cast<jlong>(new SessionPtr(session));
    } catch (hresult_error const& e) {
        report("attach", e);
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeSetMetadata(
    JNIEnv* env, jclass, jlong handle, jstring title, jstring artist, jstring album, jstring albumArtist,
    jint trackNumber, jstring thumbnail) {
    Session* session = sessionOf(handle);
    if (session == nullptr) return;
    try {
        ensureApartment();
        auto updater = session->smtc.DisplayUpdater();
        updater.Type(MediaPlaybackType::Music);
        auto music = updater.MusicProperties();
        music.Title(hstring(toWide(env, title)));
        music.Artist(hstring(toWide(env, artist)));
        music.AlbumTitle(hstring(toWide(env, album)));
        music.AlbumArtist(hstring(toWide(env, albumArtist)));
        music.TrackNumber(static_cast<uint32_t>(trackNumber > 0 ? trackNumber : 0));

        std::wstring thumb = toWide(env, thumbnail);
        if (thumb.empty()) {
            updater.Thumbnail(nullptr);
        } else if (thumb.rfind(L"http://", 0) == 0 || thumb.rfind(L"https://", 0) == 0) {
            updater.Thumbnail(RandomAccessStreamReference::CreateFromUri(Uri(hstring(thumb))));
        } else {
            // An unpackaged app cannot hand SMTC a file: URI; a StorageFile works.
            try {
                auto file = StorageFile::GetFileFromPathAsync(hstring(thumb)).get();
                updater.Thumbnail(RandomAccessStreamReference::CreateFromFile(file));
            } catch (hresult_error const& e) {
                report("thumbnail", e);
                updater.Thumbnail(nullptr);
            }
        }
        updater.Update();
    } catch (hresult_error const& e) {
        report("metadata", e);
    }
}

// 0 closed, 1 changing, 2 stopped, 3 playing, 4 paused: MediaPlaybackStatus.
JNIEXPORT void JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeSetPlaybackStatus(JNIEnv*, jclass, jlong handle, jint status) {
    Session* session = sessionOf(handle);
    if (session == nullptr) return;
    try {
        ensureApartment();
        session->smtc.PlaybackStatus(static_cast<MediaPlaybackStatus>(status));
    } catch (hresult_error const& e) {
        report("status", e);
    }
}

JNIEXPORT void JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeSetTimeline(
    JNIEnv*, jclass, jlong handle, jlong positionMs, jlong durationMs, jboolean seekable) {
    Session* session = sessionOf(handle);
    if (session == nullptr) return;
    try {
        ensureApartment();
        SystemMediaTransportControlsTimelineProperties timeline;
        int64_t duration = durationMs > 0 ? durationMs : 0;
        int64_t position = positionMs < 0 ? 0 : (duration > 0 && positionMs > duration ? duration : positionMs);
        timeline.StartTime(TimeSpan{0});
        timeline.MinSeekTime(TimeSpan{0});
        timeline.EndTime(TimeSpan{duration * kTicksPerMs});
        timeline.MaxSeekTime(TimeSpan{(seekable ? duration : 0) * kTicksPerMs});
        timeline.Position(TimeSpan{position * kTicksPerMs});
        session->smtc.UpdateTimelineProperties(timeline);
    } catch (hresult_error const& e) {
        report("timeline", e);
    }
}

JNIEXPORT void JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeSetNavigation(
    JNIEnv*, jclass, jlong handle, jboolean canPrevious, jboolean canNext) {
    Session* session = sessionOf(handle);
    if (session == nullptr) return;
    try {
        ensureApartment();
        session->smtc.IsPreviousEnabled(canPrevious == JNI_TRUE);
        session->smtc.IsNextEnabled(canNext == JNI_TRUE);
    } catch (hresult_error const& e) {
        report("navigation", e);
    }
}

JNIEXPORT void JNICALL
Java_tf_monochrome_desktop_platform_windows_SmtcNative_nativeDetach(JNIEnv*, jclass, jlong handle) {
    auto* holder = reinterpret_cast<SessionPtr*>(handle);
    if (holder == nullptr) return;
    Session* session = holder->get();
    try {
        ensureApartment();
        session->smtc.ButtonPressed(session->buttonToken);
        session->smtc.PlaybackPositionChangeRequested(session->seekToken);
        session->smtc.DisplayUpdater().ClearAll();
        session->smtc.IsEnabled(false);
    } catch (hresult_error const& e) {
        report("detach", e);
    }
    // The handlers drop their references once the revoked delegates are
    // released; the last owner deletes the callback's global ref.
    delete holder;
}

}  // extern "C"
