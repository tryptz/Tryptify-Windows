package tf.monochrome.desktop.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.desktop.data.local.repository.LocalMediaRepository
import tf.monochrome.desktop.domain.model.UnifiedAlbum
import tf.monochrome.desktop.domain.model.UnifiedArtist
import tf.monochrome.desktop.domain.model.UnifiedTrack
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import tf.monochrome.desktop.di.SavedStateVmFactory
import tf.monochrome.desktop.R
import tf.monochrome.desktop.ui.components.errorText

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocalArtistDetailViewModel @AssistedInject constructor(
    @Assisted savedStateHandle: SavedStateHandle,
    private val localMediaRepository: LocalMediaRepository
) : ViewModel() {

    // Desktop: Hilt handed the navigation arguments in by itself; plain Dagger builds
    // this through an assisted factory that the ViewModel module binds.
    @AssistedFactory
    interface Factory : SavedStateVmFactory<LocalArtistDetailViewModel>

    private val artistId: Long = savedStateHandle.get<Long>("artistId") ?: 0L

    private val _artist = MutableStateFlow<UnifiedArtist?>(null)
    val artist: StateFlow<UnifiedArtist?> = _artist.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<tf.monochrome.desktop.ui.components.UiText?>(null)
    val error: StateFlow<tf.monochrome.desktop.ui.components.UiText?> = _error.asStateFlow()

    val albums: StateFlow<List<UnifiedAlbum>> = _artist
        .flatMapLatest { a ->
            if (a == null) flowOf(emptyList())
            else localMediaRepository.getAlbumsByArtist(a.name)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val tracks: StateFlow<List<UnifiedTrack>> =
        localMediaRepository.getTracksByArtist(artistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadArtist()
    }

    fun retry() = loadArtist()

    private fun loadArtist() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                if (artistId <= 0L) {
                    _error.value = tf.monochrome.desktop.ui.components.UiText.Res(R.string.error_artist_not_found)
                } else {
                    val result = localMediaRepository.getArtistById(artistId)
                    if (result == null) {
                        _error.value = tf.monochrome.desktop.ui.components.UiText.Res(R.string.error_artist_not_found)
                    } else {
                        _artist.value = result
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = errorText(e, R.string.error_load_artist)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
