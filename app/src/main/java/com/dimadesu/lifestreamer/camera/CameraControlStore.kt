package com.dimadesu.lifestreamer.camera

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson

/**
 * Remembers each physical camera's controls, so they come back whenever that camera turns on,
 * across restarts too. Keyed by the camera, not by the layer it feeds: the same lens keeps its
 * focus and white balance wherever it is used.
 *
 * SharedPreferences rather than the settings DataStore: every DataStore change is announced to
 * the remote pages as a settings change, which a dragged slider would flood.
 */
class CameraControlStore(context: Context) {

    /** Flat and kept, as Gson writes field names and R8 would rename them (see proguard-rules.pro). */
    @Keep
    internal data class Dto(
        val v: Int = SCHEMA_VERSION,
        val zoom: Float? = null,
        val ev: Int? = null,
        val aeLock: Boolean? = null,
        val manual: Boolean? = null,
        val iso: Int? = null,
        val expNs: Long? = null,
        val af: Int? = null,
        val lens: Float? = null,
        val awb: Int? = null,
        val awbLock: Boolean? = null,
        val uvc: Map<String, Int>? = null,
    )

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** What was remembered for [cameraKey]; automatic when nothing was, or it cannot be read. */
    fun load(cameraKey: String): CameraControlValues {
        val json = prefs.getString(prefKey(cameraKey), null) ?: return CameraControlValues()
        return fromJson(gson, json) ?: CameraControlValues()
    }

    fun save(cameraKey: String, values: CameraControlValues) {
        try {
            if (values.isAutomatic) {
                prefs.edit().remove(prefKey(cameraKey)).apply()
            } else {
                prefs.edit().putString(prefKey(cameraKey), gson.toJson(toDto(values))).apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save the controls of $cameraKey: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "CameraControlStore"
        private const val PREFS_NAME = "camera_controls"
        internal const val SCHEMA_VERSION = 1

        /** A phone camera's key, by its Camera2 id. */
        fun camera2Key(cameraId: String) = "cam2:$cameraId"

        /** A USB camera's key, by its vendor and product (the same model shares its settings). */
        fun uvcKey(productKey: String) = "uvc:$productKey"

        private fun prefKey(cameraKey: String) = "v$SCHEMA_VERSION:$cameraKey"

        internal fun toDto(values: CameraControlValues) = Dto(
            zoom = values.zoom,
            ev = values.evIndex,
            aeLock = values.aeLock,
            manual = values.manualExposure,
            iso = values.iso,
            expNs = values.exposureNs,
            af = values.afMode,
            lens = values.lensDiopters,
            awb = values.awbMode,
            awbLock = values.awbLock,
            uvc = values.uvc.takeIf { it.isNotEmpty() },
        )

        internal fun fromDto(dto: Dto) = CameraControlValues(
            zoom = dto.zoom,
            evIndex = dto.ev,
            aeLock = dto.aeLock,
            manualExposure = dto.manual,
            iso = dto.iso,
            exposureNs = dto.expNs,
            afMode = dto.af,
            lensDiopters = dto.lens,
            awbMode = dto.awb,
            awbLock = dto.awbLock,
            uvc = dto.uvc.orEmpty(),
        )

        /**
         * Null on anything unexpected: a corrupt value, or one saved by a newer app, falls back to
         * automatic rather than crashing when the camera turns on.
         */
        internal fun fromJson(gson: Gson, json: String): CameraControlValues? = try {
            gson.fromJson(json, Dto::class.java)
                ?.takeIf { it.v <= SCHEMA_VERSION }
                ?.let { fromDto(it) }
        } catch (t: Throwable) {
            null
        }
    }
}
