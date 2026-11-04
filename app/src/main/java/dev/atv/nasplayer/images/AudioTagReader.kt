package dev.atv.nasplayer.images

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.source.RemoteEntry
import dev.atv.nasplayer.source.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

data class AudioTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val year: String? = null,
    val durationMs: Long = -1,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title).put("artist", artist).put("album", album)
        .put("track", track).put("year", year).put("duration", durationMs)

    companion object {
        fun from(o: JSONObject) = AudioTags(
            o.optString("title").ifEmpty { null }, o.optString("artist").ifEmpty { null },
            o.optString("album").ifEmpty { null }, o.optString("track").ifEmpty { null },
            o.optString("year").ifEmpty { null }, o.optLong("duration", -1),
        )
    }
}

/**
 * Reads ID3 / Vorbis / MP4 tags with MediaMetadataRetriever and keeps them beside the thumbnails, so
 * each file is only fetched from the NAS once and "Clear thumbnail cache" clears them too.
 * A failed read is remembered in memory only, so a sleeping NAS doesn't leave a file untagged for good.
 */
object AudioTagReader {
    private const val TAG = "NasTags"
    private val memory = LruCache<String, AudioTags>(2000)

    private fun key(serverId: String, e: RemoteEntry) = "$serverId|${e.path}|${e.size}|${e.modifiedMs}"

    fun cached(serverId: String, entry: RemoteEntry): AudioTags? = memory.get(key(serverId, entry))

    suspend fun read(context: Context, serverId: String, entry: RemoteEntry): AudioTags {
        val key = key(serverId, entry)
        memory.get(key)?.let { return it }
        return ThumbnailFetcher.gate.withPermit {
            withContext(Dispatchers.IO) {
                val file = File(File(context.cacheDir, "thumbs").apply { mkdirs() }, ThumbnailFetcher.sha1(key) + ".tags")
                val tags = runCatching { AudioTags.from(JSONObject(file.readText())) }.getOrNull()
                    ?: withTimeoutOrNull(20_000) { fetch(serverId, entry) }?.also { file.writeText(it.toJson().toString()) }
                (tags ?: AudioTags()).also { memory.put(key, it) }
            }
        }
    }

    private fun fetch(serverId: String, entry: RemoteEntry): AudioTags? {
        val server = ServerRepository.get(serverId) ?: return null
        return runCatching {
            RemoteFile.open(server, entry.path).use { file ->
                withRetriever(file) { mmr ->
                    fun tag(key: Int) = mmr.extractMetadata(key)?.trim()?.ifEmpty { null }
                    AudioTags(
                        title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/'),
                        year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4),
                        durationMs = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1,
                    )
                }
            }
        }.onFailure { Log.d(TAG, "${entry.path}: $it") }.getOrNull()
    }
}
