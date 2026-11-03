package dev.atv.nasplayer.playback

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import dev.atv.nasplayer.source.MediaTypes

enum class Engine { EXO, VLC }

/**
 * Engine selection, modelled on how Jellyfin and Kodi split the work:
 * ExoPlayer (hardware decoding, HDR, audio passthrough, FFmpeg for DTS/TrueHD/etc.) first,
 * libVLC for containers ExoPlayer has no extractor for, and as an automatic fallback.
 */
object EngineChooser {
    /** Containers / codecs ExoPlayer cannot open at all. */
    private val vlcOnly = setOf(
        "wmv", "asf", "wma", "wtv", "dvr-ms", "rm", "rmvb", "ra", "ram", "ogm", "ape", "wv", "mpc",
        "dv", "nsv", "iso", "vob", "ifo", "divx", "xvid", "tak", "tta", "dts", "mlp", "thd",
        "ac3", "eac3", "spx", "voc", "au", "mod", "xm", "s3m", "it",
    )

    fun initialEngine(fileName: String, preference: EnginePreference): Engine = when (preference) {
        EnginePreference.EXO -> Engine.EXO
        EnginePreference.VLC -> Engine.VLC
        EnginePreference.AUTO -> if (MediaTypes.extension(fileName) in vlcOnly) Engine.VLC else Engine.EXO
    }

    /** True when the failure is about the media format rather than the network, so VLC may succeed. */
    fun shouldFallBack(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        PlaybackException.ERROR_CODE_UNSPECIFIED -> true
        else -> false
    }

    /**
     * ExoPlayer "plays" files whose video or audio it can't decode by silently skipping that track,
     * and its extractors drop streams they don't recognise at all (e.g. ProRes in MOV) — so a video
     * file that shows up with no video track is also a reason to hand off.
     */
    fun hasUnplayableTracks(tracks: Tracks, expectVideo: Boolean): Boolean {
        if (expectVideo && !tracks.containsType(C.TRACK_TYPE_VIDEO)) return true
        val badVideo = tracks.containsType(C.TRACK_TYPE_VIDEO) && !tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
        val badAudio = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
        return badVideo || badAudio
    }
}

enum class EnginePreference(val label: String) {
    AUTO("Automatic (ExoPlayer, VLC fallback)"),
    EXO("Always ExoPlayer"),
    VLC("Always VLC"),
}
