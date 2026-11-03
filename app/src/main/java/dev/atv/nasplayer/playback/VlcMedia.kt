package dev.atv.nasplayer.playback

import android.net.Uri
import dev.atv.nasplayer.data.ServerConfig
import dev.atv.nasplayer.data.ServerType
import dev.atv.nasplayer.source.WebDavBrowser

/** Builds the URL + options libVLC needs to stream a file directly with its own SMB/HTTP clients. */
object VlcMedia {
    data class Target(val url: String, val options: List<String>)

    fun target(server: ServerConfig, path: String): Target = when (server.type) {
        ServerType.SMB -> {
            // Credentials go in the URL too, so sidecar subtitle "slaves" can authenticate as well.
            val userInfo = if (server.username.isNotBlank()) {
                Uri.encode(server.username) + ":" + Uri.encode(server.password) + "@"
            } else ""
            val b = Uri.Builder().scheme("smb")
                .encodedAuthority(userInfo + Uri.encode(server.host) + (server.port?.let { ":$it" } ?: ""))
            path.split('/').filter { it.isNotEmpty() }.forEach { b.appendPath(it) }
            val opts = buildList {
                if (server.username.isNotBlank()) {
                    add(":smb-user=${server.username}")
                    add(":smb-pwd=${server.password}")
                    if (server.domain.isNotBlank()) add(":smb-domain=${server.domain}")
                }
            }
            Target(b.build().toString(), opts)
        }
        ServerType.WEBDAV -> {
            val url = WebDavBrowser.resolve(server, path).newBuilder().apply {
                if (server.username.isNotBlank()) {
                    username(server.username)
                    password(server.password)
                }
            }.build()
            Target(url.toString(), emptyList())
        }
    }
}
