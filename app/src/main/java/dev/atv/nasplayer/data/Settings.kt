package dev.atv.nasplayer.data

import android.content.Context
import android.content.SharedPreferences
import dev.atv.nasplayer.playback.EnginePreference

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

    var showAllFiles: Boolean
        get() = prefs.getBoolean("showAllFiles", false)
        set(v) = prefs.edit().putBoolean("showAllFiles", v).apply()
}
