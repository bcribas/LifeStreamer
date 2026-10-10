package com.dimadesu.lifestreamer.sources

import io.github.thibaultbee.streampack.core.elements.sources.video.IVideoSource
import io.github.thibaultbee.streampack.core.elements.sources.video.camera.ICameraSource
import io.github.thibaultbee.streampack.core.elements.sources.video.composite.ICompositeVideoSource

/** The cameras behind [source]: itself, or a composition's camera layers. */
fun camerasIn(source: IVideoSource?): List<ICameraSource> = when (source) {
    is ICameraSource -> listOf(source)
    is ICompositeVideoSource -> source.layoutFlow.value.layers.mapNotNull { source.childSource(it.id) as? ICameraSource }
    else -> emptyList()
}
