package dev.atv.nasplayer.playback

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor

@UnstableApi
object ExoFactory {
    fun create(context: Context): ExoPlayer {
        // Prefer hardware / passthrough, but use FFmpeg (DTS, TrueHD, AC3, E-AC3, MLP, ...) when the
        // platform has no decoder for a track.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val extractors = DefaultExtractorsFactory()
            // Seeking in files without an index (some MP3/ADTS/AVI).
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            // Broadcast / DVR transport streams: H.264 without IDR frames or AUD delimiters, Blu-ray DTS.
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS
            )
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Play a track at reduced quality rather than refusing it.
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setPreferredAudioLanguage(java.util.Locale.getDefault().language)
                    .setPreferredTextLanguage(java.util.Locale.getDefault().language)
                    .setSelectUndeterminedTextLanguage(false)
            )
        }

        // Generous buffer on capable boxes (high-bitrate remuxes), but stay well inside the heap of
        // 1.5-2 GB devices like the Chromecast with Google TV.
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val heapMb = am.largeMemoryClass
        val targetBytes = when {
            am.isLowRamDevice || heapMb < 256 -> 48
            heapMb < 512 -> 96
            else -> 192
        } * 1024 * 1024
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, if (targetBytes > 64 * 1024 * 1024) 120_000 else 50_000, 2_000, 4_000)
            .setTargetBufferBytes(targetBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(NasDataSource.Factory(context), extractors))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }
}
