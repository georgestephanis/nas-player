package dev.atv.nasplayer.playback

import dev.atv.nasplayer.BuildConfig

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.atv.nasplayer.PlaybackSession
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.joinPath
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
            ).apply {
                // VLC's error messages can include the stream URL, and that carries the SMB/WebDAV
                // password (VlcMedia). Logcat only reaches adb and bug reports, but keep it out of
                // release builds anyway.
                if (!BuildConfig.DEBUG) add("--quiet")
            },
        ).also { instance = it }
    }
}

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

    fun seekBy(deltaMs: Long) {
        val target = (time + deltaMs).coerceIn(0, if (length > 0) length - 1_000 else Long.MAX_VALUE)
        mp.setTime(target)
        time = target
    }
    fun tracks(): List<TrackChoice> =
        mp.audioTracks.orEmpty().map { t -> TrackChoice("Audio", t.name, t.id == mp.audioTrack) { mp.setAudioTrack(t.id) } } +
            mp.spuTracks.orEmpty().map { t -> TrackChoice("Subtitles", t.name, t.id == mp.spuTrack) { mp.setSpuTrack(t.id) } }

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
        media.setHWDecoderEnabled(!session.softwareDecode, false)
        // These files have no index, so a seek lands between keyframes; drop the frames decoded from
        // missing references (grey smear) and hold the last good one until the next keyframe.
        if (session.softwareDecode) media.addOption(":no-avcodec-corrupted")
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

    PlayerControls(
        title = name.substringBeforeLast('.'),
        engineLabel = "VLC",
        positionMs = time,
        durationMs = length,
        playing = playing,
        buffering = buffering,
        keepBarVisible = false,
        hasNext = session.index + 1 < request.items.size,
        setPlaying = { if (it) mp.play() else mp.pause() },
        seekBy = ::seekBy,
        onNext = onNext,
        onPrevious = { if (mp.time > 5_000) mp.setTime(0) else onPrevious() },
        tracks = ::tracks,
        onExit = onExit,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                VLCVideoLayout(ctx).also {
                    // TextureView: composes inside the normal view hierarchy, so the Compose bar draws on
                    // top (a SurfaceView here rendered black and hid the bar on the Streamer).
                    mp.attachViews(it, null, /* subtitles = */ true, /* textureView = */ true)
                    viewReady = true
                }
            },
        )
    }
}
