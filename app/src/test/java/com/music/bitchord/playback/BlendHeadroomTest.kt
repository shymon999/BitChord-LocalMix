package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** The headroom two overlapping tracks share, as the crossfade multiplies it into both faders. */
class BlendHeadroomTest {

    private fun rise(progress: Float) = sin(progress * PI.toFloat() / 2f)
    private fun fall(progress: Float) = cos(progress * PI.toFloat() / 2f)

    /** A blend in a thousand steps, both ends included. */
    private val blend = (0..1000).map { it / 1000f }

    /** Automix's bass-swap blend with both faders up by the swap, as `CrossfadeController.mixRise`/`mixFall` hold them. */
    private fun djIncoming(progress: Float) = rise(minOf(1f, progress / 0.5f))
    private fun djOutgoing(progress: Float) = if (progress <= 0.5f) 1f else fall((progress - 0.5f) / 0.5f)

    @Test
    fun `no trim at either end of a blend, so putting it on and taking it off is never a step`() {
        assertEquals(1f, blendHeadroom(rise(0f), fall(0f)), 1e-6f)
        assertEquals(1f, blendHeadroom(rise(1f), fall(1f)), 1e-6f)
        assertEquals(1f, blendHeadroom(djIncoming(0f), djOutgoing(0f)), 1e-6f)
        assertEquals(1f, blendHeadroom(djIncoming(1f), djOutgoing(1f)), 1e-6f)
        // One track, at any level, is left alone.
        assertEquals(1f, blendHeadroom(0f, 0.3f), 0f)
        assertEquals(1f, blendHeadroom(1f, 0f), 0f)
    }

    @Test
    fun `an equal-power midpoint gives up 1_5 dB and peaks within 1_19 of full scale`() {
        val trim = blendHeadroom(rise(0.5f), fall(0.5f))
        assertEquals(0.8409f, trim, 1e-3f)
        assertEquals(-1.505f, 20f * log10(trim), 0.01f)
        assertEquals(1.189f, (rise(0.5f) + fall(0.5f)) * trim, 1e-3f)
    }

    @Test
    fun `both faders up are each taken down 3 dB, together carrying one track's power`() {
        val trim = blendHeadroom(1f, 1f)
        assertEquals(0.7071f, trim, 1e-4f)
        assertEquals(1f, 2f * trim.pow(2), 1e-4f)
    }

    @Test
    fun `a trimmed blend never carries more power than one track at full`() {
        for (progress in blend) {
            val equalPower = blendHeadroom(rise(progress), fall(progress))
            val plain = (rise(progress) * equalPower).pow(2) + (fall(progress) * equalPower).pow(2)
            assertTrue("equal power: $plain at $progress", plain <= 1f + 1e-5f)

            val incoming = djIncoming(progress)
            val outgoing = djOutgoing(progress)
            val dj = blendHeadroom(incoming, outgoing)
            val held = (incoming * dj).pow(2) + (outgoing * dj).pow(2)
            assertTrue("faders held up: $held at $progress", held <= 1f + 1e-5f)
        }
    }

    @Test
    fun `the trim follows the faders smoothly, without a jump anywhere in the blend`() {
        var last = blendHeadroom(rise(0f), fall(0f))
        var lastDj = blendHeadroom(djIncoming(0f), djOutgoing(0f))
        for (progress in blend.drop(1)) {
            val trim = blendHeadroom(rise(progress), fall(progress))
            val dj = blendHeadroom(djIncoming(progress), djOutgoing(progress))
            assertTrue("equal power jumped ${abs(trim - last)} at $progress", abs(trim - last) < 0.01f)
            assertTrue("faders held up jumped ${abs(dj - lastDj)} at $progress", abs(dj - lastDj) < 0.01f)
            last = trim
            lastDj = dj
        }
    }
}
