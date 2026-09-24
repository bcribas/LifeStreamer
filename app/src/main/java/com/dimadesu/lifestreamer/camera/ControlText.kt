package com.dimadesu.lifestreamer.camera

import kotlin.math.roundToInt

/** How a control's value reads on the phone; the remote page mirrors it (index.html fmtControl). */
object ControlText {
    fun value(control: ControlDescriptor, value: Float): String = when (control.format) {
        ControlFormat.ZOOM -> "%.1f×".format(value)
        ControlFormat.EV -> "%+.1f EV".format(value * control.scale)
        ControlFormat.DISTANCE -> if (value <= 0.01f) "∞" else "%.2f m".format(1f / value)
        else -> {
            val scaled = value * control.scale
            val number = if (control.scale == 1f) scaled.roundToInt().toString() else "%.1f".format(scaled)
            control.unit?.let { "$number $it" } ?: number
        }
    }

    fun choice(control: ControlDescriptor): String? =
        control.options?.firstOrNull { it.value == control.value }?.label

    fun group(group: String): String = when (group) {
        ControlGroup.ZOOM -> "Zoom"
        ControlGroup.EXPOSURE -> "Exposure"
        ControlGroup.FOCUS -> "Focus"
        ControlGroup.WHITE_BALANCE -> "White balance"
        ControlGroup.IMAGE -> "Image"
        ControlGroup.LIGHT -> "Light"
        else -> ""
    }
}
