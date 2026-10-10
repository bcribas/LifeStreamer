package com.dimadesu.lifestreamer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWatchTest {

    @Test
    fun `a camera taken and given back is told both ways`() {
        val watch = VideoWatch()
        val back = Any()
        assertEquals(
            "camera BACK 80° lost (taken by another app or the system): reopening it",
            watch.onCamera(back, "BACK 80°", "taken by another app or the system", 10_000)
        )
        assertEquals(
            "No picture from BACK 80° for 3 s: taken by another app or the system. Reopening…",
            watch.problem(13_000)
        )
        assertEquals("camera BACK 80° back after 4 s", watch.onCamera(back, "BACK 80°", null, 14_000))
        assertNull(watch.problem(14_000))
        // Nothing new to say
        assertNull(watch.onCamera(back, "BACK 80°", null, 15_000))
    }

    @Test
    fun `both cameras of a composition`() {
        val watch = VideoWatch()
        watch.onCamera("0", "BACK 80°", "taken by another app or the system", 1_000)
        watch.onCamera("1", "FRONT 82°", "taken by another app or the system", 1_000)
        assertTrue(watch.problem(2_000)!!.startsWith("No picture from BACK 80° and FRONT 82°"))
        watch.forget("1")
        assertTrue(watch.problem(2_000)!!.startsWith("No picture from BACK 80° for"))
    }

    @Test
    fun `an encoder that stops putting out frames`() {
        val watch = VideoWatch()
        var now = 0L
        var frames = 0L
        // Live, 60 frames every two seconds
        repeat(5) {
            assertNull(watch.onTick(now, live = true, frameCount = frames))
            now += 2_000; frames += 60
        }
        // The 2026-10-10 case: the count stops and the phone still says it streams
        assertNull(watch.onTick(now, true, frames))
        now += 2_000
        assertNull(watch.onTick(now, true, frames))
        now += 2_000
        assertNull(watch.onTick(now, true, frames))
        now += 2_000
        assertEquals("no video frames for 6 s", watch.onTick(now, true, frames))
        assertEquals("No video for 6 s: the encoder gets no frames", watch.problem(now))
        // Said once
        now += 2_000
        assertNull(watch.onTick(now, true, frames))
        assertEquals("No video for 8 s: the encoder gets no frames", watch.problem(now))

        now += 2_000; frames += 30
        assertEquals("video frames back after 10 s", watch.onTick(now, true, frames))
        assertNull(watch.problem(now))
    }

    @Test
    fun `stopping the live is not a stall`() {
        val watch = VideoWatch()
        watch.onTick(0, true, 0)
        watch.onTick(2_000, true, 60)
        assertNull(watch.onTick(4_000, false, null))
        assertNull(watch.onTick(60_000, false, null))
        // A new live starts counting again
        assertNull(watch.onTick(62_000, true, 0))
        assertNull(watch.onTick(64_000, true, 60))
        assertNull(watch.problem(64_000))
    }

    @Test
    fun `a new encoder counting from zero is progress`() {
        val watch = VideoWatch()
        watch.onTick(0, true, 9_000)
        assertNull(watch.onTick(2_000, true, 30))
        assertNull(watch.onTick(4_000, true, 90))
        assertNull(watch.problem(4_000))
    }

    @Test
    fun `the cameras away are named with the stall`() {
        val watch = VideoWatch()
        watch.onTick(0, true, 100)
        watch.onCamera("0", "BACK 80°", "Max cameras in use", 1_000)
        assertEquals("no video frames for 6 s; away: BACK 80°", watch.onTick(6_000, true, 100))
        // The camera's own words win on screen: they say what to do
        assertTrue(watch.problem(6_000)!!.contains("Max cameras in use"))
    }
}
