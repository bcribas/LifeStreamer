package com.dimadesu.lifestreamer.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What happened during a live, kept on the phone: network changes, the SRT link going down and
 * up, heat, and every few seconds what goes out. Android's own log keeps a few minutes of the
 * app at best, which left nothing to look at the day after a live went wrong.
 *
 * One file per day in the app's files (`diagnostics/journal-YYYY-MM-DD.log`), the last
 * [KEEP_DAYS] days kept. Writing happens on its own coroutine: a caller never waits for storage.
 */
class DiagnosticsLog(context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "diagnostics")
    private val lines = Channel<String>(capacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    init {
        scope.launch(Dispatchers.IO) {
            runCatching { dir.mkdirs() }
            prune()
            for (line in lines) {
                runCatching {
                    File(dir, "journal-${day.format(Date())}.log").appendText(line + "\n")
                }.onFailure { Log.w(TAG, "Could not write the journal: ${it.message}") }
            }
        }
    }

    /** One line: when, what kind ("link", "net", "heat", "stats"...), and what. */
    fun event(kind: String, text: String) {
        val stamp = synchronized(time) { time.format(Date()) }
        lines.trySend("$stamp $kind $text")
    }

    private fun prune() {
        val files = dir.listFiles { file -> file.name.startsWith("journal-") }?.sortedBy { it.name } ?: return
        files.dropLast(KEEP_DAYS).forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val TAG = "DiagnosticsLog"
        const val KEEP_DAYS = 7
    }
}
