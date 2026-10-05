package tf.monochrome.desktop.dj

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tf.monochrome.desktop.audio.dsp.DspEngineManager
import tf.monochrome.desktop.audio.dsp.MixBusProcessor
import tf.monochrome.desktop.audio.dsp.SnapinType
import tf.monochrome.desktop.audio.dsp.model.BusConfig
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.player.engine.EngineController
import tf.monochrome.desktop.player.engine.PlaybackEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The DJ console: two [Deck]s, a crossfader, and two FX units, played
 * through the app's own output and mixer.
 *
 * While it runs ([start]), deck A is the player's input (the queue's track
 * pauses where it was) and deck B is the mixer's side input. The mixer's
 * first four buses are the console: Mix A → FX A and Mix B → FX B, both FX
 * buses into the master ([BusConfig.consoleRouting]). So each deck has its
 * own effects chain, and everything after (the master's EQ, the visualizer,
 * the output device) hears the mix exactly as it hears a track.
 *
 * Each deck runs its own channel strip (trim, EQ, filter, fader) and the
 * crossfader before the buses, so the bus faders stay a second, slower level
 * the mixer screen shows.
 */
@Singleton
class DjEngine @Inject constructor(
    private val controller: EngineController,
    private val dsp: DspEngineManager,
    private val mixBus: MixBusProcessor,
) {
    val decks: Array<Deck> = arrayOf(Deck(0), Deck(1))

    /** Crossfader, -1 (all A) .. 1 (all B). */
    @Volatile var crossfader: Float = 0f
    @Volatile var crossfaderCurve: DjMath.CrossfaderCurve = DjMath.CrossfaderCurve.SMOOTH

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _running = MutableStateFlow(false)
    /** The console has the output. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    init {
        decks[0].peer = decks[1]
        decks[1].peer = decks[0]
        for (d in decks) d.onUserTempo = ::onUserTempo
        scope.launch {
            // The player takes the output back (play pressed on a track, a
            // device change it could not follow): the decks stop with it.
            controller.liveInput.collect { live -> if (!live && _running.value) onStopped() }
        }
        scope.launch {
            // Snapshots for the screen, only while something watches them.
            _state.subscriptionCount.collectLatest { n ->
                while (n > 0) {
                    publish()
                    delay(SNAPSHOT_MS)
                }
            }
        }
    }

    // ── Output ─────────────────────────────────────────────────────────

    @Volatile private var outRate = 0
    private val gainsA = FloatArray(2)
    private val gainsB = FloatArray(2)
    private var bufAL = FloatArray(0)
    private var bufAR = FloatArray(0)

    /** Deck A, interleaved, for the player's input. Render thread. */
    private val deckAInput = PlaybackEngine.LiveInput { dst, frames ->
        if (bufAL.size < frames) {
            bufAL = FloatArray(frames)
            bufAR = FloatArray(frames)
        }
        DjMath.crossfaderGains(crossfader, crossfaderCurve, gainsA)
        decks[0].render(bufAL, bufAR, frames, outRate, gainsA[0])
        for (i in 0 until frames) {
            dst[2 * i] = bufAL[i]
            dst[2 * i + 1] = bufAR[i]
        }
    }

    /** Deck B, for the mixer's side input. Render thread, in step with deck A. */
    private val deckBInput = MixBusProcessor.SideInput { l, r, frames ->
        DjMath.crossfaderGains(crossfader, crossfaderCurve, gainsB)
        decks[1].render(l, r, frames, outRate, gainsB[1])
    }

    /**
     * Takes the output: the queue's track pauses, the decks play. Sets the
     * mixer up as the console the first time (only when no bus hears deck B
     * yet, so a layout the user changed stays theirs) and turns the mixer on,
     * since deck B is heard only through it.
     */
    fun start() {
        if (_running.value) return
        val rate = controller.engine.preferredLiveRate
        outRate = rate
        if (!BusConfig.hearsDeckB(dsp.buses.value)) dsp.applyConsoleLayout()
        if (!dsp.enabled.value) dsp.setEnabled(true)
        ensureFxUnits()
        for (d in decks) d.attach()
        mixBus.sideInput = deckBInput
        _running.value = true
        controller.startLiveInput(deckAInput, rate)
    }

    /** Hands the output back to the player. The decks stop where they are. */
    fun stop() {
        controller.stopLiveInput()
        onStopped()
    }

    private fun onStopped() {
        if (!_running.value) return
        _running.value = false
        if (mixBus.sideInput === deckBInput) mixBus.sideInput = null
        for (d in decks) d.detach()
    }

    // ── Loading ────────────────────────────────────────────────────────

    /** Per deck: what is loading or why it failed. */
    data class LoadState(val loading: Boolean = false, val title: String = "", val error: String? = null)

    private val _load = MutableStateFlow(List(2) { LoadState() })
    val load: StateFlow<List<LoadState>> = _load.asStateFlow()
    private val loadJobs = arrayOfNulls<Job>(2)

    /**
     * Loads [track] on deck [deck]: resolves it as the player would, then
     * decodes all of it into memory. The deck can play as soon as the first
     * second is in. Refused (false) while that deck is playing, as on any
     * console: a mistake must not cut the record that is on air.
     */
    fun load(deck: Int, track: Track): Boolean {
        val d = decks.getOrNull(deck) ?: return false
        if (d.playing) return false
        loadJobs[deck]?.cancel()
        setLoad(deck, LoadState(loading = true, title = track.title))
        loadJobs[deck] = scope.launch(Dispatchers.IO) {
            val job = coroutineContext[Job]
            try {
                val item = controller.resolvePlayable(track)
                if (item == null) {
                    setLoad(deck, LoadState(title = track.title, error = "Not playable"))
                    return@launch
                }
                controller.engine.sourceOpener.open(item, true).use { decoder ->
                    val rate = decoder.outputFormat.sampleRate
                    val expected = if (decoder.info.durationUs > 0) decoder.info.durationUs * rate / 1_000_000L else 0L
                    val t = DeckTrack(track, track.title, track.displayArtist, rate, expected)
                    if (job?.isActive == false) return@launch
                    d.load(t)
                    setLoad(deck, LoadState(loading = true, title = track.title))
                    t.decode(decoder, BeatAnalyzer(rate), cancelled = { job?.isActive == false })
                    setLoad(deck, LoadState(title = track.title, error = t.error))
                }
            } catch (e: Exception) {
                if (job?.isActive != false) setLoad(deck, LoadState(title = track.title, error = e.message ?: e.javaClass.simpleName))
            }
        }
        return true
    }

    private fun setLoad(deck: Int, state: LoadState) = _load.update { it.toMutableList().also { l -> l[deck] = state } }

    // ── Browser: the list a controller's browse knob scrolls ───────────

    data class Browser(val title: String = "", val tracks: List<Track> = emptyList(), val selected: Int = 0)

    private val _browser = MutableStateFlow(Browser())
    val browser: StateFlow<Browser> = _browser.asStateFlow()

    fun setBrowser(title: String, tracks: List<Track>) =
        _browser.update { b -> Browser(title, tracks, if (b.tracks == tracks) b.selected.coerceIn(0, (tracks.size - 1).coerceAtLeast(0)) else 0) }

    fun select(index: Int) = _browser.update { it.copy(selected = index.coerceIn(0, (it.tracks.size - 1).coerceAtLeast(0))) }

    fun browse(steps: Int) = _browser.update { it.copy(selected = (it.selected + steps).coerceIn(0, (it.tracks.size - 1).coerceAtLeast(0))) }

    fun loadSelected(deck: Int): Boolean {
        val b = _browser.value
        val t = b.tracks.getOrNull(b.selected) ?: return false
        return load(deck, t)
    }

    // ── Sync ───────────────────────────────────────────────────────────

    /** Sync lock on deck [deck]. With both locked, the one locked first leads until the other's tempo is moved. */
    fun setSyncLock(deck: Int, on: Boolean) {
        val d = decks[deck]
        val p = decks[1 - deck]
        d.syncLock = on
        if (on) {
            d.isLeader = false
            if (p.syncLock) p.isLeader = true
            d.sync()
        } else {
            d.isLeader = false
            if (p.syncLock) p.isLeader = true
        }
    }

    private fun onUserTempo(d: Deck) {
        val p = decks[1 - d.index]
        // Moving a locked deck's tempo makes it the leader; a follower would snap straight back.
        if (d.syncLock && p.syncLock) {
            d.isLeader = true
            p.isLeader = false
        }
    }

    // ── FX units ───────────────────────────────────────────────────────

    /**
     * An FX unit in group mode, as a Traktor controller sees it: three
     * effects (the first three on its FX bus), each with an on button and an
     * amount knob, and one dry/wet knob over all of them.
     */
    data class FxUnit(
        val mix: Float = 1f,
        val amounts: List<Float> = List(FX_SLOTS) { DEFAULT_FX_AMOUNT },
    )

    private val _fx = MutableStateFlow(List(2) { FxUnit() })
    val fx: StateFlow<List<FxUnit>> = _fx.asStateFlow()

    /** The FX bus of unit [unit] (0 is deck A's). */
    fun fxBus(unit: Int): Int = if (unit == 0) BusConfig.FX_A else BusConfig.FX_B

    /** Effect [slot] of unit [unit]: on/off. */
    fun toggleFx(unit: Int, slot: Int) {
        val p = plugin(unit, slot) ?: return
        dsp.setPluginBypassed(fxBus(unit), slot, !p.bypassed)
    }

    fun fxOn(unit: Int, slot: Int): Boolean = plugin(unit, slot)?.let { !it.bypassed } ?: false

    fun fxName(unit: Int, slot: Int): String? = plugin(unit, slot)?.displayName

    fun setFxAmount(unit: Int, slot: Int, value: Float) {
        _fx.update { l -> l.toMutableList().also { it[unit] = it[unit].copy(amounts = it[unit].amounts.toMutableList().also { a -> a[slot] = value.coerceIn(0f, 1f) }) } }
        applyFx(unit, slot)
    }

    fun setFxMix(unit: Int, value: Float) {
        _fx.update { l -> l.toMutableList().also { it[unit] = it[unit].copy(mix = value.coerceIn(0f, 1f)) } }
        for (s in 0 until FX_SLOTS) applyFx(unit, s)
    }

    private fun applyFx(unit: Int, slot: Int) {
        if (plugin(unit, slot) == null) return
        val u = _fx.value[unit]
        dsp.setPluginDryWet(fxBus(unit), slot, u.mix * u.amounts[slot])
    }

    private fun plugin(unit: Int, slot: Int) =
        dsp.buses.value.firstOrNull { it.index == fxBus(unit) }?.plugins?.getOrNull(slot)

    /** An empty FX bus gets a delay, a reverb and a flanger, all off: a controller's FX buttons work from the first press. */
    private fun ensureFxUnits() {
        for (unit in 0..1) {
            val bus = fxBus(unit)
            val plugins = dsp.buses.value.firstOrNull { it.index == bus }?.plugins ?: continue
            if (plugins.isNotEmpty()) continue
            DEFAULT_FX.forEachIndexed { slot, type ->
                val at = dsp.addPlugin(bus, slot, type)
                if (at >= 0) {
                    dsp.setPluginBypassed(bus, at, true)
                    applyFx(unit, at)
                }
            }
        }
    }

    // ── Snapshots for the screen ───────────────────────────────────────

    data class DeckState(
        val index: Int,
        val loaded: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val playing: Boolean = false,
        val positionSeconds: Double = 0.0,
        val lengthSeconds: Double = 0.0,
        /** Decoded so far, 0..1 of the stated length. */
        val decoded: Float = 0f,
        val complete: Boolean = false,
        val truncated: Boolean = false,
        val trackBpm: Double = 0.0,
        val bpm: Double = 0.0,
        val speed: Double = 1.0,
        val tempoRange: Float = DjMath.DEFAULT_TEMPO_RANGE,
        val keylock: Boolean = false,
        val quantize: Boolean = true,
        val syncLock: Boolean = false,
        val leader: Boolean = false,
        val cueSeconds: Double = 0.0,
        /** Seconds, null where unset. */
        val hotCues: List<Double?> = List(Deck.HOT_CUES) { null },
        val loopActive: Boolean = false,
        val loopInSeconds: Double? = null,
        val loopOutSeconds: Double? = null,
        val loopBeats: Double = DjMath.DEFAULT_LOOP_BEATS,
        val jumpBeats: Double = DjMath.DEFAULT_JUMP_BEATS,
        /** Where the playhead is within its beat (0..1), or null without a grid. */
        val beatPhase: Double? = null,
        val peak: Float = 0f,
        val volume: Float = 1f,
        val trim: Float = 0.5f,
        val eqLow: Float = 0.5f,
        val eqMid: Float = 0.5f,
        val eqHigh: Float = 0.5f,
        val filter: Float = 0f,
    )

    private val _state = MutableStateFlow(List(2) { DeckState(it) })
    /** Both decks, refreshed about 30 times a second while collected. */
    val state: StateFlow<List<DeckState>> = _state.asStateFlow()

    /** Refreshes [state] now (the screen after a press, a controller's LEDs). */
    fun publish() {
        _state.value = decks.map { snapshot(it) }
    }

    private fun snapshot(d: Deck): DeckState {
        val t = d.track ?: return DeckState(
            d.index, volume = d.volume, trim = d.trim, eqLow = d.eqLow, eqMid = d.eqMid,
            eqHigh = d.eqHigh, filter = d.filter, keylock = d.keylock, quantize = d.quantize,
            syncLock = d.syncLock, tempoRange = d.tempoRange, speed = d.speed,
        )
        val sr = t.sampleRate.toDouble()
        val g = t.grid
        val pos = d.playPosition
        fun sec(f: Double): Double? = if (f.isNaN()) null else f / sr
        return DeckState(
            index = d.index,
            loaded = true,
            title = t.title,
            artist = t.artist,
            playing = d.playing,
            positionSeconds = pos / sr,
            lengthSeconds = t.lengthFrames / sr,
            decoded = if (t.complete) 1f else if (t.lengthFrames > 0) (t.frames.toFloat() / t.lengthFrames).coerceIn(0f, 1f) else 0f,
            complete = t.complete,
            truncated = t.truncated,
            trackBpm = g?.bpm ?: 0.0,
            bpm = d.bpm,
            speed = d.speed,
            tempoRange = d.tempoRange,
            keylock = d.keylock,
            quantize = d.quantize,
            syncLock = d.syncLock,
            leader = d.isLeader,
            cueSeconds = d.cuePoint / sr,
            hotCues = d.hotCues.map { sec(it) },
            loopActive = d.loopActive,
            loopInSeconds = sec(d.loopIn),
            loopOutSeconds = sec(d.loopOut),
            loopBeats = d.loopBeats,
            jumpBeats = d.jumpBeats,
            beatPhase = g?.let { DjMath.beatPhase(pos, it.firstBeat, it.beatFrames(t.sampleRate)) },
            peak = d.peak,
            volume = d.volume,
            trim = d.trim,
            eqLow = d.eqLow,
            eqMid = d.eqMid,
            eqHigh = d.eqHigh,
            filter = d.filter,
        )
    }

    companion object {
        const val FX_SLOTS = 3
        const val DEFAULT_FX_AMOUNT = 0.5f
        val DEFAULT_FX = listOf(SnapinType.DELAY, SnapinType.REVERB, SnapinType.FLANGER)
        private const val SNAPSHOT_MS = 33L
    }
}
