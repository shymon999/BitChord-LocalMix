package com.music.bitchord.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the merged Song transitions setting hands playback, and what it makes of
 * the crossfade slider and Automix switch it replaced.
 */
class SongTransitionsTest {

    @Test
    fun `off by default, with a crossfade length ready for when it is turned on`() {
        val defaults = SongTransitions()
        assertFalse(defaults.enabled)
        assertEquals(SongTransitionStyle.CROSSFADE, defaults.style)
        assertEquals(6, defaults.crossfadeSeconds)
        assertFalse(defaults.automix)
        assertEquals(0, defaults.playbackCrossfadeSeconds)
    }

    @Test
    fun `Automix that was on stays Automix, keeping any slider length`() {
        val bare = SongTransitions.fromLegacy(crossfadeSeconds = 0, automix = true)
        assertEquals(SongTransitions(true, SongTransitionStyle.AUTOMIX, 6), bare)

        val withSlider = SongTransitions.fromLegacy(crossfadeSeconds = 4, automix = true)
        assertEquals(SongTransitions(true, SongTransitionStyle.AUTOMIX, 4), withSlider)
    }

    @Test
    fun `a crossfade length that was set becomes Crossfade at that length`() {
        assertEquals(
            SongTransitions(true, SongTransitionStyle.CROSSFADE, 4),
            SongTransitions.fromLegacy(crossfadeSeconds = 4, automix = false),
        )
        assertEquals(
            SongTransitions(true, SongTransitionStyle.CROSSFADE, 12),
            SongTransitions.fromLegacy(crossfadeSeconds = 12, automix = false),
        )
    }

    @Test
    fun `nothing set stays off`() {
        assertEquals(SongTransitions(), SongTransitions.fromLegacy(crossfadeSeconds = 0, automix = false))
    }

    @Test
    fun `lengths outside the slider are pulled into range`() {
        // Only reachable through a hand-edited or foreign backup, but an import
        // must not hand playback a 30-second fade or a negative one.
        assertEquals(12, SongTransitions.fromLegacy(crossfadeSeconds = 30, automix = false).crossfadeSeconds)
        assertEquals(SongTransitions(), SongTransitions.fromLegacy(crossfadeSeconds = -3, automix = false))
        assertEquals(1, SongTransitions().withCrossfadeSeconds(0).crossfadeSeconds)
        assertEquals(12, SongTransitions().withCrossfadeSeconds(13).crossfadeSeconds)
        assertEquals(9, SongTransitions().withCrossfadeSeconds(9).crossfadeSeconds)
    }

    @Test
    fun `playback sees Automix only while transitions are on and Automix is chosen`() {
        assertFalse(SongTransitions(enabled = false, style = SongTransitionStyle.AUTOMIX).automix)
        assertTrue(SongTransitions(enabled = true, style = SongTransitionStyle.AUTOMIX).automix)
        assertFalse(SongTransitions(enabled = true, style = SongTransitionStyle.CROSSFADE).automix)
    }

    @Test
    fun `playback sees the crossfade length while on, under either style, and zero while off`() {
        val crossfade = SongTransitions(enabled = true, style = SongTransitionStyle.CROSSFADE, crossfadeSeconds = 9)
        assertEquals(9, crossfade.playbackCrossfadeSeconds)
        // Under Automix the length is the fallback for a pair not analysed yet.
        assertEquals(9, crossfade.copy(style = SongTransitionStyle.AUTOMIX).playbackCrossfadeSeconds)
        // Off keeps the length for later but hands playback nothing.
        val off = crossfade.copy(enabled = false)
        assertEquals(9, off.crossfadeSeconds)
        assertEquals(0, off.playbackCrossfadeSeconds)
    }

    @Test
    fun `Automix keeps its old fallback length for anyone who never touched the slider`() {
        // CrossfadeController falls back on DEFAULT_SMART_FALLBACK_SECONDS (6.0)
        // when the crossfade length reads 0; the merged setting hands it 6.
        assertEquals(6, SongTransitions.fromLegacy(crossfadeSeconds = 0, automix = true).playbackCrossfadeSeconds)
    }
}
