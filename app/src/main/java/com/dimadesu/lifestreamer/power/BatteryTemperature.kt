package com.dimadesu.lifestreamer.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * The battery's temperature, in °C, or null when unknown.
 *
 * It follows the phone's skin closely, and unlike the thermal status it has the same meaning on
 * every phone: on the S20 FE Samsung's own heat guard closed the app with the battery at 55 °C
 * and the skin at 58 °C, while the thermal status alone gave no warning the app could act on.
 */
object BatteryTemperature {
    fun read(context: Context): Float? = runCatching {
        val battery: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
    }.getOrNull()
}
