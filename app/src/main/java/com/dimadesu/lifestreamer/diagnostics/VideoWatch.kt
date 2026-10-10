package com.dimadesu.lifestreamer.diagnostics

import java.util.Locale

/**
 * Whether the live sends a picture, and why not when it does not.
 *
 * On 2026-10-10 unplugging the car charger woke the lock screen, face unlock took the cameras,
 * and for 25 minutes the live sent sound alone while the phone said "streaming, 30.9 fps,
 * 4 Mbps". Two things are watched, because each misses what the other sees:
 * - each camera the system takes, while the app gets it back ([onCamera]): in a composition the
 *   last frame keeps being sent, so the encoder alone does not notice;
 * - frames out of the encoder ([onTick]): whatever stops them, a camera or not.
 *
 * Returns the journal's lines; [problem] is what the operator sees meanwhile. Plain Kotlin,
 * tested on the JVM; called from the status tick and from the cameras' collectors.
 */
class VideoWatch(private val noFramesMs: Long = NO_FRAMES_MS) {

    private class Away(val name: String, val reason: String, val sinceMs: Long)

    private val away = LinkedHashMap<Any, Away>()
    private var lastCount: Long? = null
    private var lastProgressMs = 0L
    private var stalledSinceMs: Long? = null

    /** The camera behind [key] is away for [reason], or back when null. */
    @Synchronized
    fun onCamera(key: Any, name: String, reason: String?, nowMs: Long): String? {
        val was = away[key]
        return when {
            reason != null && was == null -> {
                away[key] = Away(name, reason, nowMs)
                "camera $name lost ($reason): reopening it"
            }
            reason != null && was != null && was.reason != reason -> {
                away[key] = Away(name, reason, was.sinceMs)
                "camera $name still away: $reason"
            }
            reason == null && was != null -> {
                away.remove(key)
                "camera $name back after ${seconds(nowMs - was.sinceMs)}"
            }
            else -> null
        }
    }

    /** The camera behind [key] is no longer used: nothing to wait for. */
    @Synchronized
    fun forget(key: Any) {
        away.remove(key)
    }

    /** Every tick: whether the live is on, and how many frames the encoder has put out. */
    @Synchronized
    fun onTick(nowMs: Long, live: Boolean, frameCount: Long?): String? {
        if (!live || frameCount == null) {
            lastCount = null
            stalledSinceMs = null
            return null
        }
        val previous = lastCount
        lastCount = frameCount
        // A new encoder counts from zero again, which is progress too
        if (previous == null || frameCount != previous) {
            lastProgressMs = nowMs
            val stalled = stalledSinceMs ?: return null
            stalledSinceMs = null
            return "video frames back after ${seconds(nowMs - stalled)}"
        }
        if (stalledSinceMs == null && nowMs - lastProgressMs >= noFramesMs) {
            stalledSinceMs = lastProgressMs
            val cameras = if (away.isEmpty()) "" else "; away: " + away.values.joinToString { it.name }
            return "no video frames for ${seconds(nowMs - lastProgressMs)}$cameras"
        }
        return null
    }

    /** What the operator should see now, or null while the picture goes out. */
    @Synchronized
    fun problem(nowMs: Long): String? {
        if (away.isNotEmpty()) {
            val names = away.values.joinToString(" and ") { it.name }
            val reason = away.values.first().reason
            val since = away.values.minOf { it.sinceMs }
            return "No picture from $names for ${seconds(nowMs - since)}: $reason. Reopening…"
        }
        val since = stalledSinceMs ?: return null
        return "No video for ${seconds(nowMs - since)}: the encoder gets no frames"
    }

    companion object {
        /** Long enough for an encoder starting up, short enough to say so while it matters. */
        const val NO_FRAMES_MS = 5_000L

        private fun seconds(ms: Long) = String.format(Locale.US, "%.0f s", ms.coerceAtLeast(0) / 1000.0)
    }
}
