package tf.monochrome.desktop.ui.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tf.monochrome.desktop.audio.UsbAudioRouter
import tf.monochrome.desktop.audio.usb.DacInfo
import tf.monochrome.desktop.data.auth.SpotifyAuthManager
import tf.monochrome.desktop.data.local.scanner.MediaStoreSource
import tf.monochrome.desktop.data.local.scanner.ScanRunner
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.player.engine.AudioOutputController
import tf.monochrome.desktop.player.engine.OutputSelection
import tf.monochrome.desktop.util.safTreeUriToFile
import javax.inject.Inject
import tf.monochrome.desktop.R

enum class OnboardingStep {
    // Desktop: no PERMISSIONS step between WELCOME and FOLDERS; a desktop app
    // holds no runtime permissions, so there is nothing to ask for.
    WELCOME, FOLDERS, DOWNLOADS, STREAMING, AUDIO_OUTPUT, TOUR, DONE
}

/** A library root picked in the Folders step. [trackCount] null = still counting. */
data class FolderEntry(
    val path: String,
    val displayName: String,
    val trackCount: Int? = null,
)

/**
 * First-run wizard state. Everything the user picks (folders, download
 * location, bit-perfect, Spotify tokens) is persisted the moment it's
 * chosen, so backing out mid-flow or process death loses nothing; only
 * `onboarding_complete` itself is written when the user leaves the flow.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    // Desktop: the initial scan is started by ScanRunner rather than enqueued
    // with WorkManager, which is what the application context was for.
    private val scanRunner: ScanRunner,
    private val preferences: PreferencesManager,
    private val mediaStoreSource: MediaStoreSource,
    private val spotifyAuthManager: SpotifyAuthManager,
    private val usbAudioRouter: UsbAudioRouter,
    private val audioOutput: AudioOutputController,
) : ViewModel() {

    private val _step = MutableStateFlow(OnboardingStep.WELCOME)
    val step: StateFlow<OnboardingStep> = _step.asStateFlow()

    // Desktop: no media-permission request to track; the step that asked
    // for it is gone (see OnboardingStep).

    private val _folders = MutableStateFlow<List<FolderEntry>>(emptyList())
    val folders: StateFlow<List<FolderEntry>> = _folders.asStateFlow()

    /** Set when a picked folder can't be resolved to a path on this machine. */
    private val _folderError = MutableStateFlow<tf.monochrome.desktop.ui.components.UiText?>(null)
    val folderError: StateFlow<tf.monochrome.desktop.ui.components.UiText?> = _folderError.asStateFlow()

    // Streaming / audio state passed straight through from the owning singletons.
    val spotifyConnected: StateFlow<Boolean> = spotifyAuthManager.isConnected
    val spotifyConnecting: StateFlow<Boolean> = spotifyAuthManager.isConnecting
    val spotifyUserName: StateFlow<String?> = spotifyAuthManager.connectedUserName
    val spotifyError: StateFlow<String?> = spotifyAuthManager.errorMessage
    val usbDevice = usbAudioRouter.usbOutputDevice

    val downloadFolderUri: StateFlow<String?> = preferences.downloadFolderUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Desktop: bit-perfect output is WASAPI exclusive mode, the route by which
     * a stream reaches a DAC at its own rate and depth with the Windows mixer
     * out of the way. Android's switch pinned the player to the USB device
     * instead (`usb_bit_perfect`), which Windows has no counterpart for. The
     * name is kept for the steps that read it.
     */
    val usbBitPerfectEnabled: StateFlow<Boolean> = audioOutput.state
        .map { it.kind == OutputSelection.Kind.WASAPI_EXCLUSIVE }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether exclusive mode can be offered at all: not without the WASAPI library. */
    val bitPerfectAvailable: Boolean =
        OutputSelection.Kind.WASAPI_EXCLUSIVE in audioOutput.availableKinds

    init {
        // Re-running onboarding from Settings: show folders that are already
        // configured instead of pretending the user has none.
        viewModelScope.launch {
            val roots = preferences.userFolderRoots.first()
            if (roots.isNotEmpty() && _folders.value.isEmpty()) {
                _folders.value = roots.map { FolderEntry(it, folderDisplayName(it)) }
                roots.forEach { countTracks(it) }
            }
        }
        // This ViewModel is Activity-scoped, so after the user finishes once its
        // _step stays at DONE. "Restart onboarding" flips onboarding_complete
        // back to false — reset to WELCOME on that transition so the wizard
        // actually reopens at the start instead of the final "all set" step.
        viewModelScope.launch {
            var wasComplete: Boolean? = null
            preferences.onboardingComplete.collect { complete ->
                if (wasComplete == true && !complete) {
                    _step.value = OnboardingStep.WELCOME
                }
                wasComplete = complete
            }
        }
    }

    // ── Step navigation ─────────────────────────────────────────────

    fun next() {
        val current = _step.value
        if (current == OnboardingStep.FOLDERS && _folders.value.isEmpty()) return
        OnboardingStep.entries.getOrNull(current.ordinal + 1)?.let { _step.value = it }
    }

    fun back() {
        OnboardingStep.entries.getOrNull(_step.value.ordinal - 1)?.let { _step.value = it }
    }

    // ── Folders ─────────────────────────────────────────────────────

    fun addFolder(uri: Uri) {
        // Desktop: kept in library form (`C:/Music`, see
        // MediaStoreSource.toLibraryPath), the form the scan, the Folders tab
        // and the exclusions use, so a folder excluded there is matched here.
        val path = safTreeUriToFile(uri)?.let { MediaStoreSource.toLibraryPath(it) }
        if (path == null) {
            _folderError.value =
                tf.monochrome.desktop.ui.components.UiText.Res(R.string.folder_unscannable)
            return
        }
        _folderError.value = null
        if (_folders.value.any { it.path == path }) return
        _folders.update { it + FolderEntry(path, folderDisplayName(path)) }
        viewModelScope.launch {
            preferences.addUserFolderRoot(path)
            countTracks(path)
        }
    }

    fun removeFolder(path: String) {
        _folders.update { list -> list.filterNot { it.path == path } }
        viewModelScope.launch { preferences.removeUserFolderRoot(path) }
    }

    private fun countTracks(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val count = mediaStoreSource.countAudioUnderPath(path)
            _folders.update { list ->
                list.map { if (it.path == path) it.copy(trackCount = count) else it }
            }
        }
    }

    // Desktop: a root stored by an earlier build may be in Windows' own
    // spelling, so the name is whatever follows the last separator of either kind.
    private fun folderDisplayName(path: String) =
        path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifBlank { path }

    // ── Downloads / streaming / audio ───────────────────────────────

    fun setDownloadFolder(uriString: String?) {
        viewModelScope.launch { preferences.setDownloadFolderUri(uriString) }
    }

    fun connectSpotify(activityContext: Context) = spotifyAuthManager.connect(activityContext)

    fun usbDeviceLabel(device: DacInfo): String =
        usbAudioRouter.describe(device)

    /**
     * Desktop: exclusive mode on, or back to shared, on the output device
     * already chosen (the Windows default until one is picked in Settings ›
     * Audio). See [usbBitPerfectEnabled].
     */
    fun setBitPerfect(enabled: Boolean) {
        if (!bitPerfectAvailable) return
        val kind = if (enabled) OutputSelection.Kind.WASAPI_EXCLUSIVE else OutputSelection.Kind.WASAPI_SHARED
        audioOutput.select(kind, audioOutput.state.value.deviceId)
    }

    // ── Exit ────────────────────────────────────────────────────────

    /**
     * Marks onboarding done and kicks off the initial scan. Called from
     * both the Done step and "Skip setup" — a skipping user has no roots
     * configured, which means a whole-device scan, so they still land on
     * a populating library rather than a dead one.
     */
    fun completeOnboarding() {
        viewModelScope.launch {
            scanRunner.start()
            preferences.setOnboardingComplete(true)
        }
    }
}
