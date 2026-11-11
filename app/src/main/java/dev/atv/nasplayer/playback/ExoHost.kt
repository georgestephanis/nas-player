package dev.atv.nasplayer.playback

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import dev.atv.nasplayer.PlaybackSession
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.joinPath
import dev.atv.nasplayer.source.MediaBrowser
import kotlinx.coroutines.delay

@UnstableApi
@Composable
fun ExoHost(
    server: ServerConfig,
    request: PlaybackRequest,
    session: PlaybackSession,
    onProgress: (Long, Long) -> Unit,
    onEnded: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onFallback: (positionMs: Long, reason: String) -> Unit,
    onError: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember(session) { ExoFactory.create(context) }
    val viewRef = remember(session) { arrayOfNulls<PlayerView>(1) }
    val recorder = remember(session) { PlaybackRecorder(server.name, joinPath(request.folder, request.items[session.index]), Engine.EXO) }
    var handedOff by remember(session) { mutableStateOf(false) }

    var position by remember(session) { mutableLongStateOf(session.startMs) }
    var duration by remember(session) { mutableLongStateOf(0L) }
    var playing by remember(session) { mutableStateOf(false) }
    var buffering by remember(session) { mutableStateOf(true) }

    DisposableEffect(player) {
        val browser = MediaBrowser.forServer(server)
        val name = request.items[session.index]
        val subs = Subtitles.sidecarsFor(name, request.siblings).map { sub ->
            MediaItem.SubtitleConfiguration.Builder(browser.playbackUri(joinPath(request.folder, sub.name)))
                .setMimeType(sub.mimeType)
                .setLanguage(sub.language)
                .setLabel(sub.language?.uppercase() ?: sub.name)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(browser.playbackUri(joinPath(request.folder, name)))
            .setSubtitleConfigurations(subs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name.substringBeforeLast('.')).build())
            .build()

        var tracksChecked = false
        var everReady = false
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) = recorder.track("video", TrackInfo("video", format.sampleMimeType ?: "?", describeFormat(format)))

            override fun onAudioInputFormatChanged(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format,
                decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
            ) = recorder.track("audio", TrackInfo("audio", format.sampleMimeType ?: "?", describeFormat(format)))

            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) = recorder.decoder("video", decoderName)

            override fun onAudioDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
            ) = recorder.decoder("audio", decoderName)
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) everReady = true
                if (state == Player.STATE_BUFFERING && everReady) recorder.rebuffers++
                if (state == Player.STATE_ENDED && !handedOff) {
                    handedOff = true
                    if (player.duration > 0) onProgress(player.duration, player.duration)
                    onEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (handedOff) return
                handedOff = true
                val pos = player.currentPosition.coerceAtLeast(session.startMs)
                val reason = error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
                if (EngineChooser.shouldFallBack(error)) {
                    recorder.finish(Outcome.FELL_BACK, reason + (error.cause?.message?.let { ": $it" } ?: ""))
                    onFallback(pos, reason)
                } else {
                    recorder.finish(Outcome.FAILED, describe(error))
                    onError(describe(error))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracksChecked || tracks.isEmpty || handedOff) return
                tracksChecked = true
                recordTracks(recorder, tracks)
                val expectVideo = dev.atv.nasplayer.source.MediaTypes.kindOf(name) == dev.atv.nasplayer.source.MediaKind.VIDEO
                if (EngineChooser.hasUnplayableTracks(tracks, expectVideo)) {
                    handedOff = true
                    val why = if (expectVideo && !tracks.containsType(androidx.media3.common.C.TRACK_TYPE_VIDEO)) {
                        "no video track recognised by ExoPlayer"
                    } else "unsupported codec"
                    recorder.finish(Outcome.FELL_BACK, why)
                    onFallback(player.currentPosition.coerceAtLeast(session.startMs), "unsupported codec")
                }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        if (dev.atv.nasplayer.BuildConfig.DEBUG) player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("NasExo"))
        player.setMediaItem(item, session.startMs)
        player.prepare()
        player.playWhenReady = true

        onDispose {
            when {
                handedOff && player.playbackState != Player.STATE_ENDED -> Unit // already recorded
                everReady -> recorder.finish(Outcome.PLAYED)
                else -> recorder.finish(Outcome.FAILED, "stopped while still loading")
            }
            player.removeListener(listener)
            if (!handedOff && player.duration > 0) onProgress(player.currentPosition, player.duration)
            viewRef[0]?.player = null
            player.release()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying && player.duration > 0) onProgress(player.currentPosition, player.duration)
        }
    }

    // ExoPlayer reports no error when a stream's video stops decoding: the audio just carries on over a
    // frozen picture. Hand those to VLC.
    LaunchedEffect(player) {
        val stall = VideoStallCheck()
        while (!handedOff) {
            delay(1_000)
            if (stall.tick(player.isPlaying, player.videoDecoderCounters?.renderedOutputBufferCount)) {
                handedOff = true
                recorder.finish(Outcome.FELL_BACK, "video stopped rendering")
                onFallback(player.currentPosition.coerceAtLeast(session.startMs), "video stopped")
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            buffering = player.playbackState == Player.STATE_BUFFERING
            playing = player.playWhenReady
            delay(250)
        }
    }

    // Music: show embedded cover art (ExoPlayer reads it from tags), falling back to folder art.
    val isAudio = dev.atv.nasplayer.source.MediaTypes.kindOf(request.items[session.index]) == dev.atv.nasplayer.source.MediaKind.AUDIO
    LaunchedEffect(session) {
        if (!isAudio) return@LaunchedEffect
        val path = joinPath(request.folder, request.items[session.index])
        val req = coil3.request.ImageRequest.Builder(context)
            .data(dev.atv.nasplayer.images.Thumb(server.id, path, dev.atv.nasplayer.source.MediaKind.AUDIO, -1, 0))
            .build()
        val img = (coil3.SingletonImageLoader.get(context).execute(req) as? coil3.request.SuccessResult)?.image
        if (img != null) viewRef[0]?.defaultArtwork = android.graphics.drawable.BitmapDrawable(context.resources, (img as coil3.BitmapImage).bitmap)
    }

    PlayerControls(
        title = request.items[session.index].substringBeforeLast('.'),
        engineLabel = "",
        positionMs = position,
        durationMs = duration,
        playing = playing,
        buffering = buffering,
        keepBarVisible = isAudio,
        hasNext = session.index + 1 < request.items.size,
        setPlaying = { player.playWhenReady = it; playing = it },
        seekBy = { delta ->
            // currentPosition already reflects a pending seek, so quick presses add up.
            val end = if (player.duration > 0) player.duration - 1_000 else Long.MAX_VALUE
            val target = (player.currentPosition + delta).coerceIn(0, end)
            if (!player.isCurrentMediaItemSeekable) {
                // Fragmented MP4 with no sidx (and other index-less files): ExoPlayer turns every seek
                // into a seek to 0. VLC can seek these, so hand off at the target position.
                if (!handedOff) {
                    handedOff = true
                    recorder.finish(Outcome.FELL_BACK, "not seekable in ExoPlayer")
                    onFallback(target, "not seekable")
                }
            } else {
                player.seekTo(target)
                position = player.currentPosition
            }
        },
        onNext = onNext,
        onPrevious = { if (player.currentPosition > 5_000) player.seekTo(0) else onPrevious() },
        tracks = { exoTrackChoices(player) },
        onExit = onExit,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    viewRef[0] = this
                }
            },
        )
    }
}

private fun exoTrackChoices(player: Player): List<TrackChoice> {
    fun list(type: Int, kind: String) = player.currentTracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
            val f = group.getTrackFormat(i)
            val name = f.label ?: listOfNotNull(f.language?.takeIf { it != "und" }, f.sampleMimeType?.substringAfter('/')).joinToString(" · ")
            TrackChoice(kind, name, group.isTrackSelected(i)) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    val text = list(C.TRACK_TYPE_TEXT, "Subtitles")
    val off = TrackChoice("Subtitles", "Off", text.none { it.selected }) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
    return list(C.TRACK_TYPE_AUDIO, "Audio") + if (text.isEmpty()) emptyList() else listOf(off) + text
}

internal fun describeFormat(f: androidx.media3.common.Format): String = buildList {
    if (f.width > 0) add("${f.width}x${f.height}")
    if (f.channelCount > 0) add("${f.channelCount}ch")
    if (f.sampleRate > 0) add("${f.sampleRate}Hz")
    f.codecs?.let { add(it) }
    f.language?.takeIf { it != "und" }?.let { add(it) }
}.joinToString(" ")

private fun recordTracks(recorder: PlaybackRecorder, tracks: Tracks) {
    tracks.groups.forEachIndexed { g, group ->
        val type = when (group.type) {
            androidx.media3.common.C.TRACK_TYPE_VIDEO -> "video"
            androidx.media3.common.C.TRACK_TYPE_AUDIO -> "audio"
            androidx.media3.common.C.TRACK_TYPE_TEXT -> "text"
            else -> return@forEachIndexed
        }
        for (i in 0 until group.length) {
            val f = group.getTrackFormat(i)
            f.containerMimeType?.let { recorder.container = it.substringAfter('/') }
            val supported = group.isTrackSupported(i)
            // Supported tracks are logged once their decoder initialises; only unsupported ones here
            // (plus text, which has no decoder callback).
            if (!supported || type == "text") {
                recorder.track("$type-$g-$i", TrackInfo(type, f.sampleMimeType ?: "?", describeFormat(f), supported = supported))
            }
        }
    }
}

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Couldn't reach the server. Check that the NAS is awake and on the network."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The server stopped responding."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file is no longer there. It may have been moved, or its drive removed."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "This account doesn't have permission to read the file."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the request (${e.cause?.message ?: "HTTP error"})."
    else -> (e.cause?.message ?: e.message ?: e.errorCodeName)
}
