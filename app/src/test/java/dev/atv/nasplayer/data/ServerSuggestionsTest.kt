package dev.atv.nasplayer.data

import dev.atv.nasplayer.discovery.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSuggestionsTest {
    private val nas = DiscoveredServer("Vault", "192.168.0.10", 445)
    private fun smb(host: String, user: String = "me", path: String = "", name: String = host) =
        ServerConfig(name = name, type = ServerType.SMB, host = host, username = user, password = "pw", path = path)
    private fun dav(url: String) = ServerConfig(name = url, type = ServerType.WEBDAV, host = url, username = "me", password = "pw")

    @Test
    fun matchesAMachineByNameOrIp() {
        assertTrue(sameServer(ServerType.SMB, "VAULT.local", "vault", emptyList()))
        assertTrue(sameServer(ServerType.SMB, "vault", "192.168.0.10", listOf(nas)))
        assertFalse(sameServer(ServerType.SMB, "vault", "192.168.0.10", emptyList()))
    }

    @Test
    fun loginsAreOnlyOfferedToTheSameServer() {
        val saved = listOf(smb("192.168.0.10"), smb("192.168.0.11", user = "other"))
        assertEquals(listOf("me"), savedLogins(ServerType.SMB, "vault", saved, listOf(nas)).map { it.username })
        assertTrue(savedLogins(ServerType.SMB, "", saved, listOf(nas)).isEmpty())
    }

    @Test
    fun webDavLoginsAreNotOfferedAcrossSchemes() {
        val saved = listOf(dav("https://nas:5006/video"))
        assertEquals(1, savedLogins(ServerType.WEBDAV, "https://nas:5006/music", saved, emptyList()).size)
        assertTrue(savedLogins(ServerType.WEBDAV, "http://nas:5006/video", saved, emptyList()).isEmpty())
    }

    @Test
    fun guestSourcesHaveNoLoginToOffer() {
        val guest = smb("nas", user = "").copy(password = "")
        assertTrue(savedLogins(ServerType.SMB, "nas", listOf(guest), emptyList()).isEmpty())
    }

    @Test
    fun hostsAreListedOncePerMachine() {
        val hosts = hostSuggestions(ServerType.SMB, listOf(smb("vault"), smb("192.168.0.20")), listOf(nas))
        assertEquals(listOf("192.168.0.10", "192.168.0.20"), hosts.map { it.host })
    }

    @Test
    fun knownPathsComeFromTheSameServer() {
        val saved = listOf(smb("nas", path = "Media/TV"), smb("nas", path = "Media/TV"), smb("other", path = "Backup"))
        assertEquals(listOf("Media/TV"), knownPaths(ServerType.SMB, "nas", saved, emptyList()))
    }
}
