package dev.atv.nasplayer.playback

import androidx.media3.common.MimeTypes
import dev.atv.nasplayer.source.MediaTypes

data class SidecarSubtitle(val name: String, val language: String?, val mimeType: String)

/** Finds "Movie.srt", "Movie.en.srt", "Movie.eng.forced.ass" next to "Movie.mkv". */
object Subtitles {
    fun sidecarsFor(videoName: String, siblingNames: List<String>): List<SidecarSubtitle> {
        val base = videoName.substringBeforeLast('.').lowercase()
        return siblingNames.mapNotNull { name ->
            val lower = name.lowercase()
            val mime = mimeFor(MediaTypes.extension(name)) ?: return@mapNotNull null
            val stem = lower.substringBeforeLast('.')
            when {
                stem == base -> SidecarSubtitle(name, null, mime)
                stem.startsWith("$base.") -> {
                    val tag = stem.removePrefix("$base.").split('.').firstOrNull { it.length in 2..3 && it != "sdh" }
                    SidecarSubtitle(name, tag, mime)
                }
                else -> null
            }
        }
    }

    private fun mimeFor(ext: String): String? = when (ext) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
}
