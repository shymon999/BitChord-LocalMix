package com.music.bitchord.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which tracks get resolved ahead of time for their loudness figure. */
class LoudnessWarmupTest {

    @Test
    fun `a YouTube track with no figure yet is resolved ahead`() {
        assertTrue(LoudnessWarmup.wanted("bitchord", "watch", figureKnown = false))
    }

    @Test
    fun `a track whose figure is already known is left alone`() {
        assertFalse(LoudnessWarmup.wanted("bitchord", "watch", figureKnown = true))
    }

    @Test
    fun `only tracks queued from YouTube are resolved for one`() {
        // A source-backed track: JioSaavn, an addon, a module.
        assertFalse(LoudnessWarmup.wanted("bitchord", "source", figureKnown = false))
        // A download or a file from the device's own library, which play
        // without the network.
        assertFalse(LoudnessWarmup.wanted("file", null, figureKnown = false))
        assertFalse(LoudnessWarmup.wanted("content", "media", figureKnown = false))
        // An SMB share.
        assertFalse(LoudnessWarmup.wanted("smb", "nas", figureKnown = false))
        assertFalse(LoudnessWarmup.wanted(null, null, figureKnown = false))
    }
}
