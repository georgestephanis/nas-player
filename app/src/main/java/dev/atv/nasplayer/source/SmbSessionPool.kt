package dev.atv.nasplayer.source

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerRepository
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Keeps one authenticated SMB session per saved server and reconnects transparently. */
object SmbSessionPool {
    private val client: SMBClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withMultiProtocolNegotiate(true)
                .withNegotiatedBufferSize()
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(45, TimeUnit.SECONDS)
                .build()
        )
    }

    private class Entry(val config: ServerConfig, val connection: Connection, val session: Session) {
        val shares = HashMap<String, DiskShare>()
    }

    // Short-lived lock for the map only; slow network work happens under a per-server lock, so one
    // unreachable NAS never stalls another server (or the UI thread calling invalidate()).
    private val entries = HashMap<String, Entry>()
    private val serverLocks = HashMap<String, Any>()

    private fun lockFor(id: String): Any = synchronized(serverLocks) { serverLocks.getOrPut(id) { Any() } }

    fun session(config: ServerConfig): Session = entry(config).session

    fun share(serverId: String, shareName: String): DiskShare {
        val config = ServerRepository.get(serverId) ?: throw IOException("Unknown server")
        return share(config, shareName)
    }

    fun share(config: ServerConfig, shareName: String): DiskShare = synchronized(lockFor(config.id)) {
        val e = entry(config)
        e.shares[shareName.lowercase()]?.takeIf { it.isConnected }?.let { return it }
        val share = e.session.connectShare(shareName) as? DiskShare
            ?: throw IOException("\"$shareName\" is not a file share")
        e.shares[shareName.lowercase()] = share
        share
    }

    /** Drop a cached connection (credentials changed, NAS slept). Never blocks: closing happens off-thread. */
    fun invalidate(serverId: String) {
        val old = synchronized(entries) { entries.remove(serverId) } ?: return
        Thread({ runCatching { old.connection.close(true) } }, "smb-close").apply { isDaemon = true }.start()
    }

    private fun entry(config: ServerConfig): Entry = synchronized(lockFor(config.id)) {
        val serverId = config.id
        val cached = synchronized(entries) { entries[serverId] }
        if (cached != null && cached.config == config && cached.connection.isConnected) return cached
        if (cached != null) invalidate(serverId)

        val t0 = android.os.SystemClock.elapsedRealtime()
        val connection = if (config.port != null) client.connect(config.host, config.port) else client.connect(config.host)
        val auth = when {
            config.username.isBlank() -> AuthenticationContext.guest()
            else -> AuthenticationContext(config.username, config.password.toCharArray(), config.domain.ifBlank { null })
        }
        val session = try {
            connection.authenticate(auth)
        } catch (e: Exception) {
            if (config.username.isBlank()) {
                // Some servers reject "guest" but accept anonymous.
                connection.authenticate(AuthenticationContext.anonymous())
            } else {
                runCatching { connection.close(true) }
                throw e
            }
        }
        android.util.Log.i("NasSmb", "connected+authenticated ${config.host} in ${android.os.SystemClock.elapsedRealtime() - t0}ms (${connection.negotiatedProtocol.dialect})")
        Entry(config, connection, session).also { synchronized(entries) { entries[serverId] = it } }
    }
}
