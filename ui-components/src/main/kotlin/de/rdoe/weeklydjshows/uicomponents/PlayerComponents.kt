package de.rdoe.weeklydjshows.uicomponents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun MiniPlayer(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    liked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onToggle: () -> Unit,
    onLike: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipeThresholdPx = with(LocalDensity.current) { 56.dp.toPx() }
    val progress = durationMs
        .takeIf { it > 0L }
        ?.let { (positionMs.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(hasPrevious, hasNext) {
                var draggedX = 0f
                var draggedY = 0f
                detectDragGestures(
                    onDragStart = {
                        draggedX = 0f
                        draggedY = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        draggedX += amount.x
                        draggedY += amount.y
                    },
                    onDragCancel = {
                        draggedX = 0f
                        draggedY = 0f
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(draggedY) > kotlin.math.abs(draggedX)) {
                            if (draggedY <= -swipeThresholdPx) onOpen()
                        } else {
                            when {
                                draggedX <= -swipeThresholdPx && hasNext -> onNext()
                                draggedX >= swipeThresholdPx && hasPrevious -> onPrevious()
                            }
                        }
                        draggedX = 0f
                        draggedY = 0f
                    },
                )
            }
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 4.dp,
    ) {
        Box {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(artworkUrl, title, Modifier.size(48.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onLike) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (liked) "Gefällt mir entfernen" else "Gefällt mir",
                        tint = if (liked) BrandPink else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onToggle) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Abspielen",
                    )
                }
            }
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = BrandPink,
                    trackColor = LocalContentColor.current.copy(alpha = 0.16f),
                )
            }
        }
    }
}

@Composable
fun FullPlayer(
    title: String,
    artist: String,
    artworkUrl: String?,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    liked: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekBy: (Long) -> Unit,
    onSpeed: (Float) -> Unit,
    onLike: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onMiniPlayer: (() -> Unit)? = null,
    onCollapse: () -> Unit,
    onEpisodeInfo: (() -> Unit)? = null,
    onShowInfo: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    val swipeThresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val shown = dragging ?: positionMs.coerceIn(0, durationMs.coerceAtLeast(positionMs)).toFloat()
    Column(
        modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        // Keep the seek slider outside this gesture area: horizontal swipes over the artwork and
        // title navigate the queue, while dragging the timeline always remains a seek gesture.
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(hasPrevious, hasNext) {
                    var draggedX = 0f
                    var draggedY = 0f
                    detectDragGestures(
                        onDragStart = {
                            draggedX = 0f
                            draggedY = 0f
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            draggedX += amount.x
                            draggedY += amount.y
                        },
                        onDragCancel = {
                            draggedX = 0f
                            draggedY = 0f
                        },
                        onDragEnd = {
                            if (kotlin.math.abs(draggedY) > kotlin.math.abs(draggedX)) {
                                if (draggedY >= swipeThresholdPx) onCollapse()
                            } else {
                                when {
                                    draggedX <= -swipeThresholdPx && hasNext -> onNext()
                                    draggedX >= swipeThresholdPx && hasPrevious -> onPrevious()
                                }
                            }
                            draggedX = 0f
                            draggedY = 0f
                        },
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth()) {
                Artwork(
                    artworkUrl,
                    title,
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clickable(enabled = onEpisodeInfo != null) { onEpisodeInfo?.invoke() },
                )
                if (onMiniPlayer != null) {
                    FilledTonalIconButton(
                        onClick = onMiniPlayer,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    ) {
                        Icon(Icons.Default.PictureInPictureAlt, "Mini-Player öffnen")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                title.ifBlank { "Weekly DJ Shows" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(enabled = onEpisodeInfo != null) { onEpisodeInfo?.invoke() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                artist,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = onShowInfo != null) { onShowInfo?.invoke() },
            )
        }
        Spacer(Modifier.height(18.dp))
        Slider(
            value = shown.coerceIn(0f, max),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shown.toLong()), style = MaterialTheme.typography.labelSmall)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) { Icon(Icons.Default.SkipPrevious, "Vorherige Folge") }
            IconButton(onClick = { onSeekBy(-10_000) }) { Icon(Icons.Default.Replay10, "10 Sekunden zurück") }
            FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "Pause" else "Abspielen",
                    modifier = Modifier.size(34.dp),
                )
            }
            IconButton(onClick = { onSeekBy(30_000) }) { Icon(Icons.Default.Forward30, "30 Sekunden vor") }
            IconButton(onClick = onNext, enabled = hasNext) { Icon(Icons.Default.SkipNext, "Nächste Folge") }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                AssistChip(
                    onClick = { speedMenu = true },
                    label = { Text("${trimSpeed(speed)}× Geschwindigkeit") },
                    leadingIcon = { Icon(Icons.Default.Speed, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(
                    expanded = speedMenu,
                    onDismissRequest = { speedMenu = false },
                ) {
                    PLAYBACK_SPEEDS.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text("${trimSpeed(choice)}×") },
                            leadingIcon = {
                                if (kotlin.math.abs(choice - speed) < 0.01f) {
                                    Icon(Icons.Default.Check, null)
                                }
                            },
                            onClick = {
                                onSpeed(choice)
                                speedMenu = false
                            },
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onLike) {
                    Icon(
                        if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        if (liked) "Gefällt mir entfernen" else "Gefällt mir",
                        tint = if (liked) BrandPink else LocalContentColor.current,
                        modifier = Modifier.size(27.dp),
                    )
                }
                IconButton(onClick = onQueue) {
                    Icon(Icons.Default.QueueMusic, "Warteschlange", Modifier.size(28.dp))
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun trimSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else "%.2f".format(speed).trimEnd('0')

private val PLAYBACK_SPEEDS = listOf(
    0.75f,
    0.85f,
    0.9f,
    1f,
    1.1f,
    1.2f,
    1.25f,
    1.3f,
    1.4f,
    1.5f,
    1.6f,
    1.7f,
    1.8f,
    1.9f,
    2f,
)
