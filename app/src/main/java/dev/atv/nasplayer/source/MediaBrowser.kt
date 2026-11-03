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
        }
    }
}

fun sortEntries(entries: List<RemoteEntry>): List<RemoteEntry> =
    entries.sortedWith(
        compareByDescending<RemoteEntry> { it.isDirectory }
            .thenComparator { a, b -> NaturalOrder.compare(a.name, b.name) }
    )

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
