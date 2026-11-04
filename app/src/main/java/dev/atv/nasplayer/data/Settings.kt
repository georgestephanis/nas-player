package dev.atv.nasplayer.data

import android.content.Context
import android.content.SharedPreferences
import dev.atv.nasplayer.playback.EnginePreference

/** Scales a folder's whole list (text, icons, thumbnails and spacing). */
enum class ListSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f),
    MEDIUM("Medium", 1f),
    LARGE("Large", 1.25f),
    EXTRA_LARGE("Extra large", 1.6f),
}

object Settings {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var enginePreference: EnginePreference
        get() = runCatching { EnginePreference.valueOf(prefs.getString("engine", null)!!) }.getOrDefault(EnginePreference.AUTO)
        set(v) = prefs.edit().putString("engine", v.name).apply()

    var autoPlayNext: Boolean
        get() = prefs.getBoolean("autoPlayNext", true)
        set(v) = prefs.edit().putBoolean("autoPlayNext", v).apply()

    var sortOrder: dev.atv.nasplayer.source.SortOrder
        get() = runCatching { dev.atv.nasplayer.source.SortOrder.valueOf(prefs.getString("sort", null)!!) }
            .getOrDefault(dev.atv.nasplayer.source.SortOrder.NAME)
        set(v) = prefs.edit().putString("sort", v.name).apply()

    /** Next/previous and auto-play step through videos, music and photos together, in folder order. */
    var mixedQueue: Boolean
        get() = prefs.getBoolean("mixedQueue", true)
        set(v) = prefs.edit().putBoolean("mixedQueue", v).apply()

    var showThumbnails: Boolean
        get() = prefs.getBoolean("thumbnails", true)
        set(v) = prefs.edit().putBoolean("thumbnails", v).apply()

    var listSize: ListSize
        get() = runCatching { ListSize.valueOf(prefs.getString("listSize", null)!!) }.getOrDefault(ListSize.MEDIUM)
        set(v) = prefs.edit().putString("listSize", v.name).apply()

    var slideshowSeconds: Int
        get() = prefs.getInt("slideshowSeconds", 6)
        set(v) = prefs.edit().putInt("slideshowSeconds", v).apply()

    var showAllFiles: Boolean
        get() = prefs.getBoolean("showAllFiles", false)
        set(v) = prefs.edit().putBoolean("showAllFiles", v).apply()
}
