package tf.monochrome.desktop.player.engine

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.desktop.audio.dsp.DownmixProcessor
import tf.monochrome.desktop.audio.dsp.DspEngineManager
import tf.monochrome.desktop.audio.dsp.MixBusProcessor
import tf.monochrome.desktop.audio.eq.AutoEqProcessor
import tf.monochrome.desktop.audio.eq.LoudnessNative
import tf.monochrome.desktop.audio.eq.ParametricEqProcessor
import tf.monochrome.desktop.audio.pipeline.AudioPipelineMonitor
import tf.monochrome.desktop.audio.pipeline.DecodedStream
import tf.monochrome.desktop.audio.resample.FloatSonicAudioProcessor
import tf.monochrome.desktop.audio.resample.VariRateAudioProcessor
import tf.monochrome.desktop.audio.stretch.PitchEngine
import tf.monochrome.desktop.audio.stretch.PitchQuality
import tf.monochrome.desktop.audio.stretch.StretchAudioProcessor
import tf.monochrome.desktop.audio.tempo.BpmTapProcessor
import tf.monochrome.desktop.audio.usb.BypassVolumeController
import tf.monochrome.desktop.data.presence.DiscordPresence
import tf.monochrome.desktop.data.presence.DiscordPresenceManager
import tf.monochrome.desktop.data.preferences.PreferencesManager
import tf.monochrome.desktop.data.repository.LibraryRepository
import tf.monochrome.desktop.data.scrobbling.ScrobblingService
import tf.monochrome.desktop.domain.model.EqBand
import tf.monochrome.desktop.domain.model.PlaybackSource
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.domain.model.buildCoverUrl
import tf.monochrome.desktop.player.GaplessEligibility
import tf.monochrome.desktop.player.PlaybackStateRepository
import tf.monochrome.desktop.player.QueueManager
import tf.monochrome.desktop.player.StreamResolver
import tf.monochrome.desktop.player.UnifiedTrackRegistry
import tf.monochrome.desktop.visualizer.PresetRotationMode
import tf.monochrome.desktop.visualizer.ProjectMEngineRepository

/**
 * Everything `PlaybackService` did around ExoPlayer on Android, around the
 * desktop [PlaybackEngine]: the queue's auto-advance and skips, stream
 * resolution, the preference collectors that drive the processors (speed,
 * pitch, EQ, downmix, gapless, crossfade length), per-track side effects
 * (history, scrobbling, Discord, the visualizer's preset rotation, BPM tap
 * and loudness resets), error recovery with backoff, live-stream reconnects
 * and position persistence.
 *
 * Android-only parts are gone: the media session and notification, audio
 * focus, the widget, Android Auto and the USB framework pinning. Crossfade is
 * not yet wired on the desktop (gapless is); see docs/porting-plan.md.
 */
@OptIn(FlowPreview::class)
@Singleton
class EngineController @Inject constructor(
    val engine: PlaybackEngine,
    private val queueManager: QueueManager,
    private val streamResolver: StreamResolver,
    private val preferences: PreferencesManager,
    private val libraryRepository: LibraryRepository,
    private val scrobblingService: ScrobblingService,
    private val discordPresence: DiscordPresenceManager,
    private val projectMEngineRepository: ProjectMEngineRepository,
    private val downmixProcessor: DownmixProcessor,
    private val mixBusProcessor: MixBusProcessor,
    private val dspManager: DspEngineManager,
    private val bpmTap: BpmTapProcessor,
    private val autoEqProcessor: AutoEqProcessor,
    private val parametricEqProcessor: ParametricEqProcessor,
    private val variRateProcessor: VariRateAudioProcessor,
    private val stretchProcessor: StretchAudioProcessor,
    private val floatSonic: FloatSonicAudioProcessor,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val playbackState: PlaybackStateRepository,
    private val audioPipelineMonitor: AudioPipelineMonitor,
    private val bypassVolumeController: BypassVolumeController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eqJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    @Volatile private var lastPitchRatio = 1f
    @Volatile private var lastSemitones = 0f
    @Volatile private var baseVolume = 1f
    @Volatile private var crossfadeGain = 1f
    @Volatile private var gaplessEnabled = true
    @Volatile private var gaplessNoResample = true
    @Volatile private var crossfadeMs = 0L
    private var consecutivePlayerErrors = 0
    private var liveReconnects = 0
    private var errorRecovery: Job? = null
    private var gaplessJob: Job? = null
    private var lastGaplessAttempt: GaplessAttempt? = null
    private var lastPresetTrackId: String? = null
    private var started = false

    private data class GaplessAttempt(val trackId: Long, val gapless: Boolean, val crossfadeMs: Long, val noResample: Boolean)

    fun start() {
        if (started) return
        started = true
        engine.addListener(listener)
        collectPreferences()
        startPositionPersistWatcher()
        scope.launch {
            queueManager.currentTrack.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { track ->
                if (track == null) discordPresence.clear() else pushDiscordPresence(track)
            }
        }
        scope.launch {
            combine(queueManager.queue, queueManager.repeatMode) { _, _ -> Unit }.collect { syncGaplessNext() }
        }
        scope.launch {
            mixBusProcessor.engineReady.collect { ready ->
                if (ready) {
                    dspManager.restoreState()
                    dspManager.reapplyAfterEngineRecreated()
                }
            }
        }
    }

    // ── Transport (what the UI calls) ────────────────────────────────────────
    fun playQueue() {
        val track = queueManager.currentTrack.value ?: return
        scope.launch {
            try {
                val unified = unifiedTrackRegistry[track.id]
                val item: MediaItem? = if (unified != null) {
                    streamResolver.resolveUnifiedTrack(unified).takeIf { it.isPlayable }?.mediaItem
                } else {
                    streamResolver.resolveMediaItem(track).first
                }
                if (item == null) { onTrackEnded(); return@launch }
                val start = playbackState.consumePendingStart(track.id)
                engine.setMediaItem(item, start.coerceAtLeast(0L))
                engine.prepare()
                engine.play()
                if (!isLiveStream(track)) libraryRepository.addToHistory(track, unified)
                preloadNextTracks()
                syncGaplessNext()
            } catch (e: Exception) {
                Log.w(TAG, "playQueue failed for ${track.id}", e)
                onTrackEnded()
            }
        }
    }

    fun playTrack(track: Track) {
        queueManager.setQueue(listOf(track), 0)
        playQueue()
    }

    fun skipToNext() {
        engine.preloadNext(null)
        if (queueManager.next() != null) playQueue() else engine.stop()
    }

    fun skipToPrevious() {
        if (engine.currentPosition > 3000) { engine.seekTo(0); return }
        engine.preloadNext(null)
        if (queueManager.previous() != null) playQueue()
    }

    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun togglePlayPause() {
        if (engine.isPlaying) engine.pause() else if (engine.currentMediaItem != null) engine.play() else playQueue()
    }

    fun setPlaybackSpeed(speed: Float, preservePitch: Boolean) = pushPlaybackParameters(speed, preservePitch)

    private fun onTrackEnded() {
        if (queueManager.next() != null) playQueue()
    }

    // ── Engine events ────────────────────────────────────────────────────────
    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            playbackState.savePosition(engine.currentPosition, engine.duration, flush = true)
            queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
                playbackState.savePosition(engine.currentPosition, engine.duration, flush = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_ENDED -> {
                    val current = queueManager.currentTrack.value
                    if (current != null && isLiveStream(current)) {
                        if (liveReconnects++ < MAX_LIVE_RECONNECTS) { playQueue() } else { liveReconnects = 0; onTrackEnded() }
                        return
                    }
                    if (current != null) scope.launch { scrobblingService.scrobbleTrack(current) }
                    audioPipelineMonitor.onIdle()
                    onTrackEnded()
                }
                Player.STATE_READY -> {
                    consecutivePlayerErrors = 0
                    liveReconnects = 0
                    queueManager.currentTrack.value?.let { pushDiscordPresence(it) }
                    engine.streamInfo?.let { publishStream(it) }
                    applyVolume(); applyPlaybackSpeed(); applyEq(); applyParametricEq()
                }
                else -> {}
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            engine.preloadNext(null)
            val attempt = consecutivePlayerErrors++
            val live = queueManager.currentTrack.value?.let { isLiveStream(it) } == true
            val budget = if (live) MAX_LIVE_PLAYER_ERRORS else MAX_CONSECUTIVE_PLAYER_ERRORS
            if (attempt >= budget) { consecutivePlayerErrors = 0; onTrackEnded(); return }
            val target = queueManager.currentTrack.value?.id
            errorRecovery?.cancel()
            errorRecovery = scope.launch {
                delay(PLAYER_ERROR_RETRY_DELAY_MS * (attempt + 1))
                if (queueManager.currentTrack.value?.id == target) playQueue()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            bpmTap.newTrack()
            LoudnessNative.reset()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The gapless hand-off: the queue follows the engine.
                queueManager.next()
                applyVolume()
                scope.launch { preloadNextTracks() }
            }
            lastGaplessAttempt = null
            syncGaplessNext()
            if (mediaItem != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                val trackId = mediaItem.mediaId.toLongOrNull()
                val track = trackId?.let { id -> queueManager.currentQueue.find { it.id == id } }
                if (track != null) scope.launch {
                    val unified = unifiedTrackRegistry[track.id]
                    if (!isLiveStream(track)) {
                        libraryRepository.addToHistory(track, unified)
                        scrobblingService.updateNowPlaying(track)
                    }
                }
            }
            val newTrackId = mediaItem?.mediaId
            if (newTrackId != null && newTrackId != lastPresetTrackId) {
                lastPresetTrackId = newTrackId
                if (projectMEngineRepository.rotationMode.value == PresetRotationMode.Track) projectMEngineRepository.nextPreset()
            }
            engine.streamInfo?.let { publishStream(it) }
        }
    }

    private fun publishStream(info: DecodedStreamInfo) {
        audioPipelineMonitor.onDecoderInitialized("ffmpeg:${info.codecName}")
        audioPipelineMonitor.onStreamFormat(
            DecodedStream(
                mimeType = info.mimeType,
                sampleRate = info.sampleRate,
                channelCount = info.channelCount,
                bitrate = info.bitrate,
                pcmBits = if (info.outputIsFloat) 32 else 16,
                pcmIsFloat = info.outputIsFloat,
            ),
        )
    }

    // ── Preferences → processors ─────────────────────────────────────────────
    private fun collectPreferences() {
        scope.launch {
            combine(preferences.playbackSpeed, preferences.preservePitch) { s, p -> s to p }
                .collect { (speed, preservePitch) -> pushPlaybackParameters(speed, preservePitch) }
        }
        scope.launch {
            preferences.pitchSemitones.collect { st -> lastSemitones = st; stretchProcessor.setSemitones(st); pushAutoEqWarp() }
        }
        scope.launch {
            combine(preferences.pitchEngine, preferences.pitchQuality) { e, q -> e to q }
                .collect { (engine, quality) -> stretchProcessor.setEngine(engine, quality); pushAutoEqWarp() }
        }
        scope.launch {
            combine(preferences.multichannelDownmixEnabled, preferences.rendererProfile) { enabled, profile ->
                // Desktop: the sink's channel count is stereo unless a multichannel device is chosen.
                enabled && !profile.speakerLayout(2).isMultichannel
            }.distinctUntilChanged().collect { downmixProcessor.setEnabled(it) }
        }
        scope.launch {
            preferences.rendererProfile.collect { profile ->
                downmixProcessor.setPreampDb(profile.downmixPreampDb)
                downmixProcessor.setLfeLowpass(profile.lfeLowpass)
                downmixProcessor.setHeadphoneRender(profile.binauralStrength, profile.heightVirtualization, profile.bassManagement, profile.crossoverHz)
            }
        }
        scope.launch { preferences.gaplessPlayback.collect { gaplessEnabled = it; syncGaplessNext() } }
        scope.launch { preferences.gaplessNoResample.collect { gaplessNoResample = it; syncGaplessNext() } }
        scope.launch {
            preferences.crossfadeDuration.collect { seconds ->
                crossfadeMs = seconds.coerceAtLeast(0) * 1_000L
                syncGaplessNext()
            }
        }
        scope.launch { preferences.volume.collect { baseVolume = it.toFloat(); pushVolume() } }
        scope.launch {
            combine(
                preferences.eqEnabled, preferences.eqBandsJson, preferences.eqBandsRJson, preferences.eqStereoMode,
                preferences.eqPreamp, preferences.systemToneControls, preferences.systemWideAutoEqEnabled,
            ) { v ->
                EqApply(v[0] as Boolean, v[1] as String?, v[2] as String?, v[3] as Boolean, v[4] as Double,
                    v[5] as tf.monochrome.desktop.domain.model.ToneControls, v[6] as Boolean)
            }.distinctUntilChanged().debounce(EQ_APPLY_DEBOUNCE_MS).collectLatest { applyEqSettings(it) }
        }
        scope.launch {
            combine(preferences.paramEqEnabled, preferences.paramEqBandsJson, preferences.paramEqPreamp) { e, b, p -> Triple(e, b, p) }
                .distinctUntilChanged().debounce(EQ_APPLY_DEBOUNCE_MS)
                .collectLatest { (enabled, bands, preamp) -> applyParametricEqSettings(enabled, bands, preamp) }
        }
    }

    private fun pushPlaybackParameters(speed: Float, preservePitch: Boolean) {
        val pitch = if (preservePitch) 1f else speed
        engine.playbackParameters = PlaybackParameters(speed, pitch)
        // Preserve-pitch speed goes to the time-stretch stage; vinyl-style speed to the resampler.
        if (preservePitch) { floatSonic.setSpeed(speed); variRateProcessor.setRatio(1f) }
        else { floatSonic.setSpeed(1f); variRateProcessor.setRatio(speed) }
        lastPitchRatio = pitch
        pushAutoEqWarp()
    }

    private fun applyPlaybackSpeed() {
        scope.launch { pushPlaybackParameters(preferences.playbackSpeed.first(), preferences.preservePitch.first()) }
    }

    private fun pushAutoEqWarp() {
        val total = lastPitchRatio * 2f.pow(lastSemitones / 12f)
        val sampleRate = engine.audioFormat?.sampleRate ?: 0
        val latencyFrames = stretchProcessor.latencyFrames()
        val latencyMs = if (sampleRate > 0 && latencyFrames > 0) (latencyFrames * 1000L / sampleRate).toInt() else 0
        if (latencyMs > 0) autoEqProcessor.setPitchRatio(total, glideMillis = latencyMs) else autoEqProcessor.setPitchRatio(total)
    }

    private fun pushVolume() {
        val effective = baseVolume * crossfadeGain
        engine.volume = effective
        bypassVolumeController.setVolume(effective)
    }

    private fun applyVolume() {
        scope.launch { baseVolume = preferences.volume.first().toFloat(); pushVolume() }
    }

    private data class EqApply(
        val enabled: Boolean, val bandsJson: String?, val bandsRJson: String?, val stereo: Boolean,
        val preamp: Double, val tone: tf.monochrome.desktop.domain.model.ToneControls, val systemWide: Boolean,
    )

    private fun applyEq() {
        scope.launch {
            runCatching {
                applyEqSettings(
                    EqApply(
                        preferences.eqEnabled.first(), preferences.eqBandsJson.first(), preferences.eqBandsRJson.first(),
                        preferences.eqStereoMode.first(), preferences.eqPreamp.first(), preferences.systemToneControls.first(),
                        preferences.systemWideAutoEqEnabled.first(),
                    ),
                )
            }
        }
    }

    private fun applyEqSettings(cfg: EqApply) {
        try {
            if (cfg.systemWide) {
                // Desktop: there is no system-wide AudioEffect; the in-app EQ stays off as on Android.
                autoEqProcessor.applyBands(emptyList(), 0f, false)
                return
            }
            fun decode(json: String?): List<EqBand> =
                if (cfg.enabled && !json.isNullOrEmpty()) eqJson.decodeFromString(json) else emptyList()
            val autoL = decode(cfg.bandsJson)
            val autoR = if (cfg.stereo) decode(cfg.bandsRJson).ifEmpty { autoL } else autoL
            val toneBands = cfg.tone.toBands()
            val bandsL = autoL + toneBands
            val bandsR = autoR + toneBands
            val preamp = if (cfg.enabled) cfg.preamp else 0.0
            val active = bandsL.any { it.enabled } || bandsR.any { it.enabled }
            autoEqProcessor.applyBands(bandsL, bandsR, preamp.toFloat(), active)
        } catch (e: Exception) {
            Log.w(TAG, "EQ apply failed", e)
        }
    }

    private fun applyParametricEq() {
        scope.launch {
            runCatching {
                applyParametricEqSettings(preferences.paramEqEnabled.first(), preferences.paramEqBandsJson.first(), preferences.paramEqPreamp.first())
            }
        }
    }

    private fun applyParametricEqSettings(enabled: Boolean, bandsJson: String?, preamp: Double) {
        try {
            val bands: List<EqBand> = if (!bandsJson.isNullOrEmpty()) eqJson.decodeFromString(bandsJson) else emptyList()
            parametricEqProcessor.applyBands(bands, preamp.toFloat(), enabled)
        } catch (e: Exception) {
            Log.w(TAG, "parametric EQ apply failed", e)
        }
    }

    // ── Gapless pre-roll ─────────────────────────────────────────────────────
    private fun syncGaplessNext() {
        if (engine.currentMediaItem == null) return
        val next = queueManager.peekNext()
        if (!gaplessEnabled || crossfadeMs > 0L || next == null) { engine.preloadNext(null); return }
        val wantedId = next.id.toString()
        if (engine.hasNextMediaItem() && engine.getMediaItemAt(1).mediaId == wantedId) return
        val attempt = GaplessAttempt(next.id, gaplessEnabled, crossfadeMs, gaplessNoResample)
        if (lastGaplessAttempt == attempt) return
        lastGaplessAttempt = attempt
        engine.preloadNext(null)
        gaplessJob?.cancel()
        gaplessJob = scope.launch {
            if (!streamResolver.warmUpcoming(next)) return@launch
            if (!sampleRatesMatch(next)) return@launch
            val item = resolveGaplessItem(next) ?: return@launch
            if (!gaplessEnabled) return@launch
            if (queueManager.peekNext()?.id != next.id) return@launch
            engine.preloadNext(item)
        }
    }

    private fun sampleRatesMatch(next: Track): Boolean {
        if (!gaplessNoResample) return true
        val current = engine.audioFormat?.sampleRate?.takeIf { it > 0 } ?: return true
        val upcoming = unifiedTrackRegistry[next.id]?.sampleRate?.takeIf { it > 0 } ?: return true
        return current == upcoming
    }

    private suspend fun resolveGaplessItem(track: Track): MediaItem? {
        val unified = unifiedTrackRegistry[track.id]
        val item = if (unified != null) {
            streamResolver.resolveUnifiedTrack(unified, askForOtherService = false).takeIf { it.isPlayable }?.mediaItem
        } else {
            streamResolver.resolveMediaItem(track, askForOtherService = false).first
        } ?: return null
        return item.takeIf { GaplessEligibility.isStableUri(it.localConfiguration?.uri?.toString()) }
    }

    private suspend fun preloadNextTracks() {
        val queue = queueManager.currentQueue
        val currentIdx = queueManager.currentQueueIndex
        for (i in 1..2) queue.getOrNull(currentIdx + i)?.let { streamResolver.warmUpcoming(it) }
    }

    // ── Side channels ────────────────────────────────────────────────────────
    private fun pushDiscordPresence(track: Track) {
        val loaded = engine.currentMediaItem?.mediaId == track.id.toString()
        discordPresence.update(
            DiscordPresence.NowPlaying(
                title = track.title,
                artist = track.artist?.name,
                album = track.album?.title,
                artworkAsset = track.album?.cover?.let { buildCoverUrl(it, 640) },
                genreId = unifiedTrackRegistry[track.id]?.genreId,
                artworkUrl = track.album?.cover?.let { buildCoverUrl(it, 640) },
                positionMs = if (loaded) engine.currentPosition.coerceAtLeast(0L) else 0L,
                durationMs = (engine.duration.takeIf { loaded && it > 0 }) ?: (track.duration * 1000L),
                paused = loaded && !engine.isPlaying,
            ),
        )
    }

    private fun startPositionPersistWatcher() {
        scope.launch {
            while (true) {
                if (!engine.isPlaying) { _isPlaying.first { it }; continue }
                delay(POSITION_PERSIST_INTERVAL_MS)
                if (engine.isPlaying) playbackState.savePosition(engine.currentPosition, engine.duration)
            }
        }
    }

    private fun isLiveStream(track: Track): Boolean =
        unifiedTrackRegistry[track.id]?.source is PlaybackSource.RadioStream

    companion object {
        private const val TAG = "EngineController"
        private const val MAX_CONSECUTIVE_PLAYER_ERRORS = 2
        private const val MAX_LIVE_PLAYER_ERRORS = 8
        private const val MAX_LIVE_RECONNECTS = 5
        private const val PLAYER_ERROR_RETRY_DELAY_MS = 1_200L
        private const val POSITION_PERSIST_INTERVAL_MS = 10_000L
        private const val EQ_APPLY_DEBOUNCE_MS = 60L
    }
}
