package dev.atv.nasplayer.data

import dev.atv.nasplayer.discovery.DiscoveredServer

internal data class HostSuggestion(val label: String, val host: String, val port: Int? = null, val name: String = "")

/** SMB: the machine's name without ".local". WebDAV: scheme, host and port, so a login saved for https is never offered to http. */
private fun hostKey(type: ServerType, host: String): String {
    val h = host.trim().lowercase()
    if (type == ServerType.SMB) return h.trimEnd('.').removeSuffix(".local")
    val rest = h.indexOf("://").let { if (it < 0) 0 else it + 3 }
    return h.take(rest) + h.drop(rest).substringBefore('/')
}

/** Same address, or a name and IP that discovery says belong to one machine. */
internal fun sameServer(type: ServerType, a: String, b: String, discovered: List<DiscoveredServer>): Boolean {
    val ka = hostKey(type, a)
    val kb = hostKey(type, b)
    if (ka.isEmpty() || kb.isEmpty()) return false
    return ka == kb || type == ServerType.SMB && discovered.any { d ->
        val same = setOf(hostKey(type, d.host), hostKey(type, d.name))
        ka in same && kb in same
    }
}

/** Machines found on the network, then saved hosts discovery didn't find. */
internal fun hostSuggestions(type: ServerType, saved: List<ServerConfig>, discovered: List<DiscoveredServer>): List<HostSuggestion> {
    val found = if (type == ServerType.SMB) {
        discovered.sortedBy { it.name.lowercase() }.map { HostSuggestion("${it.name} · ${it.host}", it.host, it.port.takeIf { p -> p != 445 && p > 0 }, it.name) }
    } else {
        emptyList()
    }
    val known = saved.filter { it.type == type }
        .filter { s -> found.none { sameServer(type, it.host, s.host, discovered) } }
        .distinctBy { hostKey(type, it.host) }
        .map { HostSuggestion("${it.name} · ${it.host}", it.host, it.port) }
    return found + known
}

/** Saved logins for this server only, so a password is never offered to a different machine. */
internal fun savedLogins(type: ServerType, host: String, saved: List<ServerConfig>, discovered: List<DiscoveredServer>): List<ServerConfig> =
    saved.filter { it.type == type && (it.username.isNotEmpty() || it.password.isNotEmpty()) && sameServer(type, it.host, host, discovered) }
        .distinctBy { Triple(it.username, it.password, it.domain) }

/** Folders other saved sources already use on this server. */
internal fun knownPaths(type: ServerType, host: String, saved: List<ServerConfig>, discovered: List<DiscoveredServer>): List<String> =
    saved.filter { it.type == type && it.path.isNotEmpty() && sameServer(type, it.host, host, discovered) }
        .map { it.path }
        .distinct()
