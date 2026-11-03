package dev.atv.nasplayer.source

enum class MediaKind { FOLDER, VIDEO, AUDIO, SUBTITLE, OTHER }

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

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    fun kindOf(name: String): MediaKind = when (extension(name)) {
        in video -> MediaKind.VIDEO
        in audio -> MediaKind.AUDIO
        in subtitle -> MediaKind.SUBTITLE
        else -> MediaKind.OTHER
    }
}
