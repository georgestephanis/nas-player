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

    private val entries = HashMap<String, Entry>()

    @Synchronized
    fun session(config: ServerConfig): Session = entry(config).session

    @Synchronized
    fun share(serverId: String, shareName: String): DiskShare {
        val config = ServerRepository.get(serverId) ?: throw IOException("Unknown server")
        return share(config, shareName)
    }

    @Synchronized
    fun share(config: ServerConfig, shareName: String): DiskShare {
        val e = entry(config)
        e.shares[shareName.lowercase()]?.takeIf { it.isConnected }?.let { return it }
        val share = e.session.connectShare(shareName) as? DiskShare
            ?: throw IOException("\"$shareName\" is not a file share")
        e.shares[shareName.lowercase()] = share
        return share
    }

    /** Drop a cached connection, e.g. after credentials changed or the NAS went to sleep. */
    @Synchronized
    fun invalidate(serverId: String) {
        entries.remove(serverId)?.let { runCatching { it.connection.close(true) } }
    }

    private fun entry(config: ServerConfig): Entry {
        val serverId = config.id
        val cached = entries[serverId]
        if (cached != null && cached.config == config && cached.connection.isConnected) return cached
        cached?.let { runCatching { it.connection.close(true) } }

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
        return Entry(config, connection, session).also { entries[serverId] = it }
    }
}
