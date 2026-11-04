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
