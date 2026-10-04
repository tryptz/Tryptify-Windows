#if defined(_WIN32)
// Ahead of everything else: GLEW refuses to be included after <GL/gl.h>.
#include <GL/glew.h>
#endif

#include "projectm_bridge.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#if defined(_WIN32)
#include <process.h>
#include <windows.h>
#define TF_GETPID _getpid
#else
#include <unistd.h>
#define TF_GETPID getpid
#endif

#include <android/log.h>

namespace {
constexpr int kDefaultFps = 60;
/** How many other presets a timed switch or Next tries when one fails to load. */
constexpr int kMaxSwitchRetries = 3;
constexpr const char* kTag = "ProjectMBridge";

#if defined(_WIN32)
/**
 * Loads the OpenGL entry points projectM calls, with the render thread's
 * context current.
 *
 * opengl32.dll exports OpenGL 1.1 and nothing newer. Everything projectM uses
 * past that is a GLEW function pointer, null until glewInit has run against a
 * current context -- and projectm_create compiles shaders straight away, so
 * without this the first frame is a call through null. Run on every create
 * rather than once per process: the context belongs to the visualizer's hidden
 * window, which is made afresh for each session, and WGL only promises that a
 * pointer carries over between contexts of the same pixel format on the same
 * device. glewExperimental, because a core profile context lists no extension
 * strings for core functions and GLEW would otherwise skip loading them.
 */
bool LoadGlEntryPoints() {
    glewExperimental = GL_TRUE;
    const GLenum result = glewInit();
    // GLEW probes with queries a core profile rejects. Drained so the errors
    // are not mistaken for projectM's own; bounded, because without a current
    // context some drivers report an error on every call.
    for (int i = 0; i < 16 && glGetError() != GL_NO_ERROR; ++i) {
    }
    if (result != GLEW_OK) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "glewInit failed: %s",
                            reinterpret_cast<const char*>(glewGetErrorString(result)));
        return false;
    }
    return true;
}

/**
 * UTF-8, which is what JNI hands over, widened for the Win32 file calls.
 *
 * A narrow path given to fopen goes through the ANSI code page, so a profile
 * folder outside it (C:\Users\Jürgen on a Cyrillic code page, any CJK name on
 * a Western one) failed to open and left the crash sentinel unwritten.
 */
std::wstring Widen(const std::string& utf8) {
    if (utf8.empty()) {
        return {};
    }
    const int length = MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), nullptr, 0);
    if (length <= 0) {
        return {};
    }
    std::wstring wide(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, utf8.data(), static_cast<int>(utf8.size()), wide.data(), length);
    return wide;
}
#endif

FILE* OpenForWriting(const std::string& path) {
#if defined(_WIN32)
    // Binary: the same bytes Android writes, "\n" and all.
    return _wfopen(Widen(path).c_str(), L"wb");
#else
    return std::fopen(path.c_str(), "w");
#endif
}

void RemoveFile(const std::string& path) {
#if defined(_WIN32)
    _wremove(Widen(path).c_str());
#else
    std::remove(path.c_str());
#endif
}
}

ProjectMBridge::ProjectMBridge(
        std::string asset_root,
        std::string preset_root,
        std::string texture_root,
        int width,
        int height,
        int mesh_width,
        int mesh_height,
        std::unordered_set<std::string> excluded_presets,
        std::string crash_sentinel_path)
        : asset_root_(std::move(asset_root)),
          preset_root_(std::move(preset_root)),
          texture_root_(std::move(texture_root)),
          excluded_presets_(std::move(excluded_presets)),
          crash_sentinel_path_(std::move(crash_sentinel_path)) {
#if defined(_WIN32)
    if (!LoadGlEntryPoints()) {
        return;
    }
#endif
    projectm_ = projectm_create();
    if (projectm_ == nullptr) {
        return;
    }

    playlist_ = projectm_playlist_create(projectm_);
    if (playlist_ == nullptr) {
        projectm_destroy(projectm_);
        projectm_ = nullptr;
        return;
    }

    projectm_set_window_size(projectm_, width, height);
    projectm_set_mesh_size(projectm_, mesh_width, mesh_height);
    projectm_set_fps(projectm_, kDefaultFps);
    projectm_set_aspect_correction(projectm_, true);
    projectm_set_beat_sensitivity(projectm_, 1.0f);
    projectm_set_preset_duration(projectm_, 20.0);
    projectm_set_soft_cut_duration(projectm_, 2.0);
    projectm_set_hard_cut_enabled(projectm_, true);
    projectm_set_hard_cut_duration(projectm_, 10.0);

    const char* texture_paths[] = {texture_root_.c_str()};
    projectm_set_texture_search_paths(projectm_, texture_paths, 1);
    projectm_playlist_add_path(playlist_, preset_root_.c_str(), true, false);
    projectm_playlist_set_shuffle(playlist_, true);
    RemoveExcludedPresets();
    BuildPresetIndex();

    // Taken over from the playlist, which registered its own on creation. Its
    // version picks the next preset and loads it in one step and only says
    // which it was afterwards -- too late to record, because loading is where
    // a preset that kills the GPU driver does it. Choosing here means the
    // sentinel names the preset before it is touched.
    projectm_set_preset_switch_requested_event_callback(projectm_, &OnSwitchRequested, this);
    projectm_set_preset_switch_failed_event_callback(projectm_, &OnSwitchFailed, this);

    if (projectm_playlist_size(playlist_) > 0) {
        PlayIndex(0, true, true);
    }
}

ProjectMBridge::~ProjectMBridge() {
    // A clean release: whatever was on screen did not take the process down.
    if (!crash_sentinel_path_.empty()) {
        RemoveFile(crash_sentinel_path_);
    }
    if (playlist_ != nullptr) {
        projectm_playlist_destroy(playlist_);
        playlist_ = nullptr;
    }
    if (projectm_ != nullptr) {
        projectm_destroy(projectm_);
        projectm_ = nullptr;
    }
}

bool ProjectMBridge::IsReady() const {
    return projectm_ != nullptr && playlist_ != nullptr;
}

void ProjectMBridge::Resize(int width, int height) {
    if (projectm_ != nullptr) {
        projectm_set_window_size(projectm_, width, height);
    }
}

void ProjectMBridge::RenderFrame(int64_t /*frame_time_nanos*/) {
    if (!IsReady() || paused_) {
        return;
    }
    PushBufferedAudioToProjectM();
    projectm_opengl_render_frame(projectm_);
}

void ProjectMBridge::PushPcm(const float* data, size_t count, int channel_count, int sample_rate) {
    if (!IsReady() || data == nullptr || count == 0) {
        return;
    }
    audio_buffer_.Push(data, count, channel_count, sample_rate);
}

/**
 * Switch to [preset_path], instantly.
 *
 * Must be called with the GL context current -- switching compiles the
 * preset's shaders, so off the render thread it silently does nothing and
 * leaves whatever was on screen. See ProjectMEngineRepository, which defers
 * every call to here into renderFrame for that reason.
 *
 * The old implementation walked the playlist twice and ended with a call to
 * projectm_load_preset_file that no input could reach: a path outside the
 * playlist returned early, and a path inside it returned from the loop. Both
 * scans are now one hash lookup.
 */
bool ProjectMBridge::SetPreset(const std::string& preset_path) {
    if (!IsReady() || preset_path.empty()) {
        return false;
    }
    // The cache cannot be trusted here: projectM rotates the preset on its own
    // timer whenever it is unlocked, and nothing tells us when it does, so
    // `current_preset_` is only correct until the first rotation. Re-reading it
    // is what keeps the shortcut below honest -- without this, picking a preset,
    // letting the timer move on, and picking that same preset again matched the
    // stale cache and returned success without switching anything, leaving the
    // browser showing a selection the screen did not agree with.
    current_preset_ = ReadCurrentPreset();

    // Already showing it. Both the tap and the preference observer that follows
    // it ask for the same preset, so this is the common case, not an edge one.
    if (preset_path == current_preset_) {
        return true;
    }
    const auto found = preset_index_.find(preset_path);
    if (found == preset_index_.end()) {
        return false;
    }
    // hard_cut = true: the switch lands on this frame rather than being blended
    // in over the soft-cut duration, which is what makes picking a preset feel
    // immediate. The soft cut still applies to the automatic rotation.
    //
    // No retry: a preset chosen by name that fails to load leaves the old one
    // showing, rather than answering the request with some third preset.
    PlayIndex(found->second, true, false);
    current_preset_ = found->first;
    return true;
}

void ProjectMBridge::RemoveExcludedPresets() {
    if (playlist_ == nullptr || excluded_presets_.empty()) {
        return;
    }
    // Backwards, so removing an item never shifts one still to be checked.
    uint32_t removed = 0;
    for (uint32_t index = projectm_playlist_size(playlist_); index-- > 0;) {
        char* item = projectm_playlist_item(playlist_, index);
        if (item == nullptr) {
            continue;
        }
        const bool excluded = excluded_presets_.count(item) != 0;
        projectm_playlist_free_string(item);
        if (excluded && projectm_playlist_remove_preset(playlist_, index)) {
            removed++;
        }
    }
    __android_log_print(ANDROID_LOG_INFO, kTag, "Left %u flagged presets out of the playlist", removed);
}

/**
 * The next preset for a timed switch or Next: uniformly at random when
 * shuffling, never the one already showing, otherwise the one after it.
 */
uint32_t ProjectMBridge::PickNextIndex() {
    const auto size = static_cast<uint32_t>(preset_paths_.size());
    if (size <= 1) {
        return 0;
    }
    const auto current = projectm_playlist_get_position(playlist_);
    if (!shuffle_) {
        return (current + 1) % size;
    }
    std::uniform_int_distribution<uint32_t> pick(0, size - 2);
    const auto index = pick(rng_);
    return index >= current ? index + 1 : index;
}

void ProjectMBridge::PlayIndex(uint32_t index, bool hard_cut, bool retry_on_failure) {
    if (index >= preset_paths_.size()) {
        return;
    }
    retry_on_failure_ = retry_on_failure;
    hard_cut_requested_ = hard_cut;
    MarkLoading(preset_paths_[index]);
    projectm_playlist_set_position(playlist_, index, hard_cut);
}

/**
 * Records [preset_path] as the preset being loaded. A plain write, no fsync:
 * the file only has to outlive the process, not the device, and a crashed
 * process leaves its page cache behind.
 */
void ProjectMBridge::MarkLoading(const std::string& preset_path) const {
    if (crash_sentinel_path_.empty()) {
        return;
    }
    FILE* file = OpenForWriting(crash_sentinel_path_);
    if (file == nullptr) {
        return;
    }
    std::fprintf(file, "%d\n%s\n", static_cast<int>(TF_GETPID()), preset_path.c_str());
    std::fclose(file);
}

void ProjectMBridge::OnSwitchRequested(bool is_hard_cut, void* user_data) {
    auto* bridge = static_cast<ProjectMBridge*>(user_data);
    if (bridge == nullptr || bridge->preset_paths_.empty()) {
        return;
    }
    bridge->switch_failures_ = 0;
    bridge->PlayIndex(bridge->PickNextIndex(), is_hard_cut, true);
    bridge->current_preset_ = bridge->ReadCurrentPreset();
}

/**
 * A preset projectM refused -- it threw while loading, which it survives. Runs
 * inside the projectm_load_preset_file that failed, so a retry from here
 * nests; kMaxSwitchRetries is what bounds it.
 */
void ProjectMBridge::OnSwitchFailed(const char* preset_filename, const char* message, void* user_data) {
    auto* bridge = static_cast<ProjectMBridge*>(user_data);
    if (bridge == nullptr) {
        return;
    }
    __android_log_print(ANDROID_LOG_WARN, kTag, "Preset failed to load: %s: %s",
                        preset_filename ? preset_filename : "?", message ? message : "?");
    if (!bridge->retry_on_failure_ || bridge->switch_failures_ >= kMaxSwitchRetries) {
        return;
    }
    bridge->switch_failures_++;
    bridge->PlayIndex(bridge->PickNextIndex(), bridge->hard_cut_requested_, true);
}

void ProjectMBridge::BuildPresetIndex() {
    preset_index_.clear();
    preset_paths_.clear();
    if (playlist_ == nullptr) {
        return;
    }
    const auto playlist_size = projectm_playlist_size(playlist_);
    preset_index_.reserve(playlist_size);
    preset_paths_.resize(playlist_size);
    for (uint32_t index = 0; index < playlist_size; ++index) {
        char* item = projectm_playlist_item(playlist_, index);
        if (item == nullptr) {
            continue;
        }
        preset_index_.emplace(item, index);
        preset_paths_[index] = item;
        projectm_playlist_free_string(item);
    }
}

std::string ProjectMBridge::NextPreset() {
    if (!IsReady()) {
        return {};
    }
    switch_failures_ = 0;
    PlayIndex(PickNextIndex(), true, true);
    current_preset_ = ReadCurrentPreset();
    return current_preset_;
}

void ProjectMBridge::SetShuffle(bool enabled) {
    shuffle_ = enabled;
    if (playlist_ != nullptr) {
        projectm_playlist_set_shuffle(playlist_, enabled);
    }
}

void ProjectMBridge::SetBeatSensitivity(int value) {
    if (projectm_ != nullptr) {
        // Exponential curve: 50 % → 1.0 (default), range 0.2 – 5.0
        // Gives finer control in the mid-range where most users operate
        const float normalized = static_cast<float>(std::clamp(value, 0, 100)) / 100.0f;
        const float scaled = 0.2f * std::pow(25.0f, normalized);
        projectm_set_beat_sensitivity(projectm_, scaled);
    }
}

void ProjectMBridge::SetBrightness(int value) {
    brightness_ = std::clamp(value, 0, 100);
}

void ProjectMBridge::SetPaused(bool paused) {
    paused_ = paused;
}

void ProjectMBridge::SetQuality(int mesh_width, int mesh_height) {
    if (projectm_ != nullptr) {
        projectm_set_mesh_size(projectm_, mesh_width, mesh_height);
    }
}

void ProjectMBridge::SetFps(int fps) {
    if (projectm_ != nullptr) {
        projectm_set_fps(projectm_, fps);
    }
}

/**
 * Turn the timed preset change on or off.
 *
 * projectM's own preset lock, not a very long duration standing in for one: it
 * stops the automatic transitions, hard and soft alike, while leaving a preset
 * chosen by hand -- from the browser, or Next -- working exactly as before.
 * A duration large enough to feel like off is still a timer, and would move the
 * preset eventually on a long listen.
 */
void ProjectMBridge::SetPresetRotationEnabled(bool enabled) {
    preset_rotation_enabled_ = enabled;
    if (projectm_ != nullptr) {
        projectm_set_preset_locked(projectm_, !enabled);
    }
}

void ProjectMBridge::SetPresetDuration(int seconds) {
    if (projectm_ == nullptr) {
        return;
    }
    const auto clamped = std::clamp(seconds, 5, 120);
    projectm_set_preset_duration(projectm_, static_cast<double>(clamped));
    projectm_set_soft_cut_duration(projectm_, std::min(3.0, clamped / 4.0));
    projectm_set_hard_cut_enabled(projectm_, true);
    projectm_set_hard_cut_duration(projectm_, std::max(5.0, clamped * 0.75));
    // Re-asserted because the calls above are what rotation being *on* looks
    // like; without this, moving the duration slider while rotation is off
    // would quietly start it again.
    projectm_set_preset_locked(projectm_, !preset_rotation_enabled_);
}

std::string ProjectMBridge::CurrentPreset() const {
    return current_preset_;
}

std::string ProjectMBridge::ReadCurrentPreset() const {
    if (playlist_ == nullptr) {
        return {};
    }
    const auto index = projectm_playlist_get_position(playlist_);
    char* item = projectm_playlist_item(playlist_, index);
    if (item == nullptr) {
        return {};
    }
    std::string result(item);
    projectm_playlist_free_string(item);
    return result;
}

void ProjectMBridge::Touch(float x, float y, int pressure, int touch_type) {
    if (projectm_ != nullptr) {
        projectm_touch(projectm_, x, y, pressure, static_cast<projectm_touch_type>(touch_type));
    }
}

void ProjectMBridge::TouchDrag(float x, float y, int pressure) {
    if (projectm_ != nullptr) {
        projectm_touch_drag(projectm_, x, y, pressure);
    }
}

void ProjectMBridge::TouchDestroy(float x, float y) {
    if (projectm_ != nullptr) {
        projectm_touch_destroy(projectm_, x, y);
    }
}

void ProjectMBridge::TouchDestroyAll() {
    if (projectm_ != nullptr) {
        projectm_touch_destroy_all(projectm_);
    }
}

void ProjectMBridge::PushBufferedAudioToProjectM() {
    if (projectm_ == nullptr) {
        return;
    }

    std::vector<float> samples;
    int channel_count = 2;
    int sample_rate = 44100;
    if (!audio_buffer_.Pop(samples, channel_count, sample_rate) || samples.empty()) {
        return;
    }

    // ── RMS-based auto-gain normalization ──────────────────────────
    // Keeps visualizer reaction consistent across quiet and loud tracks.
    float sum_sq = 0.0f;
    for (const auto& s : samples) {
        sum_sq += s * s;
    }
    const float rms = std::sqrt(sum_sq / static_cast<float>(samples.size()));

    // Adaptive envelope: fast attack (~55 ms) to catch transients,
    // slow release (~550 ms) to preserve musical dynamics.
    if (rms > smoothed_rms_) {
        smoothed_rms_ = smoothed_rms_ * 0.7f + rms * 0.3f;
    } else {
        smoothed_rms_ = smoothed_rms_ * 0.97f + rms * 0.03f;
    }

    // Derive gain that brings the smoothed level to a consistent target.
    constexpr float kTargetRms = 0.18f;
    constexpr float kMinGain = 0.5f;
    constexpr float kMaxGain = 6.0f;
    float gain = 1.0f;
    if (smoothed_rms_ > 0.001f) {
        gain = std::clamp(kTargetRms / smoothed_rms_, kMinGain, kMaxGain);
    }

    // Apply gain with tanh soft-clip so transient peaks drive the
    // visualizer without harsh distortion.
    for (auto& s : samples) {
        s = std::tanh(s * gain);
    }

    (void) sample_rate;
    const unsigned int per_channel_count = static_cast<unsigned int>(samples.size() / std::max(channel_count, 1));
    projectm_pcm_add_float(
            projectm_,
            samples.data(),
            per_channel_count,
            channel_count == 1 ? PROJECTM_MONO : PROJECTM_STEREO
    );
}
