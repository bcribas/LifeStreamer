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
package com.dimadesu.lifestreamer.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.dimadesu.lifestreamer.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Shows the remote control URL and PIN as a QR code, which is what makes the remote usable once
 * the phone is already mounted: the other device points its camera and is in.
 *
 * Opened from the settings and from the button on the main screen. The address is read again
 * whenever Wi-Fi changes while the dialog is open: the server listens on every interface, so
 * after a network switch only the address changes, and a dialog opened a moment before the new
 * one is assigned would otherwise keep showing the old one.
 */
object RemoteControlQrDialog {
    private const val TAG = "RemoteControlQrDialog"

    fun show(context: Context, pin: String) {
        if (!RemoteControlManager.isRunning) {
            Toast.makeText(context, "Remote control is not running", Toast.LENGTH_SHORT).show()
            return
        }

        // Sized from the SHORT edge of the screen. The phone is normally in landscape while
        // streaming, where the window is only a few hundred dp tall, and a QR laid out against a
        // fixed dp size simply gets squeezed by the dialog until a camera can no longer read it.
        val metrics = context.resources.displayMetrics
        val shortEdgePx = minOf(metrics.widthPixels, metrics.heightPixels)
        val sizePx = (shortEdgePx * 0.62f).toInt().coerceIn(360, 900)
        fun dp(value: Int) = (metrics.density * value).toInt()

        val image = ImageView(context).apply {
            // The quiet zone has to be white even under the dark theme, where the dialog
            // background would otherwise bleed right up to the finder patterns.
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        val caption = TextView(context).apply {
            textSize = 12f
            setPadding(dp(16), dp(12), dp(16), 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(image)
            addView(caption)
        }

        var dismissed = false
        var shownUrl: String? = null
        var rendered = false
        fun render() {
            if (dismissed) return
            val url = RemoteControlManager.urlWithPin(context, pin)
            if (rendered && url == shownUrl) return
            rendered = true
            shownUrl = url
            val bitmap = url?.let { encode(it, sizePx) }
            // INVISIBLE, not GONE: the dialog keeps its size instead of jumping while it waits.
            image.visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
            image.setImageBitmap(bitmap)
            caption.text = when {
                url == null -> context.getString(R.string.remote_control_qr_waiting)
                bitmap == null -> "Could not build the QR code\n\n$url"
                else -> "$url\n\n${context.getString(R.string.remote_control_qr_message)}"
            }
        }
        render()

        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val mainHandler = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { render() }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                mainHandler.post { render() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { render() }
            }
        }
        // Several transports in one request match a network with any of them.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val registered = runCatching { connectivity?.registerNetworkCallback(request, callback) }
            .onFailure { Log.w(TAG, "Could not watch the network: ${it.message}") }
            .isSuccess && connectivity != null

        // Landscape leaves very little height, so the content scrolls rather than being clipped
        // or shrunk. setMessage is deliberately not used: its text competes with the image for
        // the same scarce height.
        AlertDialog.Builder(context)
            .setTitle(R.string.remote_control_qr_title)
            .setView(ScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener {
                dismissed = true
                if (registered) {
                    runCatching { connectivity?.unregisterNetworkCallback(callback) }
                }
            }
            .show()
    }

    /**
     * One pixel per module, then a nearest-neighbour upscale. Drawing straight at the final size
     * means ~600k setPixel calls on the main thread; this is a few thousand, and scaling without
     * filtering keeps the module edges hard, which is what a decoder needs.
     */
    private fun encode(text: String, sizePx: Int): Bitmap? = runCatching {
        val matrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            1,
            1,
            mapOf(EncodeHintType.MARGIN to 4)
        )
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        val small = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val scale = maxOf(1, sizePx / w)
        Bitmap.createScaledBitmap(small, w * scale, h * scale, false)
    }.onFailure { Log.w(TAG, "Could not build the QR code: ${it.message}") }.getOrNull()
}
