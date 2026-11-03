package dev.atv.nasplayer

import android.app.Application
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerRepository

class NasPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServerRepository.init(this)
        ResumeStore.init(this)
    }
}
