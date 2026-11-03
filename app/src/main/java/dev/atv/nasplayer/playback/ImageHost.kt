package dev.atv.nasplayer.playback

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import dev.atv.nasplayer.PlaybackSession
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.Settings
import dev.atv.nasplayer.data.joinPath
import dev.atv.nasplayer.images.FullImage
import kotlinx.coroutines.delay

/**
 * Photo viewer inside the player screen. ◀/▶ move through the folder queue (which may continue
 * into videos or music), OK toggles a slideshow, ▼/INFO shows details.
 */
@Composable
fun ImageHost(
    server: ServerConfig,
    request: PlaybackRequest,
    session: PlaybackSession,
    slideshow: Boolean,
    onSlideshowChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onAdvance: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val name = request.items[session.index]
    val path = joinPath(request.folder, name)
    var overlay by remember(session) { mutableStateOf(true) }
    var poke by remember(session) { mutableIntStateOf(0) }
    var loaded by remember(session) { mutableStateOf(false) }
    var failed by remember(session) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val recorder = remember(session) { PlaybackRecorder(server.name, path, Engine.EXO) }

    LaunchedEffect(poke, session) { delay(3_500); overlay = false }
    LaunchedEffect(slideshow, loaded, failed, session) {
        if (slideshow && (loaded || failed)) {
            delay(Settings.slideshowSeconds * 1000L)
            onAdvance()
        }
    }
    LaunchedEffect(session) { runCatching { focus.requestFocus() } }

    BackHandler {
        when {
            slideshow -> onSlideshowChange(false)
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
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_NEXT,
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { onNext(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> { onPrevious(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> {
                        onSlideshowChange(!slideshow); overlay = true; poke++; true
                    }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_DPAD_UP,
                    AndroidKeyEvent.KEYCODE_INFO -> { overlay = !overlay; poke++; true }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { onExit(); true }
                    else -> false
                }
            },
    ) {
        coil3.compose.AsyncImage(
            model = ImageRequest.Builder(context).data(FullImage(server.id, path)).build(),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
            onState = { st: AsyncImagePainter.State ->
                when (st) {
                    is AsyncImagePainter.State.Success -> if (!loaded) {
                        loaded = true
                        val img = st.result.image
                        recorder.track("image", TrackInfo("image", name.substringAfterLast('.').lowercase(), "${img.width}x${img.height}", decoder = "Android image decoder"))
                        recorder.finish(Outcome.PLAYED)
                    }
                    is AsyncImagePainter.State.Error -> if (!failed) {
                        failed = true
                        recorder.track("image", TrackInfo("image", name.substringAfterLast('.').lowercase(), supported = false))
                        recorder.finish(Outcome.FAILED, st.result.throwable.message ?: "could not decode image")
                    }
                    else -> Unit
                }
            },
        )
        if (!loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (failed) "Can't display this image" else "Loading…", color = Color.White.copy(alpha = 0.7f))
            }
        }

        AnimatedVisibility(
            visible = overlay,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(horizontal = 48.dp, vertical = 28.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    Text("${session.index + 1} / ${request.items.size}", color = Color.White.copy(alpha = 0.7f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (slideshow) "Slideshow on · " else "") + "◀ ▶ previous / next   OK ${if (slideshow) "stop" else "start"} slideshow",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}
