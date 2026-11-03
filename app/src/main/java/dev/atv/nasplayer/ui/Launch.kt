package dev.atv.nasplayer.ui

import android.content.Context
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.Settings
import dev.atv.nasplayer.playback.Engine
import dev.atv.nasplayer.playback.PlaybackRequest
import dev.atv.nasplayer.source.MediaKind
import dev.atv.nasplayer.source.RemoteEntry

/** Plays [entry] with the rest of the folder's media of the same kind queued after it. */
fun launchPlayback(
    context: Context,
    server: ServerConfig,
    folder: String,
    folderEntries: List<RemoteEntry>,
    entry: RemoteEntry,
    engine: Engine? = null,
) {
    val sameKind = folderEntries.filter { it.kind == entry.kind && !it.isDirectory }
    val items = sameKind.map { it.name }.ifEmpty { listOf(entry.name) }
    val index = items.indexOf(entry.name).coerceAtLeast(0)
    val request = PlaybackRequest(
        serverId = server.id,
        folder = folder,
        items = items,
        siblings = folderEntries.filter { it.kind == MediaKind.SUBTITLE }.map { it.name },
        index = index,
        forcedEngine = engine,
    )
    context.startActivity(request.intent(context))
}

fun visibleEntries(entries: List<RemoteEntry>): List<RemoteEntry> =
    if (Settings.showAllFiles) entries.filter { it.kind != MediaKind.SUBTITLE }
    else entries.filter { it.kind == MediaKind.FOLDER || it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO }
