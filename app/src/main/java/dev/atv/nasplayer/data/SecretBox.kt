package dev.atv.nasplayer.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts saved passwords with a non-exportable AndroidKeyStore key.
 *
 * The system keystore service can hang indefinitely on some TV firmware (observed on a Google TV
 * Streamer: keystore2 calls pending for days until reboot). Every keystore call therefore runs on
 * a dedicated thread with a timeout. If the keystore doesn't answer, values are stored with a
 * "plain:" prefix in app-private storage (sandboxed, backups disabled) and transparently
 * re-encrypted on the next save once the keystore responds again.
 */
object SecretBox {
    private const val TAG = "SecretBox"
    private const val ALIAS = "nasplayer_credentials"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val PLAIN = "plain:"
    private const val TIMEOUT_MS = 2_000L

    private val keystoreThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "keystore").apply { isDaemon = true }
    }

    @Volatile private var cached: SecretKey? = null
    @Volatile private var unavailableUntil = 0L

    /** Runs [block] on the keystore thread. Null if it failed, or didn't answer in time. */
    private fun <T> onKeystore(block: () -> T): T? {
        if (System.currentTimeMillis() < unavailableUntil) return null
        val task = keystoreThread.submit<T> { block() }
        return try {
            task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Don't stack up more blocked calls behind a hung keystore for a while.
            task.cancel(true)
            unavailableUntil = System.currentTimeMillis() + 60_000
            Log.w(TAG, "Android keystore not responding", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Android keystore call failed", e)
            null
        }
    }

    private fun key(): SecretKey? = cached ?: onKeystore { loadOrCreate() }?.also { cached = it }

    private fun loadOrCreate(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /** Call off the main thread: may block for up to [TIMEOUT_MS]. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val out = key()?.let { key ->
            onKeystore {
                val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
                cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            }
        } ?: return PLAIN + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Call off the main thread: may block for up to [TIMEOUT_MS]. Null means there is a password but it can't be read. */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return ""
        return try {
            if (encoded.startsWith(PLAIN)) {
                return String(Base64.decode(encoded.removePrefix(PLAIN), Base64.NO_WRAP), Charsets.UTF_8)
            }
            val key = key() ?: return null
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            onKeystore {
                val cipher = Cipher.getInstance(TRANSFORM).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, IV_LEN))
                }
                String(cipher.doFinal(bytes, IV_LEN, bytes.size - IV_LEN), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
    }
}
