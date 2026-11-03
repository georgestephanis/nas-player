package dev.atv.nasplayer.images

import dev.atv.nasplayer.source.MediaKind

/** Small preview for a list row. [modifiedMs] and [size] are part of the cache key so edits refresh it. */
data class Thumb(
    val serverId: String,
    val path: String,
    val kind: MediaKind,
    val size: Long,
    val modifiedMs: Long,
    /** Sibling artwork (e.g. "Movie-poster.jpg") to use instead of extracting a frame. */
    val artworkPath: String? = null,
) {
    val cacheKey: String get() = "$serverId|$path|$size|$modifiedMs|${artworkPath.orEmpty()}"
}

/** Full-resolution image for the photo viewer (downsampled to the screen by Coil). */
data class FullImage(val serverId: String, val path: String)
