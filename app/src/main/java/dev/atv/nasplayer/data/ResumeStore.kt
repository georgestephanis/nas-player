package dev.atv.nasplayer.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class ResumeEntry(
    val serverId: String,
    val folder: String,
    val name: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val path: String get() = joinPath(folder, name)
    val watched: Boolean get() = durationMs > 0 && positionMs >= durationMs * WATCHED_FRACTION
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        const val WATCHED_FRACTION = 0.95
    }
}

/** Remembers playback position per file so long videos can be resumed. */
object ResumeStore {
    private lateinit var prefs: SharedPreferences
    private val _version = MutableStateFlow(0)
    /** Bumps whenever anything changes, so UI can recompose. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("resume", Context.MODE_PRIVATE)
    }

    private fun key(serverId: String, path: String) = "$serverId|$path"

    fun get(serverId: String, path: String): ResumeEntry? =
        prefs.getString(key(serverId, path), null)?.let { parse(it) }

    fun save(serverId: String, folder: String, name: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        val o = JSONObject()
            .put("serverId", serverId).put("folder", folder).put("name", name)
            .put("pos", positionMs).put("dur", durationMs).put("at", System.currentTimeMillis())
        prefs.edit().putString(key(serverId, joinPath(folder, name)), o.toString()).apply()
        _version.value++
    }

    fun forget(serverId: String, path: String) {
        prefs.edit().remove(key(serverId, path)).apply()
        _version.value++
    }

    fun clearServer(serverId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$serverId|") }.forEach { editor.remove(it) }
        editor.apply()
        _version.value++
    }

    /** Partially watched items, most recent first. */
    fun continueWatching(limit: Int = 15): List<ResumeEntry> =
        prefs.all.values.mapNotNull { (it as? String)?.let(::parse) }
            .filter { it.positionMs > 0 && !it.watched }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    private fun parse(raw: String): ResumeEntry? = try {
        val o = JSONObject(raw)
        ResumeEntry(
            o.getString("serverId"), o.getString("folder"), o.getString("name"),
            o.getLong("pos"), o.getLong("dur"), o.getLong("at"),
        )
    } catch (e: Exception) {
        null
    }
}

fun joinPath(folder: String, name: String): String =
    if (folder.endsWith("/")) folder + name else "$folder/$name"

fun parentPath(path: String): String {
    val trimmed = path.trimEnd('/')
    val idx = trimmed.lastIndexOf('/')
    return if (idx <= 0) "/" else trimmed.substring(0, idx)
}
