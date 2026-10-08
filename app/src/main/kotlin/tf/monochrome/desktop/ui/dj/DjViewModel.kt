package tf.monochrome.desktop.ui.dj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.desktop.data.local.repository.LocalMediaRepository
import tf.monochrome.desktop.data.repository.LibraryRepository
import tf.monochrome.desktop.data.repository.MusicRepository
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
    private val musicRepository: MusicRepository,
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

    /**
     * What the browser lists. The first four are the listener's own; the
     * streaming ones search that service's catalogue for the query, and a
     * track from one loads from that service, as it would play in the player.
     */
    enum class Crate(val service: String? = null) {
        QUEUE, LOCAL, LIKED, HISTORY,
        TIDAL("TIDAL"), QOBUZ("Qobuz"), DEEZER("Deezer");

        val isStreaming: Boolean get() = service != null
    }

    /** Where a streaming crate's search is, for what the empty list says. */
    enum class SearchStatus { IDLE, PROMPT, SEARCHING, FAILED }

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

    private val _searchStatus = MutableStateFlow(SearchStatus.IDLE)
    val searchStatus: StateFlow<SearchStatus> = _searchStatus.asStateFlow()

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
        Crate.TIDAL, Crate.QOBUZ, Crate.DEEZER -> serviceTracks(crate, query)
        Crate.QUEUE -> queueManager.queue.map { it.matching(query) }.also { _searchStatus.value = SearchStatus.IDLE }
        Crate.LIKED -> libraryRepository.getFavoriteTracks().map { it.matching(query) }.also { _searchStatus.value = SearchStatus.IDLE }
        Crate.HISTORY -> libraryRepository.getHistory().map { it.matching(query) }.also { _searchStatus.value = SearchStatus.IDLE }
        Crate.LOCAL -> (if (query.isEmpty()) localMedia.getAllTracks() else localMedia.searchTracks(query)).map { list ->
            // A local file plays only once the registry knows it: the deck,
            // like the player, resolves a track's audio through it.
            list.map { ut -> ut.toLegacyTrack().also { registry.put(it.id, ut) } }
        }.also { _searchStatus.value = SearchStatus.IDLE }
    }

    /**
     * [crate]'s catalogue searched for [query]. The search already registers
     * each Qobuz and Deezer id it returns (QobuzIdRegistry), which is what
     * sends a deck's load to the right service. The old list stays up while a
     * search runs, so typing does not flash the crate empty.
     */
    private fun serviceTracks(crate: Crate, query: String): Flow<List<Track>> = flow {
        if (query.isEmpty()) {
            _searchStatus.value = SearchStatus.PROMPT
            emit(emptyList())
            return@flow
        }
        _searchStatus.value = SearchStatus.SEARCHING
        // On top of the query's own debounce: each keystroke here is a request
        // to a server, and flatMapLatest cancels this wait for the next one.
        delay(SERVICE_DEBOUNCE_MS)
        val found = runCatching { search(crate, query) }.getOrNull()
        _searchStatus.value = if (found == null) SearchStatus.FAILED else SearchStatus.IDLE
        emit(found.orEmpty())
    }

    /** Null when the service could not be reached; a list, maybe empty, when it answered. */
    private suspend fun search(crate: Crate, query: String): List<Track>? = when (crate) {
        Crate.TIDAL -> musicRepository.searchTracks(query, limit = SERVICE_RESULTS).getOrThrow()
        // Qobuz and Deezer answer a few tracks a page: ask for several at once.
        Crate.QOBUZ -> pages { offset -> musicRepository.searchQobuz(query, offset).getOrThrow().tracks }
        Crate.DEEZER -> pages { offset -> musicRepository.searchDeezer(query, offset).getOrThrow().tracks }
        else -> emptyList()
    }

    /**
     * [SERVICE_PAGES] pages from [page], in order and without repeats. The first
     * page failing fails the search; a later one only shortens it.
     */
    private suspend fun pages(page: suspend (offset: Int) -> List<Track>): List<Track> = coroutineScope {
        val first = page(0)
        if (first.isEmpty()) return@coroutineScope first
        val rest = (1 until SERVICE_PAGES).map { i ->
            async { runCatching { page(i * first.size) }.getOrDefault(emptyList()) }
        }.awaitAll()
        (first + rest.flatten()).distinctBy { it.id }
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
        /** Added to [SEARCH_DEBOUNCE_MS] before a streaming crate asks its server. */
        const val SERVICE_DEBOUNCE_MS = 250L
        /** TIDAL's page, in tracks. */
        const val SERVICE_RESULTS = 50
        /** Qobuz and Deezer pages fetched together for one crate. */
        const val SERVICE_PAGES = 3
    }
}
