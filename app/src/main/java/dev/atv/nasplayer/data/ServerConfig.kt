package dev.atv.nasplayer.data

import java.util.UUID

enum class ServerType(val label: String) {
    SMB("SMB / Windows share"),
    WEBDAV("WebDAV / HTTP"),
    USB("USB drive"),
}

/**
 * A saved network source.
 *
 * - SMB: [host] is a hostname or IP, [path] optionally pins a share (and sub-folder) such as "Media/Movies".
 * - WebDAV: [host] is the full base URL, e.g. "http://192.168.1.10:5005/video".
 * - USB: never saved, see [dev.atv.nasplayer.source.UsbDrives]; [host] is the mount point, e.g. "/storage/1234-ABCD".
 */
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ServerType,
    val host: String,
    val port: Int? = null,
    val path: String = "",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val allowInsecureTls: Boolean = false,
    /** With [allowInsecureTls]: SHA-256 of the certificate accepted on first connection; any other is refused. */
    val pinnedCert: String = "",
    val showInContinueWatching: Boolean = true,
) {
    val subtitle: String
        get() = when (type) {
            ServerType.SMB -> "smb://" + host + (port?.let { ":$it" } ?: "") +
                (path.trim('/').takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")
            ServerType.WEBDAV -> host
            ServerType.USB -> type.label
        }
}
