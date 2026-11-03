package dev.atv.nasplayer.playback

import android.content.Context
import android.content.Intent
import dev.atv.nasplayer.PlayerActivity

/** A folder's worth of playable files; playback starts at [index] and can advance through the rest. */
data class PlaybackRequest(
    val serverId: String,
    val folder: String,
    val items: List<String>,
    val siblings: List<String>,
    val index: Int,
    val forcedEngine: Engine? = null,
) {
    fun intent(context: Context): Intent = Intent(context, PlayerActivity::class.java)
        .putExtra("serverId", serverId)
        .putExtra("folder", folder)
        .putStringArrayListExtra("items", ArrayList(items))
        .putStringArrayListExtra("siblings", ArrayList(siblings))
        .putExtra("index", index)
        .putExtra("engine", forcedEngine?.name)

    companion object {
        fun from(intent: Intent): PlaybackRequest? {
            val serverId = intent.getStringExtra("serverId") ?: return null
            val items = intent.getStringArrayListExtra("items") ?: return null
            return PlaybackRequest(
                serverId = serverId,
                folder = intent.getStringExtra("folder") ?: "/",
                items = items,
                siblings = intent.getStringArrayListExtra("siblings") ?: arrayListOf(),
                index = intent.getIntExtra("index", 0).coerceIn(0, (items.size - 1).coerceAtLeast(0)),
                forcedEngine = intent.getStringExtra("engine")?.let { runCatching { Engine.valueOf(it) }.getOrNull() },
            )
        }
    }
}
