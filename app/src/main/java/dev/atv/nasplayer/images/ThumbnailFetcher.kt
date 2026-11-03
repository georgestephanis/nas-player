package dev.atv.nasplayer.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.joinPath
import dev.atv.nasplayer.source.MediaBrowser
import dev.atv.nasplayer.source.MediaKind
import dev.atv.nasplayer.source.MediaTypes
import dev.atv.nasplayer.source.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Produces a ~480px JPEG per item and keeps it in cacheDir/thumbs, so each file is only ever
 * fetched from the NAS once:
 *  - images: decoded with downsampling;
 *  - video: sibling poster art if present, else a frame ~10% in via MediaMetadataRetriever reading
 *    the file over SMB/WebDAV with ranged reads;
 *  - audio: embedded cover art, else folder art;
 *  - folders: folder.jpg / poster.jpg / cover.jpg inside the folder.
 * Failures are cached as empty markers so unthumbnailable files aren't retried on every scroll.
 */
class ThumbnailFetcher(private val data: Thumb, private val context: Context) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        val file = File(dir, sha1(data.cacheKey) + ".jpg")
        if (file.exists()) {
            if (file.length() == 0L) return null // cached "no thumbnail"
            return result(file, DataSource.DISK)
        }
        val bytes = gate.withPermit {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(20_000) { runCatching { generate() }.onFailure { Log.d(TAG, "${data.path}: $it") }.getOrNull() }
            }
        }
        file.writeBytes(bytes ?: ByteArray(0))
        return if (bytes == null) null else result(file, DataSource.NETWORK)
    }

    private fun result(file: File, source: DataSource) = SourceFetchResult(
        source = ImageSource(file.toOkioPath(), FileSystem.SYSTEM),
        mimeType = "image/jpeg",
        dataSource = source,
    )

    private suspend fun generate(): ByteArray? {
        val server = ServerRepository.get(data.serverId) ?: return null
        data.artworkPath?.let { art -> return RemoteFile.open(server, art).use { decodeScaled(it.readFully(32L shl 20)) } }
        return when (data.kind) {
            MediaKind.IMAGE -> RemoteFile.open(server, data.path).use { decodeScaled(it.readFully()) }
            MediaKind.VIDEO -> RemoteFile.open(server, data.path).use { videoFrame(it) }
            MediaKind.AUDIO -> RemoteFile.open(server, data.path).use { embeddedArt(it) }
                ?: folderArt(server, data.path.substringBeforeLast('/'))
            MediaKind.FOLDER -> folderArt(server, data.path)
            else -> null
        }
    }

    private suspend fun folderArt(server: dev.atv.nasplayer.data.ServerConfig, folder: String): ByteArray? {
        val entries = MediaBrowser.forServer(server).list(folder)
        val images = entries.filter { it.kind == MediaKind.IMAGE }
        // Named artwork first; otherwise the first photo, so photo albums get a preview too.
        val pick = MediaTypes.folderArtNames.firstNotNullOfOrNull { n ->
            images.firstOrNull { it.name.substringBeforeLast('.').equals(n, true) }
        } ?: dev.atv.nasplayer.source.sortEntries(images).firstOrNull() ?: return null
        return RemoteFile.open(server, joinPath(folder, pick.name)).use { decodeScaled(it.readFully(32L shl 20)) }
    }

    private fun videoFrame(file: RemoteFile): ByteArray? = withRetriever(file) { mmr ->
        val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        val atUs = (durMs * 1000 / 10).coerceAtMost(180_000_000L)
        val bmp = if (Build.VERSION.SDK_INT >= 27) {
            mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX, MAX)
        } else {
            mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
        bmp?.let { encode(scale(it)) }
    }

    private fun embeddedArt(file: RemoteFile): ByteArray? =
        withRetriever(file) { mmr -> mmr.embeddedPicture?.let { decodeScaled(it) } }

    private fun <T> withRetriever(file: RemoteFile, block: (MediaMetadataRetriever) -> T?): T? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
                    try { file.readAt(position, buffer, offset, size) } catch (e: IOException) { -1 }
                override fun getSize(): Long = file.size
                override fun close() {}
            })
            block(mmr)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun decodeScaled(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX && bounds.outHeight / (sample * 2) >= MAX / 2) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return encode(scale(bmp))
    }

    private fun scale(b: Bitmap): Bitmap {
        val f = MAX.toFloat() / maxOf(b.width, b.height)
        if (f >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true).also { if (it !== b) b.recycle() }
    }

    private fun encode(b: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        b.compress(Bitmap.CompressFormat.JPEG, 82, out); b.recycle(); out.toByteArray()
    }

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbnailFetcher(data, context.applicationContext)
    }

    companion object {
        private const val TAG = "NasThumb"
        private const val MAX = 480
        /** At most two NAS fetches/decodes at once: keeps scrolling smooth and leaves bandwidth for playback. */
        private val gate = Semaphore(2)

        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun clearCache(context: Context) = File(context.cacheDir, "thumbs").deleteRecursively()
    }
}
