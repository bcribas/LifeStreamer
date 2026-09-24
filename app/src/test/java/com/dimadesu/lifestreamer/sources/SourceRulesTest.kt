package com.dimadesu.lifestreamer.sources

import com.dimadesu.lifestreamer.sources.SourceChoice.Camera
import com.dimadesu.lifestreamer.sources.SourceChoice.Rtmp
import com.dimadesu.lifestreamer.sources.SourceChoice.Screen
import com.dimadesu.lifestreamer.sources.SourceChoice.TestImage
import com.dimadesu.lifestreamer.sources.SourceChoice.Usb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceRulesTest {
    private val open = SourceRules.Context(
        appOpen = true, canAskOnPhone = true, cameraIds = listOf("0", "1", "2"),
        rtmp = mapOf(1 to true, 2 to false), captureGranted = false, apiLevel = 34,
        usbConnected = true, usbPermitted = true,
    )
    private val closed = open.copy(appOpen = false, canAskOnPhone = false)

    @Test
    fun `keys round trip, and nonsense is none`() {
        listOf(Camera("0"), Rtmp(2), Usb, Screen, TestImage).forEach {
            assertEquals(it, SourceChoice.parse(it.key))
        }
        assertNull(SourceChoice.parse("rtmp:0"))
        assertNull(SourceChoice.parse("camera:"))
        assertNull(SourceChoice.parse("webcam"))
    }

    @Test
    fun `with the app closed only cameras switch the whole picture`() {
        assertTrue(SourceRules.verdict(Camera("1"), closed).available)
        listOf(Rtmp(1), Usb, Screen).forEach {
            assertEquals(SourceRules.NEEDS_APP, SourceRules.verdict(it, closed).reason)
        }
    }

    @Test
    fun `what needs a permission says it will ask on the phone, or to bring the app forward`() {
        val screen = SourceRules.verdict(Screen, open)
        assertTrue(screen.available)
        assertEquals(SourceRules.ACCEPT_CAPTURE, screen.phoneAction)
        val behind = open.copy(canAskOnPhone = false)
        assertEquals(SourceRules.BRING_APP, SourceRules.verdict(Screen, behind).reason)
        assertNull(SourceRules.verdict(Screen, open.copy(captureGranted = true)).phoneAction)
        assertEquals(SourceRules.ALLOW_USB, SourceRules.verdict(Usb, open.copy(usbPermitted = false)).phoneAction)
        assertFalse(SourceRules.verdict(Usb, open.copy(usbConnected = false)).available)
    }

    @Test
    fun `an RTMP source needs a URL, and in a layer not the app`() {
        assertFalse(SourceRules.verdict(Rtmp(2), open).available)
        assertFalse(SourceRules.verdict(Rtmp(3), open).available)
        assertTrue(SourceRules.verdict(Rtmp(1), closed, layerId = "main").available)
        assertEquals(SourceRules.ACCEPT_CAPTURE, SourceRules.verdict(Rtmp(1), open).phoneAction)
    }

    @Test
    fun `a layer cannot take what another layer shows, nor a camera that cannot pair`() {
        val composing = open.copy(
            layers = mapOf("main" to Camera("0"), "pip" to Rtmp(1)),
            canRunTogether = { a, b -> setOf(a, b) != setOf("0", "2") },
        )
        assertFalse(SourceRules.verdict(Rtmp(1), composing, "main").available)
        assertTrue(SourceRules.verdict(Rtmp(1), composing, "pip").available)
        assertFalse(SourceRules.verdict(Camera("0"), composing, "pip").available)
        assertTrue(SourceRules.verdict(Camera("1"), composing, "pip").available)
        assertFalse(SourceRules.verdict(Camera("2"), composing, "pip").available)
        assertTrue(SourceRules.verdict(TestImage, composing, "pip").available)
        assertFalse(SourceRules.verdict(TestImage, composing).available)
    }

    @Test
    fun `layers are offered the test image, the whole picture is not`() {
        assertTrue(TestImage in SourceRules.options(open, "pip"))
        assertFalse(TestImage in SourceRules.options(open, null))
        assertEquals(listOf(Camera("0"), Camera("1"), Camera("2"), Rtmp(1), Rtmp(2), Usb, Screen), SourceRules.options(open, null))
    }
}
