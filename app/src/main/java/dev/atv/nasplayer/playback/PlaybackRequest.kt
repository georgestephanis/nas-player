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
    fun intent(context: Context): Intent {
        current = this
        return Intent(context, PlayerActivity::class.java)
    }

    companion object {
        // ponytail: held in memory because a big folder's names don't fit in Intent extras (Binder's ~1 MB limit).
        // If the process dies while the player is in the background, the player closes back to the home screen.
        @Volatile private var current: PlaybackRequest? = null

        fun from(): PlaybackRequest? = current
    }
}
