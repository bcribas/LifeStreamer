package com.dimadesu.lifestreamer.sources

/**
 * What only the app's screen can do about sources: the RTMP player with its retries and its
 * sound, the USB camera's helper, screen capture, and the dialogs that grant them. The ViewModel
 * registers itself as this while it is alive (see [SourceController.host]); without it, only
 * cameras can be switched.
 */
interface AppSourceHost {
    /** The app is in front, so a permission can be asked for on the phone. */
    val canAskOnPhone: Boolean

    /** A screen-capture grant is at hand and not used up. */
    val captureGranted: Boolean

    val usbConnected: Boolean
    val usbPermitted: Boolean

    /** The whole-picture source the app runs, as it means it (the RTMP index, the USB camera...). */
    val topLevelChoice: SourceChoice?

    /**
     * Switches the whole picture to [choice], already checked by the caller. Returns at once:
     * what happens (a permission asked on the phone, the switch) shows in the state.
     */
    fun switchTopLevel(choice: SourceChoice)
}
