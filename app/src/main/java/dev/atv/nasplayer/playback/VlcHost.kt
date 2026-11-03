package dev.atv.nasplayer.playback

import android.content.Context
import android.net.Uri
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.atv.nasplayer.PlaybackSession
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.joinPath
import dev.atv.nasplayer.ui.formatDuration
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

object VlcRuntime {
    @Volatile private var instance: LibVLC? = null

    fun get(context: Context): LibVLC = instance ?: synchronized(this) {
        instance ?: LibVLC(
            context.applicationContext,
            arrayListOf(
                "--no-drop-late-frames",
                "--no-skip-frames",
                "--audio-time-stretch",
                "--avcodec-skiploopfilter=1", // keeps software-decoded HEVC/VC-1 real-time on weak SoCs
                "--subsdec-encoding=UTF-8",
                "--freetype-rel-fontsize=16",
            ),
        ).also { instance = it }
    }
}

private data class TrackChoice(val kind: String, val id: Int, val name: String, val selected: Boolean)

@Composable
fun VlcHost(
    server: ServerConfig,
    request: PlaybackRequest,
    session: PlaybackSession,
    onProgress: (Long, Long) -> Unit,
    onEnded: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onError: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val libVlc = remember { VlcRuntime.get(context) }
    val mp = remember(session) { MediaPlayer(libVlc) }
    val name = request.items[session.index]

    var viewReady by remember(session) { mutableStateOf(false) }
    var time by remember(session) { mutableLongStateOf(session.startMs) }
    var length by remember(session) { mutableLongStateOf(0L) }
    var playing by remember(session) { mutableStateOf(false) }
    var buffering by remember(session) { mutableStateOf(true) }
    var overlay by remember(session) { mutableStateOf(true) }
    var poke by remember(session) { mutableIntStateOf(0) }
    var trackMenu by remember(session) { mutableStateOf<List<TrackChoice>?>(null) }
    val focus = remember { FocusRequester() }

    fun show() { overlay = true; poke++ }
    fun seekBy(deltaMs: Long) {
        val target = (mp.time + deltaMs).coerceIn(0, if (length > 0) length - 1_000 else Long.MAX_VALUE)
        mp.setTime(target)
        time = target
        show()
    }
    fun openTracks() {
        val audio = mp.audioTracks.orEmpty().map { TrackChoice("Audio", it.id, it.name, it.id == mp.audioTrack) }
        val spu = mp.spuTracks.orEmpty().map { TrackChoice("Subtitles", it.id, it.name, it.id == mp.spuTrack) }
        trackMenu = audio + spu
    }

    val recorder = remember(session) { PlaybackRecorder(server.name, joinPath(request.folder, name), Engine.VLC) }

    fun recordVlcTracks() {
        val media = mp.media ?: return
        try {
            for (i in 0 until media.trackCount) {
                val t = media.getTrack(i) ?: continue
                val (type, detail) = when (t) {
                    is IMedia.VideoTrack -> "video" to "${t.width}x${t.height}"
                    is IMedia.AudioTrack -> "audio" to "${t.channels}ch ${t.rate}Hz"
                    else -> "text" to ""
                }
                val lang = t.language?.let { " $it" } ?: ""
                recorder.track("vlc-$i", TrackInfo(type, t.codec ?: t.originalCodec ?: "?", detail + lang, decoder = "libvlc"))
            }
        } finally {
            media.release()
        }
    }

    DisposableEffect(mp) {
        var finished = false
        var played = false
        mp.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.TimeChanged -> time = e.timeChanged
                MediaPlayer.Event.LengthChanged -> length = e.lengthChanged
                MediaPlayer.Event.Playing -> {
                    playing = true; buffering = false
                    if (!played) { played = true; recordVlcTracks() }
                }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Buffering -> buffering = e.buffering < 100f
                MediaPlayer.Event.EndReached -> if (!finished) {
                    finished = true
                    if (length > 0) onProgress(length, length)
                    onEnded()
                }
                MediaPlayer.Event.EncounteredError -> if (!finished) {
                    finished = true
                    recorder.finish(Outcome.FAILED, "VLC could not open or decode the file")
                    onError("VLC couldn't open or decode this file.")
                }
            }
        }
        onDispose {
            if (played) recorder.finish(Outcome.PLAYED) else recorder.finish(Outcome.FAILED, "stopped while still loading")
            mp.setEventListener(null)
            if (!finished && length > 0) onProgress(mp.time, length)
            mp.stop()
            mp.detachViews()
            mp.release()
        }
    }

    LaunchedEffect(viewReady) {
        if (!viewReady) return@LaunchedEffect
        val target = VlcMedia.target(server, joinPath(request.folder, name))
        val media = Media(libVlc, Uri.parse(target.url))
        target.options.forEach(media::addOption)
        media.addOption(":network-caching=3000")
        media.setHWDecoderEnabled(true, false)
        if (session.startMs > 0) media.addOption(":start-time=${session.startMs / 1000.0}")
        Subtitles.sidecarsFor(name, request.siblings).forEach { sub ->
            val url = VlcMedia.target(server, joinPath(request.folder, sub.name)).url
            media.addSlave(IMedia.Slave(IMedia.Slave.Type.Subtitle, 4, url))
        }
        mp.media = media
        media.release()
        mp.play()
    }

    LaunchedEffect(mp) {
        while (true) {
            delay(5_000)
            if (mp.isPlaying && length > 0) onProgress(mp.time, length)
        }
    }

    LaunchedEffect(poke, playing) {
        if (playing) {
            delay(4_000)
            overlay = false
        }
    }

    BackHandler { if (overlay && playing) overlay = false else onExit() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.nativeKeyEvent.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                    AndroidKeyEvent.KEYCODE_SPACE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    AndroidKeyEvent.KEYCODE_HEADSETHOOK -> {
                        if (mp.isPlaying) mp.pause() else mp.play(); show(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { mp.play(); show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { mp.pause(); show(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-10_000); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT, AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); true }
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_MENU,
                    AndroidKeyEvent.KEYCODE_CAPTIONS -> { openTracks(); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN, AndroidKeyEvent.KEYCODE_INFO -> { show(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT, AndroidKeyEvent.KEYCODE_CHANNEL_UP -> { onNext(); true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS, AndroidKeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        if (mp.time > 5_000) mp.setTime(0) else onPrevious(); true
                    }
                    AndroidKeyEvent.KEYCODE_MEDIA_STOP -> { onExit(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).also {
                    // TextureView: composes inside the normal view hierarchy, so the Compose overlay draws on
                    // top (a SurfaceView here rendered black and hid the overlay on the Streamer).
                    mp.attachViews(it, null, /* subtitles = */ true, /* textureView = */ true)
                    viewReady = true
                }
            },
        )

        AnimatedVisibility(
            visible = overlay || buffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name.substringBeforeLast('.'),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        when { buffering -> "Buffering…"; playing -> "VLC"; else -> "Paused · VLC" },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (length > 0) (time.toFloat() / length).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Text("${formatDuration(time)} / ${formatDuration(length)}", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "◀ −10s   ▶ +30s   OK play/pause   ▲ audio & subtitles",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    trackMenu?.let { choices ->
        TrackDialog(
            choices = choices,
            onDismiss = { trackMenu = null },
            onPick = { c ->
                if (c.kind == "Audio") mp.setAudioTrack(c.id) else mp.setSpuTrack(c.id)
                trackMenu = null
            },
        )
    }

    LaunchedEffect(trackMenu) {
        if (trackMenu == null) runCatching { focus.requestFocus() }
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
                    item(key = "${c.kind}-${c.id}") {
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
