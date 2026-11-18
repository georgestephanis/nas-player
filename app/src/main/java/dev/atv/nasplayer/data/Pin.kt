package dev.atv.nasplayer.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The app-wide PIN guarding sources marked [ServerConfig.locked]. Entering it unlocks them all
 * until the app leaves the screen (see [dev.atv.nasplayer.NasPlayerApp]).
 *
 * ponytail: salted SHA-256 of a 4-digit PIN. It only keeps the PIN out of plain sight in app-private
 * storage. Anyone who can read that storage could try all 10,000 PINs, but they could also clear the data.
 */
object Pin {
    const val LENGTH = 4
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 30_000L

    var unlocked by mutableStateOf(false)
    private var misses = 0
    private var lockedUntil = 0L

    val isSet: Boolean get() = Settings.pinHash.isNotEmpty()

    fun needed(server: ServerConfig): Boolean = server.locked && isSet && !unlocked

    /** Seconds left before another try is allowed, or 0. */
    fun waitSeconds(now: Long = System.currentTimeMillis()): Long = ((lockedUntil - now + 999) / 1000).coerceAtLeast(0)

    fun check(pin: String): Boolean {
        if (waitSeconds() > 0) return false
        val ok = pinMatches(Settings.pinHash, pin)
        if (ok) { misses = 0; unlocked = true }
        else if (++misses >= MAX_TRIES) { misses = 0; lockedUntil = System.currentTimeMillis() + LOCKOUT_MS }
        return ok
    }

    /** Sets a new PIN, or removes it with null. */
    fun set(pin: String?) {
        Settings.pinHash = pin?.let(::hashPin) ?: ""
        unlocked = pin != null
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
