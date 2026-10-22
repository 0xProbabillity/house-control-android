package dev.housecontrol.android.core

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small text log of what was sent to real devices and what the cloud answered:
 * `events.log` in the app's private storage. It is the first thing to look at when a
 * device does not react. Never contains tokens; it stays on this phone.
 */
class EventLog(directory: File) {
    val file = File(directory, "events.log")
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    @Synchronized
    fun write(line: String) {
        val text = "${stamp.format(Date())} $line\n"
        try {
            // Keep the newer half once the file has grown past the limit.
            if (file.length() > LIMIT) {
                val tail = file.readText().takeLast(LIMIT / 2)
                file.writeText(tail.substringAfter('\n') + text)
            } else {
                file.appendText(text)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val LIMIT = 256 * 1024

        fun describe(commands: List<JSONObject>): String =
            commands.joinToString(" ") { "${it.opt("code") ?: "?"}=${it.opt("value") ?: "?"}" }
    }
}
