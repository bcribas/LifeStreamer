package com.dimadesu.lifestreamer.composition

import android.util.Size
import com.dimadesu.lifestreamer.sources.SourceChoice
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.CompositionLayout
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionStoreTest {

    /** What the app saved before the layers had their own sources. */
    private val schema2 = """
        {"v":2,"pipSource":"RTMP","bg":-16777216,"layers":[
          {"id":"main","l":0.0,"t":0.0,"r":1.0,"b":1.0,"visible":true,"scale":"FILL","alpha":1.0,"mirror":false,"rot":0},
          {"id":"pip","l":0.68,"t":0.0,"r":1.0,"b":0.34,"visible":true,"scale":"FIT","alpha":1.0,"mirror":false,"rot":0}
        ]}
    """.trimIndent()

    @Test
    fun `a schema 2 blob still loads, with no per-layer sources`() {
        val saved = CompositionStore.decode(schema2)!!
        assertEquals("RTMP", saved.pipSourceName)
        assertTrue(saved.sources.isEmpty())
        assertTrue(saved.depths.isEmpty())
        assertNull(saved.primary)
        assertEquals(LayerRect(0.68f, 0f, 1f, 0.34f), saved.rects["pip"])
    }

    @Test
    fun `the old second layer kinds become sources`() {
        assertEquals(SourceChoice.Rtmp(1), SourceChoice.fromLegacyKind("RTMP") { null })
        assertEquals(SourceChoice.Camera("2"), SourceChoice.fromLegacyKind("CAMERA") { "2" })
        assertEquals(SourceChoice.TestImage, SourceChoice.fromLegacyKind("CAMERA") { null })
        assertEquals(SourceChoice.Screen, SourceChoice.fromLegacyKind("SCREEN") { null })
        assertNull(SourceChoice.fromLegacyKind("SOMETHING") { null })
        assertEquals("RTMP", SourceChoice.legacyKind(SourceChoice.Rtmp(3)))
        assertEquals("TEST_IMAGE", SourceChoice.legacyKind(null))
    }

    @Test
    fun `sources, depth and the audio layer survive the round trip`() {
        // Swapped: the second layer is at the bottom and full, the first one the inset
        val layout = CompositionLayout(
            canvasSize = Size(1080, 1920),
            layers = listOf(
                VideoLayer("main", z = 1, rect = LayerRect(0.68f, 0f, 1f, 0.34f), scaleMode = LayerScaleMode.FIT),
                VideoLayer("pip", z = 0, rect = LayerRect.FULL, scaleMode = LayerScaleMode.FILL),
            ),
            primaryLayerId = "pip"
        )
        val sources = mapOf("main" to "camera:0", "pip" to "rtmp:2")
        val saved = CompositionStore.decode(CompositionStore.encode(layout, sources, "RTMP"))!!
        assertEquals(sources, saved.sources)
        assertEquals(mapOf("main" to 1, "pip" to 0), saved.depths)
        assertEquals("pip", saved.primary)
        assertEquals("RTMP", saved.pipSourceName)
        assertEquals(LayerRect.FULL, saved.rects["pip"])
    }

    @Test
    fun `sources chosen with no composition keep the saved layout`() {
        val json = CompositionStore.withSources(schema2, mapOf("pip" to "screen"), "SCREEN")
        val saved = CompositionStore.decode(json)!!
        assertEquals(mapOf("pip" to "screen"), saved.sources)
        assertEquals("SCREEN", saved.pipSourceName)
        assertEquals(LayerRect(0.68f, 0f, 1f, 0.34f), saved.rects["pip"])

        val fresh = CompositionStore.decode(CompositionStore.withSources(null, mapOf("pip" to "rtmp:1"), "RTMP"))!!
        assertEquals(mapOf("pip" to "rtmp:1"), fresh.sources)
        assertTrue(fresh.rects.isEmpty())
    }

    @Test
    fun `a newer or broken blob is ignored`() {
        assertNull(CompositionStore.decode("""{"v":99,"pipSource":"RTMP","layers":[]}"""))
        assertNull(CompositionStore.decode("not json"))
        assertNull(CompositionStore.decode(null))
    }
}
