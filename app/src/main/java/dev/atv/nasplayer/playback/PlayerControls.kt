package dev.atv.nasplayer.playback

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.atv.nasplayer.ui.formatDuration
import kotlinx.coroutines.delay

internal class TrackChoice(val kind: String, val name: String, val selected: Boolean, val pick: () -> Unit)

private class BarButton(val icon: ImageVector, val label: String, val enabled: Boolean, val action: () -> Unit)

/**
 * Remote handling and the bottom bar, shared by both engines. The video is never dimmed: only the bar
 * itself has a background. ◀ ▶ always seek, whether or not the bar is showing; ▼ moves onto the buttons,
 * where ◀ ▶ choose one until ▲ or Back moves off again.
 */
@Composable
internal fun PlayerControls(
    title: String,
    engineLabel: String,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    buffering: Boolean,
    keepBarVisible: Boolean,
    hasNext: Boolean,
    setPlaying: (Boolean) -> Unit,
    seekBy: (Long) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    tracks: () -> List<TrackChoice>,
    onExit: () -> Unit,
    video: @Composable BoxScope.() -> Unit,
) {
    var barShown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var trackMenu by remember { mutableStateOf<List<TrackChoice>?>(null) }
    val focus = remember { FocusRequester() }

    fun show() { barShown = true; poke++ }
    fun next() { if (hasNext) onNext() }

    val buttons = listOf(
        BarButton(Icons.Default.SkipPrevious, "Previous", true, onPrevious),
        BarButton(Icons.Default.Replay10, "Back 10 seconds", true) { seekBy(-10_000) },
        BarButton(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play", true) { setPlaying(!playing) },
        BarButton(Icons.Default.Forward30, "Forward 30 seconds", true) { seekBy(30_000) },
        BarButton(Icons.Default.SkipNext, "Next", hasNext, ::next),
        BarButton(Icons.Default.ClosedCaption, "Audio and subtitles", true) { trackMenu = tracks() },
    )
    val playPauseIndex = 2

    LaunchedEffect(poke, playing, keepBarVisible) {
        if (playing && !keepBarVisible) {
            delay(4_000)
            barShown = false
            selected = null
        }
    }
    LaunchedEffect(trackMenu) {
        if (trackMenu == null) runCatching { focus.requestFocus() }
    }

    BackHandler {
        when {
            selected != null -> { selected = null; show() }
            barShown && playing && !keepBarVisible -> barShown = false
            else -> onExit()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val row = selected
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (row != null) selected = (row - 1).coerceAtLeast(0) else seekBy(-10_000)
                        show(); true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (row != null) selected = (row + 1).coerceAtMost(buttons.lastIndex) else seekBy(30_000)
                        show(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); show(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                        if (row != null) buttons[row].takeIf { it.enabled }?.action?.invoke() else setPlaying(!playing)
                        show(); true
                    }
                    AndroidKeyEvent.KEYCODE_SPACE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_HEADSETHOOK -> { setPlaying(!playing); show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { setPlaying(true); show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { setPlaying(false); show(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { selected = row ?: playPauseIndex; show(); true }
                    AndroidKeyEvent.KEYCODE_INFO -> { show(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> {
                        if (row != null) { selected = null; show() } else trackMenu = tracks()
                        true
                    }
                    AndroidKeyEvent.KEYCODE_MENU, AndroidKeyEvent.KEYCODE_CAPTIONS -> { trackMenu = tracks(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { next(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { onPrevious(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { onExit(); true }
                    else -> false
                }
            },
    ) {
        video()

        AnimatedVisibility(
            visible = barShown || buffering || !playing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 32.dp, vertical = 10.dp),
            ) {
                LinearProgressIndicator(
                    progress = { if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    buttons.forEachIndexed { i, b ->
                        Box(
                            Modifier
                                .size(40.dp)
                                .background(if (selected == i) Color.White else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                b.icon,
                                contentDescription = b.label,
                                tint = when {
                                    selected == i -> Color.Black
                                    b.enabled -> Color.White
                                    else -> Color.White.copy(alpha = 0.3f)
                                },
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${formatDuration(positionMs)} / ${formatDuration(durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.width(20.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildList {
                                when {
                                    buffering -> add("Buffering…")
                                    !playing -> add("Paused")
                                }
                                if (engineLabel.isNotEmpty()) add(engineLabel)
                                add(
                                    selected?.let { "${buttons[it].label}   ◀ ▶ choose   OK select   ▲ back" }
                                        ?: "◀ −10s   ▶ +30s   OK play/pause   ▼ buttons   ▲ audio & subtitles",
                                )
                            }.joinToString(" · "),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    trackMenu?.let { choices ->
        TrackDialog(choices, onDismiss = { trackMenu = null }, onPick = { it.pick(); trackMenu = null })
    }
}

@Composable
private fun TrackDialog(choices: List<TrackChoice>, onDismiss: () -> Unit, onPick: (TrackChoice) -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), modifier = Modifier.width(520.dp)) {
            if (choices.isEmpty()) {
                Text("No selectable tracks", Modifier.padding(24.dp))
                return@Surface
            }
            LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                var lastKind: String? = null
                choices.forEachIndexed { i, c ->
                    if (c.kind != lastKind) {
                        lastKind = c.kind
                        item(key = "h-${c.kind}") {
                            Text(c.kind, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
                        }
                    }
                    item(key = i) {
                        ListItem(
                            selected = c.selected,
                            onClick = { onPick(c) },
                            headlineContent = { Text(c.name) },
                            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}
