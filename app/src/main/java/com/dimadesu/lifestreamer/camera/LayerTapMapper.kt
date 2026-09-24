package com.dimadesu.lifestreamer.camera

/**
 * Which layer of a composition a point on the canvas falls on, and where that is in the layer's
 * own frame, undoing how the compositor draws it: fit (letterboxed) or fill (cropped), mirrored,
 * rotated. Pure, so it is tested on the JVM.
 */
object LayerTapMapper {

    /** A layer as the compositor draws it; rect in canvas fractions, as CompositionLayout has it. */
    data class Layer(
        val id: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val z: Int,
        val visible: Boolean = true,
        /** FILL, FIT or STRETCH */
        val scaleMode: String = "FILL",
        val mirror: Boolean = false,
        val rotation: Int = 0,
    )

    /** The layer tapped, and the point on its frame as the camera delivers it upright (0..1). */
    data class Hit(val layerId: String, val x: Float, val y: Float)

    /**
     * The topmost visible layer drawn under ([x], [y]) (canvas fractions), or null. A tap in the
     * bars of a fitted layer is not on it, and goes to the layer below.
     *
     * @param canvasAspect the canvas's width over height
     * @param sourceAspect each layer's frame width over height, upright
     */
    fun hit(
        layers: List<Layer>,
        x: Float,
        y: Float,
        canvasAspect: Float,
        sourceAspect: (String) -> Float = { canvasAspect },
    ): Hit? {
        for (layer in layers.filter { it.visible }.sortedByDescending { it.z }) {
            val width = layer.right - layer.left
            val height = layer.bottom - layer.top
            if (width <= 0f || height <= 0f) continue
            val quadAspect = width / height * canvasAspect
            val turned = layer.rotation == 90 || layer.rotation == 270
            val source = sourceAspect(layer.id).let { if (turned) 1f / it else it }

            // Where the picture is drawn: the whole rectangle, or a letterboxed part of it
            var left = layer.left
            var top = layer.top
            var drawnW = width
            var drawnH = height
            if (layer.scaleMode == "FIT") {
                if (source > quadAspect) {
                    drawnH = height * quadAspect / source
                    top += (height - drawnH) / 2
                } else {
                    drawnW = width * source / quadAspect
                    left += (width - drawnW) / 2
                }
            }
            if (x < left || x > left + drawnW || y < top || y > top + drawnH) continue

            var u = (x - left) / drawnW
            var v = (y - top) / drawnH
            if (layer.mirror) u = 1f - u
            // Filling crops the (turned) frame to the rectangle's shape, centred
            if (layer.scaleMode == "FILL") {
                if (source > quadAspect) u = 0.5f + (u - 0.5f) * quadAspect / source
                else v = 0.5f + (v - 0.5f) * source / quadAspect
            }
            // Undo the layer's turn (clockwise, as drawn)
            val (fx, fy) = when (layer.rotation) {
                90 -> v to 1f - u
                180 -> 1f - u to 1f - v
                270 -> 1f - v to u
                else -> u to v
            }
            return Hit(layer.id, fx, fy)
        }
        return null
    }
}
