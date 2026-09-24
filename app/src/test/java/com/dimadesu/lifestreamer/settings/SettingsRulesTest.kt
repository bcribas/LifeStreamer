package com.dimadesu.lifestreamer.settings

import com.dimadesu.lifestreamer.settings.SettingsRules.Dimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRulesTest {
    @Test
    fun `bitrates snap to 50 below 1 Mbps and 500 above, to the nearest`() {
        assertEquals(200, SettingsRules.snapBitrateKbps(210))
        assertEquals(250, SettingsRules.snapBitrateKbps(226))
        assertEquals(1000, SettingsRules.snapBitrateKbps(990))
        assertEquals(1500, SettingsRules.snapBitrateKbps(1260))
        assertEquals(1000, SettingsRules.snapBitrateKbps(1200))
    }

    @Test
    fun `the regulator minimum never exceeds the target`() {
        assertEquals(300 to 5000, SettingsRules.coupleRegulator(300, 5000, targetWasSet = true))
        // Target set below the minimum: the minimum follows it down
        assertEquals(250 to 250, SettingsRules.coupleRegulator(300, 250, targetWasSet = true))
        // Minimum set above the target: the target follows it up
        assertEquals(800 to 800, SettingsRules.coupleRegulator(800, 500, targetWasSet = false))
    }

    @Test
    fun `ports, latencies and hosts`() {
        assertEquals(6000, SettingsRules.port(" 6000 "))
        assertNull(SettingsRules.port("0"))
        assertNull(SettingsRules.port("70000"))
        assertNull(SettingsRules.port("abc"))
        assertEquals(1000, SettingsRules.latencyMs("1000"))
        assertNull(SettingsRules.latencyMs("5"))
        assertEquals("charge.naquadah.com.br", SettingsRules.host(" charge.naquadah.com.br "))
        assertNull(SettingsRules.host("srt://host"))
        assertNull(SettingsRules.host("a b"))
        assertNull(SettingsRules.host(""))
    }

    @Test
    fun `resolutions parse from the stored WxH`() {
        assertEquals(Dimensions(1920, 1080), Dimensions.parse("1920x1080"))
        assertNull(Dimensions.parse("live"))
        assertNull(Dimensions.parse("0x10"))
        assertTrue(SettingsRules.sameShape(Dimensions(640, 360), Dimensions(1920, 1080)))
        assertFalse(SettingsRules.sameShape(Dimensions(640, 480), Dimensions(1920, 1080)))
    }

    @Test
    fun `low bitrate suggests the largest resolution of the same shape that fits`() {
        val offered = listOf(
            Dimensions(1920, 1080), Dimensions(1280, 720), Dimensions(960, 720),
            Dimensions(640, 480), Dimensions(640, 360)
        )
        val warning = SettingsRules.lowBitrate(250_000, Dimensions(1920, 1080), 15, offered)!!
        assertEquals(600, warning.neededKbps)
        // 960x720 fits too, but it is 4:3: a 16:9 live is pointed at 640x360
        assertEquals(Dimensions(640, 360), warning.smaller)

        assertNull(SettingsRules.lowBitrate(250_000, Dimensions(640, 360), 15, offered))
    }

    @Test
    fun `when no smaller resolution fits, 15 fps is suggested`() {
        val warning = SettingsRules.lowBitrate(
            200_000, Dimensions(640, 360), 60, listOf(Dimensions(640, 360))
        )!!
        assertNull(warning.smaller)
        assertEquals(Dimensions(640, 360), warning.smallerAt15Fps)
    }
}
