package dev.atv.nasplayer.source

import android.net.Uri
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerType

/** Browses a network source and maps its files to URIs that the player's DataSource understands. */
interface MediaBrowser {
    val server: ServerConfig

    /** Top-most path the user may browse to. */
    val rootPath: String

    suspend fun list(path: String): List<RemoteEntry>

    fun playbackUri(path: String): Uri

    companion object {
        fun forServer(server: ServerConfig): MediaBrowser = when (server.type) {
            ServerType.SMB -> SmbBrowser(server)
            ServerType.WEBDAV -> WebDavBrowser(server)
            ServerType.USB -> UsbBrowser(server)
        }
    }
}

enum class SortOrder(val label: String) {
    NAME("Name (A–Z)"),
    NAME_DESC("Name (Z–A)"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    TYPE("Type, then name"),
}

private val byName = Comparator<RemoteEntry> { a, b -> NaturalOrder.compare(a.name, b.name) }

/** Folders always come first; [order] applies within folders and within files. */
fun sortEntries(entries: List<RemoteEntry>, order: SortOrder = SortOrder.NAME): List<RemoteEntry> {
    val within: Comparator<RemoteEntry> = when (order) {
        SortOrder.NAME -> byName
        SortOrder.NAME_DESC -> byName.reversed()
        SortOrder.NEWEST -> compareByDescending<RemoteEntry> { it.modifiedMs }.then(byName)
        SortOrder.OLDEST -> compareBy<RemoteEntry> { it.modifiedMs }.then(byName)
        SortOrder.LARGEST -> compareByDescending<RemoteEntry> { it.size }.then(byName)
        SortOrder.TYPE -> compareBy<RemoteEntry> { it.kind.ordinal }.thenBy { MediaTypes.extension(it.name) }.then(byName)
    }
    return entries.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.then(within))
}

/**
 * Images that are really artwork for something else in the folder — "Movie.jpg"/"Movie-poster.jpg"
 * next to "Movie.mkv", or folder.jpg/cover.jpg beside music — rather than photos to browse.
 */
fun artworkFiles(entries: List<RemoteEntry>): Set<String> {
    val media = entries.filter { it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO }
    // poster.jpg beside "Season 1/" (or beside music/video) is artwork; in a folder of only photos it's a photo.
    if (media.isEmpty() && entries.none { it.isDirectory }) return emptySet()
    val bases = media.map { it.name.substringBeforeLast('.').lowercase() }.toSet()
    val suffixes = listOf("", "-poster", "-thumb", "-fanart", "-banner", "-landscape", "-clearlogo")
    return entries.filter { it.kind == MediaKind.IMAGE }.filter { img ->
        val stem = img.name.substringBeforeLast('.').lowercase()
        stem in MediaTypes.folderArtNames || bases.any { b -> suffixes.any { stem == b + it } }
    }.map { it.name }.toSet()
}

/** "Episode 2" sorts before "Episode 10". */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]; val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (r != 0) return r
        }
        return ca.size - cb.size
    }
}
