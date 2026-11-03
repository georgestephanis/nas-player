package dev.atv.nasplayer.source

enum class MediaKind { FOLDER, VIDEO, AUDIO, IMAGE, SUBTITLE, OTHER }

data class RemoteEntry(
    val name: String,
    /** Absolute path within the server, always starting with "/". */
    val path: String,
    val isDirectory: Boolean,
    val size: Long = -1,
    val modifiedMs: Long = 0,
) {
    val kind: MediaKind = if (isDirectory) MediaKind.FOLDER else MediaTypes.kindOf(name)
}

object MediaTypes {
    private val video = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm", "mpg", "mpeg",
        "flv", "3gp", "ogv", "vob", "divx", "asf", "hevc",
    )
    private val audio = setOf(
        "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "alac", "aiff", "aif", "mka", "ape", "wv",
    )
    private val subtitle = setOf("srt", "vtt", "ass", "ssa", "ttml")
    private val image = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

    /** Kinds that open in the player screen and take part in next/previous. */
    val playable = setOf(MediaKind.VIDEO, MediaKind.AUDIO, MediaKind.IMAGE)

    /** Folder artwork names used by Kodi, Plex, Jellyfin and music rippers. */
    val folderArtNames = listOf("folder", "poster", "cover", "front", "album", "thumb", "fanart", "default")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): MediaKind = when (extension(name)) {
        in video -> MediaKind.VIDEO
        in audio -> MediaKind.AUDIO
        in subtitle -> MediaKind.SUBTITLE
        in image -> MediaKind.IMAGE
        else -> MediaKind.OTHER
    }
}
