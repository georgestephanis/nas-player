package dev.atv.nasplayer

import android.app.Application
import dev.atv.nasplayer.data.ResumeStore
import dev.atv.nasplayer.data.ServerRepository
import dev.atv.nasplayer.data.Settings

class NasPlayerApp : Application(), coil3.SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        dev.atv.nasplayer.source.UsbDrives.init(this)
        ServerRepository.init(this)
        ResumeStore.init(this)
        Settings.init(this)
        dev.atv.nasplayer.playback.PlaybackLog.init(this)
        // Relock PIN-protected sources once none of the app's screens is showing (Home button, screen off).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(a: android.app.Activity) { started++ }
            override fun onActivityStopped(a: android.app.Activity) { if (--started == 0) dev.atv.nasplayer.data.Pin.unlocked = emptySet() }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }

    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .components {
                add(dev.atv.nasplayer.images.ThumbnailFetcher.Factory(context))
                add(dev.atv.nasplayer.images.FullImageFetcher.Factory())
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory()) else add(coil3.gif.GifDecoder.Factory())
            }
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .build()
}
