package com.dimadesu.lifestreamer.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Tap to focus and pinch to zoom on the preview, in a composition too: laid over the preview on
 * exactly its pixels (like [CompositionOverlayView]), so a touch is a point on the canvas, and
 * the listener finds the layer under it.
 *
 * The preview's own gestures only reach a camera that is the whole picture, and go around the
 * camera controls, which would then undo them.
 */
class CameraGestureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        /** A tap at ([x], [y]), in fractions of the canvas. */
        fun onTap(x: Float, y: Float)

        /** A pinch around ([x], [y]) began; the zoom it asks for follows. */
        fun onPinchStart(x: Float, y: Float)

        /** The pinch asks for [factor] more zoom since the last call. */
        fun onPinch(factor: Float)
    }

    var listener: Listener? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xE6FFFFFF.toInt()
    }
    private var ringX = 0f
    private var ringY = 0f
    private var ringAlpha = 0f
    private var ringAnimator: ValueAnimator? = null

    /** Zoom gathered since the last report: a pinch sends at most one every [REPORT_MS]. */
    private var pendingFactor = 1f
    private var lastReport = 0L

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (width <= 0 || height <= 0) return false
            showRing(e.x, e.y)
            listener?.onTap(e.x / width, e.y / height)
            return true
        }
    })

    private val pinches = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (width <= 0 || height <= 0) return false
            pendingFactor = 1f
            listener?.onPinchStart(detector.focusX / width, detector.focusY / height)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pendingFactor *= detector.scaleFactor
            val now = System.currentTimeMillis()
            if (now - lastReport >= REPORT_MS) report(now)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            report(System.currentTimeMillis())
        }
    })

    private fun report(now: Long) {
        if (pendingFactor == 1f) return
        listener?.onPinch(pendingFactor)
        pendingFactor = 1f
        lastReport = now
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinches.onTouchEvent(event)
        if (!pinches.isInProgress) taps.onTouchEvent(event)
        return true
    }

    private fun showRing(x: Float, y: Float) {
        ringX = x
        ringY = y
        ringAnimator?.cancel()
        ringAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = RING_MS
            addUpdateListener {
                ringAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (ringAlpha <= 0f) return
        // The view is laid out at the stream's size and scaled down: size the ring for the eye
        val scale = scaleX.takeIf { it > 0f } ?: 1f
        ringPaint.strokeWidth = 3f * resources.displayMetrics.density / scale
        ringPaint.alpha = (ringAlpha * 230).toInt()
        canvas.drawCircle(ringX, ringY, 28f * resources.displayMetrics.density / scale, ringPaint)
    }

    private companion object {
        const val REPORT_MS = 50L
        const val RING_MS = 900L
    }
}
