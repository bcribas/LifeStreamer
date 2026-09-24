/*
 * Copyright (C) 2026 dimadesu
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dimadesu.lifestreamer.composition

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.Size
import androidx.media3.exoplayer.ExoPlayer
import com.dimadesu.lifestreamer.R
import com.dimadesu.lifestreamer.rtmp.video.RTMPVideoSource
import com.dimadesu.lifestreamer.sources.SourceChoice
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerRect
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.LayerScaleMode
import io.github.thibaultbee.streampack.core.elements.processing.video.composition.VideoLayer
import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSourceInternal
import io.github.thibaultbee.streampack.core.elements.sources.video.bitmap.BitmapSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.CameraSourceFactory
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.LayerSpec

/** The two layers the app composes. Fixed ids: failure handling, USB and labels key on them. */
object CompositionLayers {
    const val MAIN = "main"
    const val PIP = "pip"
}

/**
 * Sources only the app's screen can build.
 *
 * A screen layer needs a MediaProjection grant and a USB layer needs the UVC helper, and both are
 * obtained through interactive dialogs on the phone. The service cannot produce either, so the
 * ViewModel registers itself as this while it is alive; without it those sources are offered as
 * unavailable, with the reason, instead of silently doing nothing.
 */
interface ExternalLayerSources {
    /** A factory for [choice] (USB or screen), or null if it cannot be built right now. */
    fun factoryFor(choice: SourceChoice): IVideoSourceInternal.Factory?

    /** Why [choice] cannot be built right now, or null if it can. */
    fun reasonUnavailable(choice: SourceChoice): String?
}

/**
 * A layer, built. [placeholderReason] is set when the chosen source could not be built and the
 * test image stands in for it, saying why.
 */
data class LayerBuild(val spec: LayerSpec, val placeholderReason: String? = null)

/**
 * Builds the layer specs the composition is made of.
 *
 * Moved out of PreviewViewModel so the service can build a composition on its own: the remote
 * control runs precisely when the app's screen is gone, and everything that only lived in the
 * ViewModel was out of its reach.
 */
class CompositionSources(private val application: Application) {
    /** Also the placeholder: a bitmap source cannot fail, which makes it a safe terminal state. */
    val testBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(application.resources, R.drawable.img_test)
    }

    fun mainLayer() = VideoLayer(
        id = CompositionLayers.MAIN,
        z = 0,
        rect = LayerRect.FULL,
        scaleMode = LayerScaleMode.FILL
    )

    fun pipLayer() = VideoLayer(
        id = CompositionLayers.PIP,
        z = 1,
        rect = LayerRect.PIP_BOTTOM_RIGHT,
        scaleMode = LayerScaleMode.FIT
    )

    fun defaultLayer(layerId: String) = if (layerId == CompositionLayers.MAIN) mainLayer() else pipLayer()

    /** The test image in [layer], keeping its place. */
    fun placeholderSpec(layer: VideoLayer) = LayerSpec(
        layer = layer,
        childFactory = BitmapSourceFactory(testBitmap)
    )

    /**
     * Builds [layer] showing [choice]. A source that cannot be built right now degrades to the
     * placeholder instead of failing the whole composition.
     *
     * @param captureResolution the cap for a camera that runs next to another one
     * @param rtmpPlayer the player of an RTMP layer, or null when the source has no URL
     */
    suspend fun layerSpec(
        choice: SourceChoice,
        layer: VideoLayer,
        external: ExternalLayerSources?,
        captureResolution: Size?,
        rtmpPlayer: suspend () -> ExoPlayer?,
    ): LayerBuild = when (choice) {
        SourceChoice.TestImage -> LayerBuild(placeholderSpec(layer))

        is SourceChoice.Camera -> LayerBuild(
            LayerSpec(
                layer = layer,
                childFactory = CameraSourceFactory(choice.id),
                captureResolution = captureResolution
            )
        )

        is SourceChoice.Rtmp -> try {
            val player = rtmpPlayer() ?: error("RTMP source ${choice.index} has no URL")
            LayerBuild(LayerSpec(layer = layer, childFactory = RTMPVideoSource.Factory(player)))
        } catch (e: Exception) {
            Log.w(TAG, "Could not build the RTMP layer: ${e.message}")
            LayerBuild(placeholderSpec(layer), "RTMP ${choice.index} unavailable - showing placeholder")
        }

        SourceChoice.Usb, SourceChoice.Screen -> {
            val factory = external?.factoryFor(choice)
            if (factory == null) {
                LayerBuild(placeholderSpec(layer), external?.reasonUnavailable(choice) ?: OPEN_APP_REASON)
            } else {
                LayerBuild(LayerSpec(layer = layer, childFactory = factory))
            }
        }
    }

    companion object {
        private const val TAG = "CompositionSources"

        /** Screen and USB need a dialog on the phone; with the app closed there is no way to show it. */
        const val OPEN_APP_REASON = "Needs the app open on the phone"
    }
}
