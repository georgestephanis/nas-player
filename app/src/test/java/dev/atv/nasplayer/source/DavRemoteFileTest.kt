package dev.atv.nasplayer.source

import com.sun.net.httpserver.HttpServer
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class DavRemoteFileTest {
    /** A server that ignores Range and always sends the whole file, a few bytes at a time. */
    @Test
    fun readsTheRightBytesWhenServerIgnoresRange() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val ranges = java.util.concurrent.CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { x ->
            x.requestHeaders.getFirst("Range")?.let { ranges.add(it) }
            if (x.requestMethod == "HEAD") x.responseHeaders["Content-Length"] = data.size.toString()
            x.sendResponseHeaders(200, if (x.requestMethod == "HEAD") -1 else data.size.toLong())
            if (x.requestMethod != "HEAD") x.responseBody.use { out ->
                for (i in data.indices step 4096) { out.write(data, i, minOf(4096, data.size - i)); out.flush() }
            }
            x.close()
        }
        server.start()
        try {
            val config = ServerConfig(id = "t", name = "t", type = ServerType.WEBDAV, host = "http://127.0.0.1:${server.address.port}")
            RemoteFile.open(config, "/f.bin").use { file ->
                assertEquals(data.size.toLong(), file.size)
                val buf = ByteArray(100)
                assertEquals(100, file.readAt(700_000, buf, 0, 100))
                assertArrayEquals(data.copyOfRange(700_000, 700_100), buf)
                assertTrue(ranges.isNotEmpty())
            }
        } finally {
            server.stop(0)
        }
    }
}
