package dev.atv.nasplayer

import android.app.Application
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.Settings

class NasPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerRepository.init(this)
        ResumeStore.init(this)
        Settings.init(this)
        dev.atv.nasplayer.playback.PlaybackLog.init(this)
    }
}
