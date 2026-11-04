package dev.atv.nasplayer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoStallCheckTest {
    private fun VideoStallCheck.run(vararg ticks: Pair<Boolean, Int?>) = ticks.map { (p, r) -> tick(p, r) }.last()

    @Test fun frozenFramesWhilePlayingTrips() =
        assertTrue(VideoStallCheck(3).run(true to 8, true to 8, true to 8, true to 8))

    @Test fun noFramesEverTrips() =
        assertTrue(VideoStallCheck(3).run(true to 0, true to 0, true to 0, true to 0))

    @Test fun newFramesResetIt() =
        assertFalse(VideoStallCheck(3).run(true to 8, true to 8, true to 8, true to 9))

    @Test fun pausedDoesNotCount() =
        assertFalse(VideoStallCheck(3).run(true to 8, true to 8, false to 8, true to 8, true to 8))

    @Test fun audioOnlyNeverTrips() =
        assertFalse(VideoStallCheck(3).run(true to null, true to null, true to null, true to null, true to null))
}
