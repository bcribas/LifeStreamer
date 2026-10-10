package com.dimadesu.lifestreamer.bitrate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SendRateTest {

    @Test
    fun `bits per second between two readings`() {
        val rate = SendRate()
        assertNull(rate.update(0, 0))
        // 1 MB in two seconds
        assertEquals(4_000_000, rate.update(2_000, 1_000_000))
    }

    @Test
    fun `sound alone is what goes out when the picture stops`() {
        val rate = SendRate()
        rate.update(0, 10_000_000)
        // 130 kb/s, the 2026-10-10 journal
        assertEquals(130_000, rate.update(2_000, 10_032_500))
    }

    @Test
    fun `a new socket starts over`() {
        val rate = SendRate()
        rate.update(0, 5_000_000)
        assertNull(rate.update(2_000, 1_000))
        assertEquals(8_000, rate.update(4_000, 3_000))
    }

    @Test
    fun `no socket, no rate`() {
        val rate = SendRate()
        rate.update(0, 5_000_000)
        assertNull(rate.update(2_000, null))
        assertNull(rate.update(4_000, 6_000_000))
    }
}
