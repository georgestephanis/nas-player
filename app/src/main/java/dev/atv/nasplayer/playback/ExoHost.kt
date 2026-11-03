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
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
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
                if (EngineChooser.shouldFallBack(error)) {
                    onFallback(pos, error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' '))
                } else {
                    onError(describe(error))
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracksChecked || tracks.isEmpty || handedOff) return
                tracksChecked = true
                if (EngineChooser.hasUnplayableTracks(tracks)) {
                    handedOff = true
                    onFallback(player.currentPosition.coerceAtLeast(session.startMs), "unsupported codec")
                }
            }
        }
        player.addListener(listener)
        player.setMediaItem(item, session.startMs)
        player.prepare()
        player.playWhenReady = true

        onDispose {
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

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
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

private fun describe(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "Couldn't reach the server. Check that the NAS is awake and on the network."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "The server stopped responding."
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The file no longer exists on the server."
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "This account doesn't have permission to read the file."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the request (${e.cause?.message ?: "HTTP error"})."
    else -> (e.cause?.message ?: e.message ?: e.errorCodeName)
}
