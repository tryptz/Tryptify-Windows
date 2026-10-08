package tf.monochrome.desktop.ui.detail

import tf.monochrome.desktop.ui.navigation.trackArtistAction
import tf.monochrome.desktop.ui.navigation.popBackStackSafe
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.desktop.ui.components.AddToPlaylistSheet
import tf.monochrome.desktop.ui.components.CoverImage
import tf.monochrome.desktop.ui.components.CreatePlaylistDialog
import tf.monochrome.desktop.ui.components.ErrorScreen
import tf.monochrome.desktop.ui.components.LoadingScreen
import tf.monochrome.desktop.ui.components.TrackContextMenu
import tf.monochrome.desktop.ui.components.TrackItem
import tf.monochrome.desktop.ui.components.TrackListToolbar
import tf.monochrome.desktop.ui.components.TrackOrder
import tf.monochrome.desktop.ui.components.TrackSort
import tf.monochrome.desktop.ui.components.TrackSortSaver
import tf.monochrome.desktop.ui.components.applySearchAndSort
import tf.monochrome.desktop.ui.navigation.Screen
import tf.monochrome.desktop.ui.navigation.openCatalogArtist
import tf.monochrome.desktop.ui.player.PlayerViewModel
import tf.monochrome.desktop.ui.navigation.navigateSafe
import tf.monochrome.desktop.ui.navigation.LocalBottomChromeInset
import tf.monochrome.desktop.ui.components.SearchOverlay
import tf.monochrome.desktop.ui.components.SearchAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.desktop.R
import tf.monochrome.desktop.ui.input.ListScrollbar
import tf.monochrome.desktop.ui.library.rememberSelectionClicks
import tf.monochrome.desktop.ui.library.selectionKeys
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val albumDetail by viewModel.albumDetail.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val downloadedTrackIds by playerViewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()

    var showContextMenuForTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<tf.monochrome.desktop.domain.model.Track?>(null) }
    var showAddToPlaylistForTrack by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<tf.monochrome.desktop.domain.model.Track?>(null) }
    var showCreatePlaylistDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var showAddToPlaylistForSelection by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val selection = tf.monochrome.desktop.ui.components.rememberTrackSelectionState<Long>()
    androidx.activity.compose.BackHandler(enabled = selection.active) { selection.clear() }
    val clicks = rememberSelectionClicks(selection)

    // How this album is being looked at right now — not anything about the
    // album, so it lives here rather than in the ViewModel.
    var listQuery by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
    var searchOpen by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var listSort by androidx.compose.runtime.saveable.rememberSaveable(stateSaver = TrackSortSaver) {
        androidx.compose.runtime.mutableStateOf(TrackSort())
    }
    // Registered after the selection's, so Escape closes the search first.
    androidx.activity.compose.BackHandler(enabled = searchOpen) { searchOpen = false; listQuery = "" }

    showContextMenuForTrack?.let { track ->
        TrackContextMenu(
            track = track,
            isLiked = favoriteTrackIds.contains(track.id),
            onDismiss = { showContextMenuForTrack = null },
            onPlayNext = { playerViewModel.playNext(track) },
            onAddToQueue = { playerViewModel.addToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavorite(track) },
            onAddToPlaylist = { showAddToPlaylistForTrack = track },
            onDownloadTrack = { playerViewModel.downloadTrack(track) },
            onShareFile = { playerViewModel.shareTrack(track) },
            onGoToAlbum = null, // Already here
            onGoToArtist = navController.trackArtistAction(track, playerViewModel.unifiedFor(track))
        )
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            onSubmit = { name, description ->
                playerViewModel.createPlaylist(name, description)
                showCreatePlaylistDialog = false
            }
        )
    }

    showAddToPlaylistForTrack?.let { track ->
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { showAddToPlaylistForTrack = null },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTrackToPlaylist(playlist.id, track)
                showAddToPlaylistForTrack = null
            },
            onCreateNew = {
                showAddToPlaylistForTrack = null
                showCreatePlaylistDialog = true
            }
        )
    }

    if (showAddToPlaylistForSelection) {
        AddToPlaylistSheet(
            title = pluralStringResource(R.plurals.add_n_tracks_to_playlist, selection.count, selection.count),
            playlists = playlists,
            onDismiss = { showAddToPlaylistForSelection = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(
                    playlist.id,
                    albumDetail?.tracks.orEmpty().filter { it.id in selection.selectedIds },
                )
                showAddToPlaylistForSelection = false
                selection.clear()
            },
            onCreateNew = {
                showAddToPlaylistForSelection = false
                showCreatePlaylistDialog = true
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            actions = {
                SearchAction(open = searchOpen, onToggle = {
                    searchOpen = !searchOpen
                    // Closing clears the filter. A hidden query in place is how
                    // a list ends up looking like it has lost rows.
                    if (!searchOpen) listQuery = ""
                })
            },
            title = {},
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        when {
            isLoading -> LoadingScreen()
            error != null -> ErrorScreen(
                message = error?.resolve(LocalContext.current) ?: stringResource(R.string.unknown_error),
                onRetry = { viewModel.retry() }
            )
            albumDetail != null -> {
                val detail = albumDetail ?: return
                // Every action on this screen works off the visible list: with
                // a filter applied, tapping row three has to queue what's on
                // screen, and "play all" has to mean what's on screen.
                val visibleTracks = androidx.compose.runtime.remember(detail.tracks, listQuery, listSort) {
                    detail.tracks.applySearchAndSort(listQuery, listSort)
                }
                androidx.compose.animation.AnimatedVisibility(visible = selection.active) {
                    tf.monochrome.desktop.ui.components.TrackSelectionBar(
                        selectedCount = selection.count,
                        onClose = { selection.clear() },
                        onAddToQueue = {
                            playerViewModel.addToQueue(visibleTracks.filter { it.id in selection.selectedIds })
                            selection.clear()
                        },
                        onAddToPlaylist = { showAddToPlaylistForSelection = true }
                    )
                }
                SearchOverlay(
                    open = searchOpen,
                    query = listQuery,
                    onQueryChange = { listQuery = it },
                    placeholder = stringResource(R.string.search_this_album),
                    onClose = { searchOpen = false; listQuery = "" },
                ) { searchTopInset ->
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .selectionKeys(onSelectAll = { clicks.selectAll(visibleTracks.map { it.id }) }),
                    contentPadding = PaddingValues(
                        top = searchTopInset,
                        bottom = 80.dp + LocalBottomChromeInset.current,
                    )
                ) {
                    item {
                        tf.monochrome.desktop.devedit.DevEditable("album_hero", Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CoverImage(
                                url = detail.album.coverUrl,
                                contentDescription = detail.album.title,
                                size = 240.dp,
                                cornerRadius = 12.dp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            // The catalog this album came from, beside any THX or Dolby Atmos mark.
                            if (source != null || detail.album.isThxSpatialAudio || detail.album.isDolbyAtmos) {
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                                ) {
                                    source?.let { tf.monochrome.desktop.ui.components.SourcePill(it) }
                                    if (detail.album.isThxSpatialAudio) {
                                        tf.monochrome.desktop.ui.components.ThxBadgePill()
                                    }
                                    if (detail.album.isDolbyAtmos) {
                                        tf.monochrome.desktop.ui.components.DolbyAtmosBadgePill()
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                            Text(
                                text = detail.album.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = detail.album.displayArtist,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .then(if (detail.album.artist?.id != null) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
                                    .clickable(enabled = detail.album.artist?.id != null) {
                                    detail.album.artist?.id?.let { artistId ->
                                        navController.navigateSafe(Screen.ArtistDetail.createRoute(artistId))
                                    }
                                }
                            )
                            detail.album.releaseYear?.let { year ->
                                Text(
                                    text = stringResource(R.string.separator_dot, albumTypeLabel(detail.album.type), year.toString()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            tf.monochrome.desktop.devedit.DevEditable("album_action_row") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilledIconButton(
                                    onClick = { playerViewModel.playAll(visibleTracks) },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.action_play),
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                                FilledIconButton(
                                    onClick = { playerViewModel.shufflePlay(visibleTracks) },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Shuffle,
                                        contentDescription = stringResource(R.string.visualizer_shuffle),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                FilledIconButton(
                                    onClick = { viewModel.downloadAll() },
                                    modifier = Modifier.size(48.dp),
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Icon(
                                        Icons.Default.Download,
                                        contentDescription = stringResource(R.string.download_album),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                        }
                    }

                    stickyHeader {
                        TrackListToolbar(
                            sort = listSort,
                            onSortChange = { listSort = it },
                            // No "Album": every row here is from the same one.
                            orders = listOf(
                                TrackOrder.ORIGINAL,
                                TrackOrder.TITLE,
                                TrackOrder.ARTIST,
                                TrackOrder.DURATION,
                            ),
                        )
                    }

                    // itemsIndexed, not items: the row falls back to the
                    // list position when a track carries no track number.
                    // Keyed by id all the same, so searching or re-sorting
                    // moves rows instead of rebuilding every one of them.
                    itemsIndexed(
                        visibleTracks,
                        key = { _, track -> track.id },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        TrackItem(
                            track = track,
                            onClick = {
                                clicks.click(track.id, visibleTracks.map { it.id }) {
                                    playerViewModel.playTrack(track, visibleTracks)
                                }
                            },
                            onLongClick = { clicks.toggle(track.id) },
                            onMoreClick = { showContextMenuForTrack = track },
                            onArtistClick = { artistId -> navController.openCatalogArtist(artistId) },
                            showCover = false,
                            trackNumber = track.trackNumber ?: (index + 1),
                            isDownloaded = track.id in downloadedTrackIds,
                            selectionMode = selection.active,
                            selected = track.id in selection.selectedIds
                        )
                    }
                }
                ListScrollbar(listState, Modifier.padding(top = searchTopInset, bottom = LocalBottomChromeInset.current))
                }
                }
            }
        }
    }
}

/**
 * The catalogue's release type in the reader's language. The API sends it as
 * an upper-case token; one this does not know is shown as it came.
 */
@Composable
private fun albumTypeLabel(type: String?): String = when (type?.uppercase()) {
    null, "ALBUM" -> stringResource(R.string.album_type_album)
    "SINGLE" -> stringResource(R.string.album_type_single)
    "COMPILATION" -> stringResource(R.string.album_type_compilation)
    else -> type
}
