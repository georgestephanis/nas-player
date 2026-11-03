package dev.atv.nasplayer.images

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.source.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem
import java.io.IOException

/** Reads a whole photo from the NAS; Coil then decodes it downsampled to the display (GIFs animate). */
class FullImageFetcher(private val data: FullImage) : Fetcher {
    override suspend fun fetch(): FetchResult = withContext(Dispatchers.IO) {
        val server = ServerRepository.get(data.serverId) ?: throw IOException("Unknown server")
        val bytes = RemoteFile.open(server, data.path).use { it.readFully(128L shl 20) }
        SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<FullImage> {
        override fun create(data: FullImage, options: Options, imageLoader: ImageLoader): Fetcher = FullImageFetcher(data)
    }
}
