package dev.atv.nasplayer.source

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumFolderTest {
    private fun folder(vararg names: String) = names.map { RemoteEntry(it, "/$it", isDirectory = it.endsWith("/")) }

    @Test fun musicWithAtMostOneImage() {
        assertTrue(isAlbumFolder(folder("a.mp3", "b.flac")))
        assertTrue(isAlbumFolder(folder("a.mp3", "cover.jpg", "rip.log", "Disc 2/")))
    }

    @Test fun anythingElse() {
        assertFalse(isAlbumFolder(folder("a.mp3", "front.jpg", "back.jpg")))
        assertFalse(isAlbumFolder(folder("a.mp3", "b.mkv")))
        assertFalse(isAlbumFolder(folder("cover.jpg", "Disc 1/")))
    }
}
