package dev.atv.nasplayer.source

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Reads a mounted drive by path, which needs [UsbDrives.hasAccess]. On Android 11+ path access goes through
 * MediaStore, so only folders and media files are listed; subtitles and other files are invisible.
 */
class UsbBrowser(override val server: ServerConfig) : MediaBrowser {

    override val rootPath: String = "/"

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val files = File(server.host + path).listFiles()
            ?: throw IOException("Couldn't read this folder. Is the drive still plugged in?")
        val base = path.trimEnd('/')
        sortEntries(
            files.filter { !it.isHidden && it.name !in systemFolders }.map {
                RemoteEntry(it.name, "$base/${it.name}", it.isDirectory, it.length(), it.lastModified())
            }
        )
    }

    override fun playbackUri(path: String): Uri = Uri.fromFile(File(server.host + path))

    private companion object {
        val systemFolders = setOf("LOST.DIR", "System Volume Information", "\$RECYCLE.BIN")
    }
}

/** Mounted removable drives. They come and go, so they are read from the system and never saved as sources. */
object UsbDrives {
    private lateinit var app: Context

    /** Broadcasts after which [list] may have changed. */
    val changes = IntentFilter().apply {
        addAction(Intent.ACTION_MEDIA_MOUNTED)
        addAction(Intent.ACTION_MEDIA_EJECT)
        addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        addAction(Intent.ACTION_MEDIA_REMOVED)
        addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
        addDataScheme("file")
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun list(): List<ServerConfig> = app.getSystemService(StorageManager::class.java).storageVolumes
        .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.uuid != null }
        .map {
            // The id is stable per drive, so resume positions survive replugging.
            ServerConfig(id = "usb-${it.uuid}", name = it.getDescription(app), type = ServerType.USB, host = "/storage/${it.uuid}")
        }

    fun get(id: String): ServerConfig? = if (id.startsWith("usb-")) list().firstOrNull { it.id == id } else null

    private val permissions = if (Build.VERSION.SDK_INT >= 33) {
        arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasAccess(): Boolean = permissions.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** The user comes back to the home screen and opens the drive again. */
    fun requestAccess(activity: Activity) = activity.requestPermissions(permissions, 1)
}
