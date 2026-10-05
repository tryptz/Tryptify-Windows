package tf.monochrome.desktop.ui.dj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.desktop.data.local.repository.LocalMediaRepository
import tf.monochrome.desktop.data.repository.LibraryRepository
import tf.monochrome.desktop.dj.DjEngine
import tf.monochrome.desktop.dj.controller.ControllerManager
import tf.monochrome.desktop.dj.controller.MidiBinding
import tf.monochrome.desktop.dj.controller.MidiControllers
import tf.monochrome.desktop.dj.controller.MidiProfile
import tf.monochrome.desktop.dj.controller.MidiTarget
import tf.monochrome.desktop.dj.controller.MixxxImport
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.player.QueueManager
import tf.monochrome.desktop.player.UnifiedTrackRegistry

/**
 * The DJ screen: the console ([DjEngine]), the crates its browser shows, and
 * the controllers driving it ([ControllerManager]).
 *
 * The browser is the engine's, not this screen's: a controller's browse knob
 * scrolls the same list and its LOAD buttons load from it. So the screen only
 * chooses what is in it, a crate and a search, and the list stays put when the
 * screen is closed.
 */
@HiltViewModel
class DjViewModel @Inject constructor(
    private val dj: DjEngine,
    private val controllers: ControllerManager,
    private val queueManager: QueueManager,
    private val libraryRepository: LibraryRepository,
    private val localMedia: LocalMediaRepository,
    private val registry: UnifiedTrackRegistry,
) : ViewModel() {

    // ── The console ───────────────────────────────────────────────────

    val running: StateFlow<Boolean> = dj.running
    val decks: StateFlow<List<DjEngine.DeckState>> = dj.state
    val load: StateFlow<List<DjEngine.LoadState>> = dj.load
    val fx: StateFlow<List<DjEngine.FxUnit>> = dj.fx

    private val _crossfader = MutableStateFlow(dj.crossfader)
    val crossfader: StateFlow<Float> = _crossfader.asStateFlow()

    fun toggleRunning() = if (dj.running.value) dj.stop() else dj.start()

    fun setCrossfader(value: Float) {
        dj.crossfader = value
        _crossfader.value = value
    }

    fun togglePlay(deck: Int) = act(deck) { togglePlay() }
    fun cueDown(deck: Int) = act(deck) { cueDown() }
    fun cueUp(deck: Int) = act(deck) { cueUp() }
    fun sync(deck: Int) = act(deck) { sync() }
    fun toggleSyncLock(deck: Int) = dj.setSyncLock(deck, !dj.decks[deck].syncLock).also { dj.publish() }
    fun toggleKeylock(deck: Int) = act(deck) { keylock = !keylock }
    fun toggleQuantize(deck: Int) = act(deck) { quantize = !quantize }
    fun hotCue(deck: Int, slot: Int) = act(deck) { hotCue(slot) }
    fun clearHotCue(deck: Int, slot: Int) = act(deck) { clearHotCue(slot) }
    fun toggleLoop(deck: Int) = act(deck) { toggleAutoLoop() }
    fun resizeLoop(deck: Int, steps: Int) = act(deck) { resizeLoop(steps) }
    fun loopIn(deck: Int) = act(deck) { loopInHere() }
    fun loopOut(deck: Int) = act(deck) { loopOutHere() }
    fun reloop(deck: Int) = act(deck) { reloop() }
    fun beatJump(deck: Int, forward: Boolean) = act(deck) { beatJump(if (forward) jumpBeats else -jumpBeats) }
    fun resizeJump(deck: Int, steps: Int) = act(deck) { resizeJump(steps) }
    fun seek(deck: Int, fraction: Float) = act(deck) { seekFraction(fraction) }
    fun eject(deck: Int) = act(deck) { eject() }

    fun setTempo(deck: Int, fader: Float) = act(deck) { setTempoFader(fader) }
    fun setTempoRange(deck: Int, range: Float) = act(deck) { tempoRange = range }
    fun setVolume(deck: Int, value: Float) = act(deck) { volume = value }
    fun setTrim(deck: Int, value: Float) = act(deck) { trim = value }
    fun setEqHigh(deck: Int, value: Float) = act(deck) { eqHigh = value }
    fun setEqMid(deck: Int, value: Float) = act(deck) { eqMid = value }
    fun setEqLow(deck: Int, value: Float) = act(deck) { eqLow = value }
    fun setFilter(deck: Int, value: Float) = act(deck) { filter = value }

    /** Runs [what] on [deck], and shows it at once rather than on the next snapshot. */
    private inline fun act(deck: Int, what: tf.monochrome.desktop.dj.Deck.() -> Unit) {
        dj.decks.getOrNull(deck)?.what()
        dj.publish()
    }

    val fxSlots: StateFlow<List<List<DjEngine.FxSlot?>>> =
        dj.fxSlots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), List(2) { List(DjEngine.FX_SLOTS) { null } })

    fun toggleFx(unit: Int, slot: Int) = dj.toggleFx(unit, slot)
    fun toggleFxAssign(unit: Int) = dj.toggleFxAssign(unit)
    fun setFxMix(unit: Int, value: Float) = dj.setFxMix(unit, value)
    fun setFxAmount(unit: Int, slot: Int, value: Float) = dj.setFxAmount(unit, slot, value)

    // ── The browser ───────────────────────────────────────────────────

    enum class Crate { QUEUE, LOCAL, LIKED, HISTORY }

    val browser: StateFlow<DjEngine.Browser> = dj.browser

    private val _crate = MutableStateFlow(Crate.QUEUE)
    val crate: StateFlow<Crate> = _crate.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setCrate(crate: Crate) {
        _crate.value = crate
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun select(index: Int) = dj.select(index)

    /** False while [deck] is playing: a playing deck is never replaced. */
    fun loadTo(deck: Int, track: Track): Boolean = dj.load(deck, track)

    init {
        viewModelScope.launch {
            @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
            combine(_crate, _query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }) { c, q -> c to q.trim() }
                .flatMapLatest { (c, q) -> tracks(c, q).map { c to it } }
                .flowOn(Dispatchers.Default)
                .collect { (c, tracks) -> dj.setBrowser(c.name, tracks) }
        }
    }

    private fun tracks(crate: Crate, query: String): Flow<List<Track>> = when (crate) {
        Crate.QUEUE -> queueManager.queue.map { it.matching(query) }
        Crate.LIKED -> libraryRepository.getFavoriteTracks().map { it.matching(query) }
        Crate.HISTORY -> libraryRepository.getHistory().map { it.matching(query) }
        Crate.LOCAL -> (if (query.isEmpty()) localMedia.getAllTracks() else localMedia.searchTracks(query)).map { list ->
            // A local file plays only once the registry knows it: the deck,
            // like the player, resolves a track's audio through it.
            list.map { ut -> ut.toLegacyTrack().also { registry.put(it.id, ut) } }
        }
    }

    private fun List<Track>.matching(query: String): List<Track> =
        if (query.isEmpty()) this
        else filter { it.title.contains(query, ignoreCase = true) || it.displayArtist.contains(query, ignoreCase = true) }

    // ── Controllers ───────────────────────────────────────────────────

    val controllerList: StateFlow<List<ControllerManager.Status>> = controllers.controllers
    val profiles: StateFlow<Map<String, MidiProfile>> = controllers.midi.profiles
    val learning: StateFlow<MidiControllers.Learning?> = controllers.midi.learning
    val logFile: StateFlow<File?> = controllers.logFile

    private val _invertTempo = MutableStateFlow(controllers.invertTempo)
    val invertTempo: StateFlow<Boolean> = _invertTempo.asStateFlow()

    private val _logReports = MutableStateFlow(controllers.logReports)
    val logReports: StateFlow<Boolean> = _logReports.asStateFlow()

    fun setInvertTempo(on: Boolean) {
        controllers.invertTempo = on
        _invertTempo.value = on
    }

    fun setLogReports(on: Boolean) {
        controllers.logReports = on
        _logReports.value = on
    }

    fun learn(port: String, target: MidiTarget) = controllers.midi.learn(port, target)
    fun cancelLearn() = controllers.midi.cancelLearn()
    fun forget(port: String, binding: MidiBinding) = controllers.midi.forget(port, binding)
    fun resetMapping(port: String) = controllers.midi.resetMapping(port)

    /** A Mixxx mapping for [port]; throws [MixxxImport.FormatException] for a file that is not one. */
    fun importMixxx(port: String, input: InputStream): MixxxImport.Result = controllers.midi.importMixxx(port, input)

    override fun onCleared() {
        // Leaving the screen mid-learn would leave the next control moved bound.
        controllers.midi.cancelLearn()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 200L
    }
}
