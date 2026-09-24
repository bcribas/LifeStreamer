package com.dimadesu.lifestreamer.sources

/**
 * Whether a source can be chosen now, and why not; the same answer for the phone and the page.
 * Pure, so it is tested on the JVM.
 */
object SourceRules {

    /** What is true right now, as the service and the app's screen know it. */
    data class Context(
        /** The app's screen is alive: USB, screen and RTMP-with-its-sound need it. */
        val appOpen: Boolean,
        /** The app is in front, so a permission can be asked for on the phone. */
        val canAskOnPhone: Boolean,
        val cameraIds: List<String>,
        /** RTMP sources by index, true when a URL is set. */
        val rtmp: Map<Int, Boolean>,
        /** A screen-capture grant is at hand and not used up (it also carries RTMP sound). */
        val captureGranted: Boolean,
        val apiLevel: Int,
        val usbConnected: Boolean,
        val usbPermitted: Boolean,
        /** What each layer of the running composition shows; empty without one. */
        val layers: Map<String, SourceChoice> = emptyMap(),
        /** Whether two cameras can run at the same time on this phone. */
        val canRunTogether: (String, String) -> Boolean = { _, _ -> true },
    )

    /**
     * [available]: it can be chosen. [phoneAction]: choosing it asks something on the phone first
     * (a permission), which is what the page says while it waits.
     */
    data class Verdict(val available: Boolean, val reason: String? = null, val phoneAction: String? = null) {
        companion object {
            val OK = Verdict(true)
            fun no(reason: String) = Verdict(false, reason)
        }
    }

    const val NEEDS_APP = "Needs the app open on the phone"
    const val BRING_APP = "Bring the app to the front on the phone to allow it"
    const val ACCEPT_CAPTURE = "Accept screen capture on the phone"
    const val ALLOW_USB = "Allow the USB camera on the phone"

    /** Screen capture (and the sound of an RTMP source) needs Android 10. */
    private const val API_CAPTURE = 29

    /** The choices to offer: the whole picture, or [layerId]'s. */
    fun options(context: Context, layerId: String?): List<SourceChoice> =
        context.cameraIds.map { SourceChoice.Camera(it) } +
                context.rtmp.keys.sorted().map { SourceChoice.Rtmp(it) } +
                listOf(SourceChoice.Usb, SourceChoice.Screen) +
                listOfNotNull(SourceChoice.TestImage.takeIf { layerId != null })

    /** Whether [choice] can feed the whole picture ([layerId] null) or that layer. */
    fun verdict(choice: SourceChoice, context: Context, layerId: String? = null): Verdict {
        val others = context.layers.filterKeys { it != layerId }
        if (layerId != null && choice !is SourceChoice.TestImage && choice in others.values) {
            return Verdict.no("Already in another layer")
        }
        return when (choice) {
            is SourceChoice.Camera -> camera(choice, context, others, layerId)
            is SourceChoice.Rtmp -> rtmp(choice, context, layerId)
            SourceChoice.Usb -> usb(context)
            SourceChoice.Screen -> screen(context)
            SourceChoice.TestImage ->
                if (layerId == null) Verdict.no("Only for a layer of a composition") else Verdict.OK
        }
    }

    private fun camera(
        choice: SourceChoice.Camera,
        context: Context,
        others: Map<String, SourceChoice>,
        layerId: String?,
    ): Verdict {
        if (choice.id !in context.cameraIds) return Verdict.no("No such camera")
        if (layerId == null) return Verdict.OK
        val clash = others.values.filterIsInstance<SourceChoice.Camera>()
            .firstOrNull { !context.canRunTogether(choice.id, it.id) }
        return if (clash != null) Verdict.no("Cannot run at the same time as the other camera on this phone")
        else Verdict.OK
    }

    private fun rtmp(choice: SourceChoice.Rtmp, context: Context, layerId: String?): Verdict {
        when (context.rtmp[choice.index]) {
            null -> return Verdict.no("There is no RTMP source ${choice.index}")
            false -> return Verdict.no("RTMP source ${choice.index} has no URL (Settings)")
            true -> Unit
        }
        // In a layer the picture needs nothing from the app (its sound does, when it is the
        // audio layer, and says so there); as the whole picture it brings its sound along
        if (layerId != null) return Verdict.OK
        if (!context.appOpen) return Verdict.no(NEEDS_APP)
        return needsCapture(context)
    }

    private fun usb(context: Context): Verdict {
        if (!context.appOpen) return Verdict.no(NEEDS_APP)
        if (!context.usbConnected) return Verdict.no("No USB camera connected")
        if (context.usbPermitted) return Verdict.OK
        return if (context.canAskOnPhone) Verdict(true, phoneAction = ALLOW_USB) else Verdict.no(BRING_APP)
    }

    private fun screen(context: Context): Verdict {
        if (context.apiLevel < API_CAPTURE) return Verdict.no("Needs Android 10")
        if (!context.appOpen) return Verdict.no(NEEDS_APP)
        return needsCapture(context)
    }

    private fun needsCapture(context: Context): Verdict = when {
        context.apiLevel < API_CAPTURE || context.captureGranted -> Verdict.OK
        context.canAskOnPhone -> Verdict(true, phoneAction = ACCEPT_CAPTURE)
        else -> Verdict.no(BRING_APP)
    }
}
