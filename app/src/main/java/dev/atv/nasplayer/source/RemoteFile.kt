package dev.atv.nasplayer.source

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerType
import okhttp3.Request
import java.io.Closeable
import java.io.IOException

/** Random-access read handle on a remote file (used for thumbnails, cover art and images). */
interface RemoteFile : Closeable {
    val size: Long
    /** Reads up to [len] bytes at [position]; returns bytes read or -1 at EOF. */
    fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int

    fun readFully(limit: Long = 64L * 1024 * 1024): ByteArray {
        if (size > limit) throw IOException("File too large (${size / 1_000_000} MB)")
        val out = ByteArray(size.toInt())
        var pos = 0
        while (pos < out.size) {
            val n = readAt(pos.toLong(), out, pos, out.size - pos)
            if (n <= 0) break
            pos += n
        }
        return if (pos == out.size) out else out.copyOf(pos)
    }

    companion object {
        fun open(server: ServerConfig, path: String): RemoteFile {
            val raw = when (server.type) {
                ServerType.SMB -> SmbRemoteFile(server, path)
                ServerType.WEBDAV -> DavRemoteFile(server, path)
            }
            return BlockCachedFile(raw)
        }
    }
}

private class SmbRemoteFile(server: ServerConfig, path: String) : RemoteFile {
    private val file = run {
        val (share, rel) = SmbBrowser.splitSmbPath(path)
        SmbSessionPool.share(server, share ?: throw IOException("No share")).openFile(
            rel, setOf(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
    }
    override val size: Long = file.fileInformation.standardInformation.endOfFile
    override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        return file.read(buf, position, off, minOf(len.toLong(), size - position).toInt())
    }
    override fun close() = runCatching { file.close() }.let { }
}

private class DavRemoteFile(private val server: ServerConfig, path: String) : RemoteFile {
    private val url = WebDavBrowser.resolve(server, path)
    private val client = WebDavBrowser.client(server)
    override val size: Long = client.newCall(Request.Builder().url(url).head().build()).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        it.header("Content-Length")?.toLongOrNull() ?: throw IOException("Unknown length")
    }
    override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        val end = minOf(position + len, size) - 1
        val req = Request.Builder().url(url).header("Range", "bytes=$position-$end").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val source = resp.body?.source() ?: return -1
            if (resp.code == 200 && position > 0) source.skip(position) // server ignored Range
            val stream = source.inputStream()
            var total = 0
            val want = (end - position + 1).toInt()
            while (total < want) {
                val n = stream.read(buf, off + total, want - total)
                if (n < 0) break
                total += n
            }
            return if (total == 0) -1 else total
        }
    }
    override fun close() {}
}

/**
 * MediaMetadataRetriever issues many small, scattered reads; serve them from 256 KiB blocks so
 * each round trip to the NAS fetches something useful.
 */
private class BlockCachedFile(private val inner: RemoteFile) : RemoteFile {
    private val blockSize = 256 * 1024
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 24
    }
    override val size: Long get() = inner.size

    @Synchronized
    override fun readAt(position: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (position >= size) return -1
        var copied = 0
        var pos = position
        while (copied < len && pos < size) {
            val idx = pos / blockSize
            val block = blocks.getOrPut(idx) {
                val start = idx * blockSize
                val n = minOf(blockSize.toLong(), size - start).toInt()
                val b = ByteArray(n)
                var got = 0
                while (got < n) {
                    val r = inner.readAt(start + got, b, got, n - got)
                    if (r <= 0) break
                    got += r
                }
                if (got == n) b else b.copyOf(got)
            }
            val inBlock = (pos - idx * blockSize).toInt()
            if (inBlock >= block.size) break
            val n = minOf(len - copied, block.size - inBlock)
            System.arraycopy(block, inBlock, buf, off + copied, n)
            copied += n
            pos += n
        }
        return if (copied == 0) -1 else copied
    }

    override fun readFully(limit: Long): ByteArray = inner.readFully(limit) // bypass the block cache
    override fun close() = inner.close()
}
