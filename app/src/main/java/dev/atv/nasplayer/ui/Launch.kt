package dev.atv.nasplayer.ui

import android.content.Context
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.Settings
import dev.atv.nasplayer.playback.Engine
import dev.atv.nasplayer.playback.PlaybackRequest
import dev.atv.nasplayer.source.MediaKind
import dev.atv.nasplayer.source.MediaTypes
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
    val art = dev.atv.nasplayer.source.artworkFiles(folderEntries)
    val ordered = dev.atv.nasplayer.source.sortEntries(folderEntries, Settings.sortOrder)
        .filter { !it.isDirectory && it.name !in art }
    val queue = when {
        entry.kind !in MediaTypes.playable -> listOf(entry)                       // "other" file → VLC, alone
        Settings.mixedQueue -> ordered.filter { it.kind in MediaTypes.playable }   // video, music and photos together
        else -> ordered.filter { it.kind == entry.kind }
    }
    val items = queue.map { it.name }.ifEmpty { listOf(entry.name) }
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

/** What the browser lists: sorted, with subtitles and artwork (shown as thumbnails instead) hidden. */
fun visibleEntries(entries: List<RemoteEntry>): List<RemoteEntry> {
    val art = dev.atv.nasplayer.source.artworkFiles(entries)
    return dev.atv.nasplayer.source.sortEntries(entries, Settings.sortOrder).filter { e ->
        e.name !in art && when {
            Settings.showAllFiles -> e.kind != MediaKind.SUBTITLE
            else -> e.kind == MediaKind.FOLDER || e.kind in MediaTypes.playable
        }
    }
}

/** Sibling artwork to use as a video's thumbnail ("Movie-poster.jpg", "Movie.jpg"...), if present. */
fun artworkFor(entry: RemoteEntry, folderEntries: List<RemoteEntry>): String? {
    if (entry.kind != MediaKind.VIDEO) return null
    val base = entry.name.substringBeforeLast('.').lowercase()
    val images = folderEntries.filter { it.kind == MediaKind.IMAGE }
    listOf("-poster", "", "-thumb", "-landscape", "-fanart").firstNotNullOfOrNull { suf ->
        images.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base + suf }
    }?.let { return it.path }
    // A movie alone in its own folder (Plex/Kodi layout) uses the folder's poster.jpg etc.
    if (folderEntries.count { it.kind == MediaKind.VIDEO } != 1) return null
    return MediaTypes.folderArtNames.firstNotNullOfOrNull { n ->
        images.firstOrNull { it.name.substringBeforeLast('.').equals(n, true) }
    }?.path
}
