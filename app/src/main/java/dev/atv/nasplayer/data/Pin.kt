package dev.atv.nasplayer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Checks the per-source PINs ([ServerConfig.pinHash]). Entering a source's PIN unlocks that source
 * until the app leaves the screen (see [dev.atv.nasplayer.NasPlayerApp]).
 *
 * ponytail: salted SHA-256 of a 4-digit PIN. It only keeps the PIN out of plain sight in app-private
 * storage. Anyone who can read that storage could try all 10,000 PINs, but they could also clear the data.
 */
object Pin {
    const val LENGTH = 4
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 30_000L

    /** Ids of sources unlocked this visit. Replaced, never mutated, so it works as a Compose key. */
    var unlocked by mutableStateOf(emptySet<String>())
    // ponytail: one miss counter for all sources (persisted in Settings), so switching sources doesn't buy more tries.

    fun needed(server: ServerConfig): Boolean = server.locked && server.id !in unlocked

    fun unlock(id: String) { unlocked = unlocked + id }

    /** Seconds left before another try is allowed, or 0. */
    fun waitSeconds(now: Long = System.currentTimeMillis()): Long = ((Settings.pinLockedUntil - now + 999) / 1000).coerceAtLeast(0)

    fun check(server: ServerConfig, pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val ok = pinMatches(server.pinHash, pin)
        if (ok) { Settings.pinMisses = 0; unlock(server.id) }
        else if (Settings.pinMisses + 1 >= MAX_TRIES) { Settings.pinMisses = 0; Settings.pinLockedUntil = System.currentTimeMillis() + LOCKOUT_MS }
        else Settings.pinMisses++
        return ok
    }
}

/** "salt:sha256(salt + pin)" with a fresh random salt. */
internal fun hashPin(pin: String, salt: String = ByteArray(16).also(SecureRandom()::nextBytes).hex()): String =
    "$salt:${MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).hex()}"

internal fun pinMatches(stored: String, pin: String): Boolean {
    val salt = stored.substringBefore(':', "").ifEmpty { return false }
    return MessageDigest.isEqual(hashPin(pin, salt).toByteArray(), stored.toByteArray())
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
