package dev.atv.nasplayer.playback

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One stream inside a file, and what (if anything) decoded it. */
data class TrackInfo(
    val type: String,          // video / audio / text
    val codec: String,         // MIME type (ExoPlayer) or codec name (VLC)
    val detail: String = "",   // resolution, channels, language...
    val decoder: String? = null,
    val supported: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().put("type", type).put("codec", codec).put("detail", detail)
        .put("decoder", decoder ?: JSONObject.NULL).put("supported", supported)

    companion object {
        fun from(o: JSONObject) = TrackInfo(
            o.getString("type"), o.getString("codec"), o.optString("detail"),
            if (o.isNull("decoder")) null else o.getString("decoder"), o.optBoolean("supported", true),
        )
    }
}

enum class Outcome { PLAYED, FELL_BACK, FAILED }

/**
 * One playback attempt by one engine. A file that ExoPlayer hands to VLC produces two entries
 * (FELL_BACK, then PLAYED/FAILED), which is exactly the signal for "what codec support is missing".
 */
data class PlaybackLogEntry(
    val at: Long,
    val serverName: String,
    val path: String,
    val container: String,
    val engine: Engine,
    val outcome: Outcome,
    val reason: String?,
    val tracks: List<TrackInfo>,
) {
    val problem: Boolean get() = outcome != Outcome.PLAYED || tracks.any { !it.supported }

    fun toJson(): JSONObject = JSONObject()
        .put("at", at).put("server", serverName).put("path", path).put("container", container)
        .put("engine", engine.name).put("outcome", outcome.name).put("reason", reason ?: JSONObject.NULL)
        .put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })

    companion object {
        fun from(o: JSONObject): PlaybackLogEntry {
            val arr = o.optJSONArray("tracks") ?: JSONArray()
            return PlaybackLogEntry(
                o.getLong("at"), o.optString("server"), o.getString("path"), o.optString("container"),
                Engine.valueOf(o.getString("engine")), Outcome.valueOf(o.getString("outcome")),
                if (o.isNull("reason")) null else o.optString("reason"),
                (0 until arr.length()).map { TrackInfo.from(arr.getJSONObject(it)) },
            )
        }
    }
}

/**
 * Append-only JSON-lines log in app storage (files/playback-log.jsonl).
 * Viewable in Settings → Playback log; on a debug build also via
 * `adb shell run-as dev.atv.nasplayer cat files/playback-log.jsonl`.
 * Contains server display names and file paths only — never hosts or credentials.
 */
object PlaybackLog {
    private const val MAX_ENTRIES = 2000
    private lateinit var file: File
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _entries = MutableStateFlow<List<PlaybackLogEntry>>(emptyList())
    val entries: StateFlow<List<PlaybackLogEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "playback-log.jsonl")
        io.launch {
            _entries.value = runCatching {
                if (!file.exists()) emptyList()
                else file.readLines().mapNotNull { line -> runCatching { PlaybackLogEntry.from(JSONObject(line)) }.getOrNull() }
            }.getOrDefault(emptyList()).sortedByDescending { it.at }
        }
    }

    fun record(entry: PlaybackLogEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        io.launch {
            runCatching {
                file.appendText(entry.toJson().toString() + "\n")
                // Compact occasionally so the file can't grow without bound.
                if (file.length() > 2_000_000) {
                    file.writeText(_entries.value.reversed().joinToString("") { it.toJson().toString() + "\n" })
                }
            }
        }
    }

    fun clear() {
        _entries.value = emptyList()
        io.launch { runCatching { file.delete() } }
    }

    /** Plain-text summary grouped by codec, for the "what do we need to add" question. */
    fun gapSummary(entries: List<PlaybackLogEntry>): List<Pair<String, Int>> =
        entries.filter { it.problem }
            .flatMap { e ->
                val bad = e.tracks.filter { !it.supported }.map { "${it.type}: ${it.codec}" }
                bad.ifEmpty { listOf("${e.container} (${e.engine} ${e.outcome.name.lowercase()}: ${e.reason ?: "unknown"})") }
            }
            .groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
}

/** Accumulates what a single engine run discovers, then writes one entry. */
class PlaybackRecorder(
    private val serverName: String,
    private val path: String,
    private val engine: Engine,
) {
    private val tracks = LinkedHashMap<String, TrackInfo>()
    // ExoPlayer reports decoder initialisation and input format in either order; join them by type.
    private val decoders = HashMap<String, String>()
    var container: String = path.substringAfterLast('.', "").lowercase()
    private var written = false

    fun track(key: String, info: TrackInfo) {
        tracks[key] = info
    }

    fun decoder(type: String, name: String) {
        decoders[type] = name
    }

    fun finish(outcome: Outcome, reason: String? = null) {
        if (written) return
        written = true
        val merged = tracks.values.map { t ->
            when {
                !t.supported || t.decoder != null -> t
                decoders[t.type] != null -> t.copy(decoder = decoders[t.type])
                // Audio ExoPlayer accepted without a decoder callback went to the platform (incl. HDMI passthrough).
                t.type == "audio" && engine == Engine.EXO && outcome == Outcome.PLAYED -> t.copy(decoder = "platform / passthrough")
                else -> t
            }
        }
        PlaybackLog.record(
            PlaybackLogEntry(System.currentTimeMillis(), serverName, path, container, engine, outcome, reason, merged)
        )
    }
}
