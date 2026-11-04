package dev.atv.nasplayer.playback

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
    val hasNext = session.index + 1 < request.items.size

    // Expose our folder playlist through the standard next/previous controls.
    val controlled = remember(player) {
        object : ForwardingPlayer(player) {
            private val extra = Player.Commands.Builder()
                .addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .apply { if (hasNext) addAll(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) }
                .build()

            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon().addAll(extra).build()

            override fun isCommandAvailable(command: Int): Boolean =
                extra.contains(command) || super.isCommandAvailable(command)

            override fun seekToNext() = onNext()
            override fun seekToNextMediaItem() = onNext()
            override fun seekToPrevious() {
                if (player.currentPosition > 5_000) player.seekTo(0) else onPrevious()
            }
            override fun seekToPreviousMediaItem() = onPrevious()
        }
    }

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
        var handedOff = false
        var everReady = false
        val recorder = PlaybackRecorder(server.name, joinPath(request.folder, name), Engine.EXO)
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

    BackHandler {
        val v = viewRef[0]
        if (v != null && v.isControllerFullyVisible) v.hideController() else onExit()
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_FIT
                this.player = controlled
                useController = true
                controllerShowTimeoutMs = 4_000
                setShowSubtitleButton(true)
                setShowNextButton(true)
                setShowPreviousButton(true)
                setShowFastForwardButton(true)
                setShowRewindButton(true)
                keepScreenOn = true
                isFocusable = true
                isFocusableInTouchMode = true
                viewRef[0] = this
                post { requestFocus() }
            }
        },
    )
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
