package dev.atv.nasplayer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredPasswordTest {
    private val encrypt = { p: String -> if (p.isEmpty()) "" else "enc($p)" }

    @Test
    fun keepsAPasswordThatCouldNotBeDecrypted() = assertEquals("old", storedPassword("", "old", encrypt))

    @Test
    fun aNewPasswordReplacesIt() = assertEquals("enc(new)", storedPassword("new", "old", encrypt))

    @Test
    fun noPasswordStaysEmpty() = assertEquals("", storedPassword("", null, encrypt))
}
