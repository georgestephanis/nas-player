package dev.atv.nasplayer

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.atv.nasplayer.data.ResumeEntry
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.Settings
import dev.atv.nasplayer.data.joinPath
import dev.atv.nasplayer.playback.Engine
import dev.atv.nasplayer.playback.EngineChooser
import dev.atv.nasplayer.playback.ExoHost
import dev.atv.nasplayer.playback.PlaybackRequest
import dev.atv.nasplayer.playback.VlcHost
import dev.atv.nasplayer.ui.NasTheme
import dev.atv.nasplayer.ui.formatDuration

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val request = PlaybackRequest.from()
        val server = request?.let { ServerRepository.get(it.serverId) }
        if (request == null || server == null || request.items.isEmpty()) {
            finish()
            return
        }
        setContent {
            NasTheme {
                PlayerScreen(server, request, onExit = ::finish)
            }
        }
    }
}

/** One mounted engine instance; changing any field tears the old player down and starts a new one. */
data class PlaybackSession(val engine: Engine, val index: Int, val startMs: Long, val nonce: Int)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(server: ServerConfig, request: PlaybackRequest, onExit: () -> Unit) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<PlaybackSession?>(null) }
    var resumePrompt by remember { mutableStateOf<Pair<Int, ResumeEntry>?>(null) }
    var error by remember { mutableStateOf<Pair<PlaybackSession, String>?>(null) }
    var nonce by remember { mutableIntStateOf(0) }
    var slideshow by remember { mutableStateOf(false) }

    fun pathOf(i: Int) = joinPath(request.folder, request.items[i])
    fun isImage(i: Int) = dev.atv.nasplayer.source.MediaTypes.kindOf(request.items[i]) == dev.atv.nasplayer.source.MediaKind.IMAGE
    fun engineFor(i: Int) = request.forcedEngine ?: EngineChooser.initialEngine(request.items[i], Settings.enginePreference)

    fun start(i: Int, startMs: Long, engine: Engine = engineFor(i)) {
        error = null
        resumePrompt = null
        session = PlaybackSession(engine, i, startMs, ++nonce)
    }

    fun begin(i: Int, ask: Boolean) {
        if (isImage(i)) { start(i, 0); return }
        val saved = ResumeStore.get(server.id, pathOf(i))?.takeIf { !it.watched && it.positionMs > 15_000 }
        when {
            saved == null -> start(i, 0)
            ask -> { session = null; resumePrompt = i to saved }
            else -> start(i, saved.positionMs)
        }
    }

    fun next(auto: Boolean) {
        val s = session ?: return
        val hasNext = s.index + 1 < request.items.size
        when {
            hasNext && (!auto || Settings.autoPlayNext || slideshow) -> begin(s.index + 1, ask = false)
            !auto && !hasNext && isImage(s.index) -> Unit // stay on the last photo
            else -> onExit()
        }
    }

    fun previous() {
        val s = session ?: return
        if (s.index > 0) begin(s.index - 1, ask = false) else if (!isImage(s.index)) start(s.index, 0, s.engine)
    }

    LaunchedEffect(Unit) { begin(request.index, ask = true) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val s = session
        if (s != null && error == null) {
            val onProgress: (Long, Long) -> Unit = { pos, dur ->
                ResumeStore.save(server.id, request.folder, request.items[s.index], pos, dur)
            }
            when {
                isImage(s.index) -> dev.atv.nasplayer.playback.ImageHost(
                    server = server,
                    request = request,
                    session = s,
                    slideshow = slideshow,
                    onSlideshowChange = { slideshow = it },
                    onNext = { next(auto = false) },
                    onPrevious = ::previous,
                    onAdvance = {
                        // Slideshow stops at the end of the folder rather than leaving the player.
                        if (s.index + 1 < request.items.size) begin(s.index + 1, ask = false) else slideshow = false
                    },
                    onExit = onExit,
                )
                s.engine == Engine.EXO -> ExoHost(
                    server = server,
                    request = request,
                    session = s,
                    onProgress = onProgress,
                    onEnded = { next(auto = true) },
                    onNext = { next(auto = false) },
                    onPrevious = ::previous,
                    onFallback = { pos, reason ->
                        Toast.makeText(context, "Switching to VLC engine ($reason)", Toast.LENGTH_SHORT).show()
                        start(s.index, pos, Engine.VLC)
                    },
                    onError = { msg -> error = s to msg },
                    onExit = onExit,
                )
                else -> VlcHost(
                    server = server,
                    request = request,
                    session = s,
                    onProgress = onProgress,
                    onEnded = { next(auto = true) },
                    onNext = { next(auto = false) },
                    onPrevious = ::previous,
                    onError = { msg -> error = s to msg },
                    onExit = onExit,
                )
            }
        }

        resumePrompt?.let { (i, entry) ->
            ChoicePanel(
                title = request.items[i],
                message = "You stopped at ${formatDuration(entry.positionMs)} of ${formatDuration(entry.durationMs)}.",
                primary = "Resume" to { start(i, entry.positionMs) },
                secondary = "Start over" to { start(i, 0) },
                onBack = onExit,
            )
        }

        error?.let { (failed, msg) ->
            val other = if (failed.engine == Engine.EXO) Engine.VLC else Engine.EXO
            val otherLabel = if (other == Engine.VLC) "Try with VLC" else "Try with ExoPlayer"
            ChoicePanel(
                title = "Can't play ${request.items[failed.index]}",
                message = msg,
                primary = "Retry" to { start(failed.index, failed.startMs, failed.engine) },
                secondary = otherLabel to { start(failed.index, failed.startMs, other) },
                tertiary = if (failed.index + 1 < request.items.size) "Next file" to { begin(failed.index + 1, ask = false) } else null,
                onBack = onExit,
            )
        }
    }
}

@Composable
private fun ChoicePanel(
    title: String,
    message: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>,
    tertiary: Pair<String, () -> Unit>? = null,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = primary.second, modifier = Modifier.focusRequester(focus)) { Text(primary.first) }
                OutlinedButton(onClick = secondary.second) { Text(secondary.first) }
                tertiary?.let { OutlinedButton(onClick = it.second) { Text(it.first) } }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
