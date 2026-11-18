package dev.atv.nasplayer.source

import android.net.Uri
import com.hierynomus.msfscc.FileAttributes
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import dev.atv.nasplayer.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbBrowser(override val server: ServerConfig) : MediaBrowser {

    override val rootPath: String = "/" + server.path.trim('/').replace('\\', '/')

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        try {
            doList(path)
        } catch (e: Exception) {
            // Stale connection (NAS slept, Wi-Fi blipped): reconnect once. A missing folder or a
            // refused login is the server's answer; reconnecting won't change it.
            if (!SmbSessionPool.isConnectionError(e)) throw e
            SmbSessionPool.invalidate(server.id)
            doList(path)
        }
    }

    private fun doList(path: String): List<RemoteEntry> {
        val (shareName, rel) = splitSmbPath(path)
        if (shareName == null) return listShares()
        val share = SmbSessionPool.share(server, shareName)
        val base = if (path.endsWith("/")) path.trimEnd('/') else path
        val entries = share.list(rel).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == ".." || name.startsWith(".") || name.startsWith("@")) return@mapNotNull null
            val attrs = info.fileAttributes
            if (attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L) return@mapNotNull null
            if (attrs and FileAttributes.FILE_ATTRIBUTE_SYSTEM.value != 0L) return@mapNotNull null
            val isDir = attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            RemoteEntry(
                name = name,
                path = "$base/$name",
                isDirectory = isDir,
                size = info.endOfFile,
                modifiedMs = info.lastWriteTime.toEpochMillis(),
            )
        }
        return sortEntries(entries)
    }

    private fun listShares(): List<RemoteEntry> {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val session = SmbSessionPool.session(server)
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).shares1
            // type 0 = disk share; hide administrative shares like IPC$, ADMIN$, C$.
            .filter { (it.type and 0xFFFF) == 0 && !it.netName.endsWith("$") }
            .map { RemoteEntry(name = it.netName, path = "/${it.netName}", isDirectory = true) }
            .let(::sortEntries)
            .also { android.util.Log.i("NasSmb", "share enumeration took ${android.os.SystemClock.elapsedRealtime() - t0}ms") }
    }

    override fun playbackUri(path: String): Uri = smbUri(server.id, path)

    companion object {
        /** "/Share/dir/file" -> ("Share", "dir\\file"); "/" -> (null, ""). */
        fun splitSmbPath(path: String): Pair<String?, String> {
            val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null to ""
            return parts[0] to parts.drop(1).joinToString("\\")
        }

        fun smbUri(serverId: String, path: String): Uri =
            Uri.Builder().scheme("smb").authority(serverId).path(path).build()
    }
}
