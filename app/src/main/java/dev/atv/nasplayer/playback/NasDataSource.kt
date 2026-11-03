package dev.atv.nasplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.source.WebDavBrowser
import java.io.IOException

/**
 * Routes `smb://` to [SmbDataSource], `dav://<serverId>/path` to an authenticated OkHttp source,
 * and anything else to Media3's default source.
 */
@UnstableApi
class NasDataSource(private val context: Context) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val (source, spec) = when (dataSpec.uri.scheme) {
            "smb" -> SmbDataSource() to dataSpec
            "dav" -> {
                val server = ServerRepository.get(dataSpec.uri.host ?: "") ?: throw IOException("Unknown server")
                val url = WebDavBrowser.resolve(server, dataSpec.uri.path ?: "/")
                OkHttpDataSource.Factory(WebDavBrowser.client(server)).createDataSource() to
                    dataSpec.withUri(Uri.parse(url.toString()))
            }
            else -> DefaultDataSource.Factory(context).createDataSource() to dataSpec
        }
        listeners.forEach(source::addTransferListener)
        active = source
        return source.open(spec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = NasDataSource(context.applicationContext)
    }
}
