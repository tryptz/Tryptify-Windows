package tf.monochrome.desktop.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.desktop.domain.model.Track
import tf.monochrome.desktop.ui.components.CoverImage
import tf.monochrome.desktop.ui.input.ListScrollbar
import tf.monochrome.desktop.ui.input.contextClick
import tf.monochrome.desktop.ui.theme.MonoDimens
import java.awt.Cursor
import kotlin.math.abs
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.desktop.R

private val QueueRowHeight = 64.dp

/**
 * Maps an in-progress reorder drag to a drop index using the list's real
 * laid-out item geometry. The old math divided the accumulated pixel offset by
 * a fixed row height, which landed a slot off wherever rows weren't uniform (the
 * taller now-playing row and its section labels). Picking the item whose centre
 * is nearest the dragged row's projected centre is height-agnostic.
 */
private fun dropTargetIndex(
    listState: LazyListState,
    fromIndex: Int,
    dragOffsetY: Float,
    lastIndex: Int,
): Int {
    val info = listState.layoutInfo
    val dragged = info.visibleItemsInfo.firstOrNull { it.index == fromIndex } ?: return fromIndex
    val draggedCenter = dragged.offset + dragged.size / 2f + dragOffsetY
    val target = info.visibleItemsInfo.minByOrNull {
        abs((it.offset + it.size / 2f) - draggedCenter)
    }?.index ?: fromIndex
    return target.coerceIn(0, lastIndex)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    playerViewModel: PlayerViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queue by playerViewModel.queue.collectAsStateWithLifecycle()
    val currentIndex by playerViewModel.currentIndex.collectAsStateWithLifecycle()
    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val isRadioActive by playerViewModel.isRadioActive.collectAsStateWithLifecycle()
    val isRadioGenerating by playerViewModel.isRadioGenerating.collectAsStateWithLifecycle()
    val radioStatusMessage by playerViewModel.radioStatusMessage.collectAsStateWithLifecycle()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIndices by remember { mutableStateOf(setOf<Int>()) }
    var menuIndex by remember { mutableStateOf<Int?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }

    // Drag-to-reorder: the handle accumulates a vertical offset and the move
    // is committed once on release. Rows keep stable bindings during the
    // gesture, which keeps the pointerInput lambda alive for its duration.
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()
    // Desktop: true while a mouse drags a handle, so the touch long-press
    // detector on the same handle keeps out of that drag.
    var mouseReorder by remember { mutableStateOf(false) }
    // The row Alt+Up/Down just moved, so it can keep itself on screen.
    var keyMovedKey by remember { mutableStateOf<String?>(null) }

    // Open on the playing row, not on the top of the history above it: that is
    // where Tab and the wheel should start.
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }

    // Stable per-row keys so reorder/delete/radio-append animate the right rows
    // and don't scramble per-row state. A queue can hold the same track twice,
    // and LazyColumn keys must be unique, so duplicate ids get an occurrence
    // suffix; the common (no-duplicate) case keys straight by track id.
    //
    // The key is bundled WITH the track (not kept in a parallel list indexed by
    // position): LazyColumn rebuilds its key→index map off the interval's item
    // count via snapshot observation, and a parallel list could be momentarily
    // shorter than that count while the queue mutated — which crashed with an
    // IndexOutOfBounds from the key lambda. Reading the key off the same element
    // the item provides makes that impossible.
    val keyedQueue = remember(queue) {
        val counts = HashMap<Long, Int>()
        queue.map { track ->
            val n = counts[track.id] ?: 0
            counts[track.id] = n + 1
            val key = if (n == 0) track.id.toString() else "${track.id}#$n"
            key to track
        }
    }

    fun exitSelection() {
        selectionMode = false
        selectedIndices = emptySet()
    }

    // The keyboard's and the menu's reorder: one slot at a time, the way the
    // handle moves a row a finger's width.
    fun moveBy(index: Int, key: String, delta: Int) {
        val target = index + delta
        // Selection is held by index, so a move would shift what it holds.
        if (selectionMode || draggingIndex != null || target !in queue.indices) return
        keyMovedKey = key
        playerViewModel.moveQueueItem(index, target)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        // Escape leaves selection before it closes the sheet. Registered inside
        // the sheet so it outranks the sheet's own dismiss.
        BackHandler(enabled = selectionMode) { exitSelection() }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.queue),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = pluralStringResource(R.plurals.track_count, queue.size, queue.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Radio: seeds from the current track, keeps the tail topped up.
                TextButton(
                    onClick = {
                        if (isRadioActive) playerViewModel.stopRadio()
                        else playerViewModel.startRadio()
                    },
                    enabled = currentTrack != null
                ) {
                    if (isRadioGenerating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Podcasts,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (isRadioActive) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (isRadioActive) stringResource(R.string.radio_on) else stringResource(R.string.tab_radio))
                }

                TextButton(
                    onClick = { showResetConfirm = true },
                    enabled = queue.size > 1
                ) {
                    Icon(
                        Icons.Default.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_reset))
                }
            }

            radioStatusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (selectionMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pluralStringResource(R.plurals.selected_count, selectedIndices.size, selectedIndices.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { exitSelection() }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(
                        onClick = {
                            playerViewModel.removeSelectedFromQueue(selectedIndices)
                            exitSelection()
                        },
                        enabled = selectedIndices.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (queue.isEmpty()) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(keyedQueue, key = { _, entry -> entry.first }) { index, entry ->
                            val track = entry.second
                            val isCurrent = index == currentIndex
                            val isDragging = index == draggingIndex
                            val rowKey = entry.first
                            val bringIntoView = remember { BringIntoViewRequester() }
                            // A row moved from the keyboard follows itself, so
                            // holding Alt+Down walks it past the bottom edge.
                            LaunchedEffect(index) {
                                if (keyMovedKey == rowKey) {
                                    keyMovedKey = null
                                    bringIntoView.bringIntoView()
                                }
                            }

                            // One Column per row so the "Now Playing" / "Up Next"
                            // labels stack with the row instead of overlapping it.
                            Column {
                                // "Now Playing" sits directly above the actual current
                                // track — it used to be a fixed header pinned to the
                                // top of the list, which was wrong whenever the current
                                // track wasn't the first one.
                                if (isCurrent) {
                                    Text(
                                        text = stringResource(R.string.now_playing),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .bringIntoViewRequester(bringIntoView)
                                        .zIndex(if (isDragging) 1f else 0f)
                                        .graphicsLayer {
                                            translationY = if (isDragging) dragOffsetY else 0f
                                        }
                                ) {
                                    QueueTrackItem(
                                        track = track,
                                        isCurrentTrack = isCurrent,
                                        selectionMode = selectionMode,
                                        isSelected = index in selectedIndices,
                                        isDragging = isDragging,
                                        onClick = {
                                            if (selectionMode) {
                                                selectedIndices =
                                                    if (index in selectedIndices) selectedIndices - index
                                                    else selectedIndices + index
                                                if (selectedIndices.isEmpty()) selectionMode = false
                                            } else {
                                                playerViewModel.skipToQueueIndex(index)
                                            }
                                        },
                                        onLongClick = {
                                            // A long-press that started a reorder drag
                                            // must not also pop the context menu.
                                            if (!selectionMode && draggingIndex == null) menuIndex = index
                                        },
                                        onMoveUp = { moveBy(index, rowKey, -1) },
                                        onMoveDown = { moveBy(index, rowKey, +1) },
                                        dragHandleModifier = Modifier
                                            .pointerHoverIcon(PointerIcon(Cursor(Cursor.MOVE_CURSOR)))
                                            .pointerInput(index) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = {
                                                        if (!mouseReorder) {
                                                            draggingIndex = index
                                                            dragOffsetY = 0f
                                                            // Close any menu the row's own
                                                            // long-press may have just opened.
                                                            menuIndex = null
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        if (!mouseReorder) {
                                                            val from = draggingIndex
                                                            if (from != null) {
                                                                val target = dropTargetIndex(
                                                                    listState, from, dragOffsetY, queue.lastIndex
                                                                )
                                                                if (target != from) {
                                                                    playerViewModel.moveQueueItem(from, target)
                                                                }
                                                            }
                                                            draggingIndex = null
                                                            dragOffsetY = 0f
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        if (!mouseReorder) {
                                                            draggingIndex = null
                                                            dragOffsetY = 0f
                                                        }
                                                    },
                                                    onDrag = { change, dragAmount ->
                                                        if (!mouseReorder) {
                                                            change.consume()
                                                            dragOffsetY += dragAmount.y
                                                        }
                                                    }
                                                )
                                            }
                                            // Desktop: a mouse has no scroll to tell a
                                            // reorder apart from, so the handle grabs on
                                            // press. Touch keeps the long press above.
                                            // After it in the chain, so it sees each
                                            // move first and the long press, finding
                                            // them consumed, stands down.
                                            .pointerInput(index) {
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false)
                                                    // Left button only: a right-click here is
                                                    // the row's menu, not a grab.
                                                    if (down.type != PointerType.Mouse ||
                                                        !currentEvent.buttons.isPrimaryPressed ||
                                                        draggingIndex != null
                                                    ) {
                                                        return@awaitEachGesture
                                                    }
                                                    // Consumed, so the row under it does
                                                    // not take the press as a click.
                                                    down.consume()
                                                    mouseReorder = true
                                                    draggingIndex = index
                                                    dragOffsetY = 0f
                                                    menuIndex = null
                                                    var dropped = false
                                                    try {
                                                        dropped = drag(down.id) { change ->
                                                            dragOffsetY += change.positionChange().y
                                                            change.consume()
                                                        }
                                                    } finally {
                                                        val from = draggingIndex
                                                        if (dropped && from != null) {
                                                            val target = dropTargetIndex(
                                                                listState, from, dragOffsetY, queue.lastIndex
                                                            )
                                                            if (target != from) {
                                                                playerViewModel.moveQueueItem(from, target)
                                                            }
                                                        }
                                                        draggingIndex = null
                                                        dragOffsetY = 0f
                                                        mouseReorder = false
                                                    }
                                                }
                                            }
                                    )

                                    QueueTrackMenu(
                                        expanded = menuIndex == index,
                                        onDismiss = { menuIndex = null },
                                        onPlayNext = { playerViewModel.playQueueItemNext(index) },
                                        onStartRadio = { playerViewModel.startRadioFrom(track) },
                                        onSelect = {
                                            selectionMode = true
                                            selectedIndices = setOf(index)
                                        },
                                        onDelete = { playerViewModel.removeFromQueue(index) },
                                        onMoveUp = if (index > 0) {
                                            { moveBy(index, rowKey, -1) }
                                        } else null,
                                        onMoveDown = if (index < queue.lastIndex) {
                                            { moveBy(index, rowKey, +1) }
                                        } else null,
                                    )
                                }

                                // Divider after the current track.
                                if (isCurrent && index < queue.size - 1) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.up_next),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                    // In the sheet's side gutter, clear of the drag handles.
                    ListScrollbar(listState, Modifier.offset(x = 12.dp))
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.queue_reset_title)) },
            text = {
                Text(stringResource(R.string.queue_reset_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        playerViewModel.resetQueue()
                        exitSelection()
                        showResetConfirm = false
                    }
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun QueueTrackMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onStartRadio: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_play_next)) },
            onClick = {
                onDismiss()
                onPlayNext()
            }
        )
        // The handle's reorder for a pointer that cannot drag or a keyboard.
        onMoveUp?.let { move ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.api_move_up)) },
                onClick = {
                    onDismiss()
                    move()
                }
            )
        }
        onMoveDown?.let { move ->
            DropdownMenuItem(
                text = { Text(stringResource(R.string.queue_move_down)) },
                onClick = {
                    onDismiss()
                    move()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.start_radio_from_song)) },
            onClick = {
                onDismiss()
                onStartRadio()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_select)) },
            onClick = {
                onDismiss()
                onSelect()
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete)) },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QueueTrackItem(
    track: Track,
    isCurrentTrack: Boolean,
    selectionMode: Boolean,
    isSelected: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    dragHandleModifier: Modifier,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn(min=) rather than a fixed height so the two text lines
            // aren't clipped at large font scale; the drop-target math reads
            // real laid-out geometry, so a taller row no longer misplaces drops.
            .heightIn(min = QueueRowHeight)
            .graphicsLayer { alpha = if (isDragging) 0.85f else 1f }
            // Desktop: Alt+Up/Down reorder the focused row, the keyboard's
            // version of the drag handle.
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isAltPressed) return@onKeyEvent false
                val move = when (event.key) {
                    Key.DirectionUp -> onMoveUp
                    Key.DirectionDown -> onMoveDown
                    else -> null
                } ?: return@onKeyEvent false
                move()
                true
            }
            // Right-click, the Menu key and Shift+F10 open what a long press does.
            .contextClick(onContextClick = onLongClick)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            selectionMode -> {
                Icon(
                    imageVector = if (isSelected) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (isSelected) stringResource(R.string.state_selected) else stringResource(R.string.state_not_selected),
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp).padding(8.dp)
                )
            }
            isCurrentTrack -> {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.now_playing),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp).padding(8.dp)
                )
            }
            else -> {
                CoverImage(
                    url = track.coverUrl,
                    contentDescription = track.title,
                    size = 40.dp,
                    cornerRadius = 4.dp
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrentTrack) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrentTrack) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Where each queued song plays from, so a mixed queue reads at a glance.
                tf.monochrome.desktop.ui.components.LocalTrackSource.current(track)?.let {
                    tf.monochrome.desktop.ui.components.SourcePill(it)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = track.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Text(
            text = track.formattedDuration,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // The row menu in plain sight, and a Tab stop of its own; a long press
        // is nothing a mouse or a keyboard would think to try.
        if (!selectionMode) {
            IconButton(onClick = onLongClick, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = stringResource(R.string.action_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            // 24dp glyph, but the drag pointer node wraps the 48dp minimum
            // touch target — this is the sheet's only reorder affordance.
            modifier = dragHandleModifier
                .minimumInteractiveComponentSize()
                .padding(start = 12.dp)
                .size(24.dp)
        )
    }
}
