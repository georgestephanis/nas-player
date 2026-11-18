package dev.atv.nasplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {
    @Test
    fun matchesOnlyTheSamePin() {
        val stored = hashPin("1234")
        assertTrue(pinMatches(stored, "1234"))
        assertFalse(pinMatches(stored, "1235"))
        assertFalse(pinMatches("", "1234"))
        assertNotEquals(stored, hashPin("1234")) // salted
    }
}
