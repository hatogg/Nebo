package ua.nebo.watch

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Останні рядки журналу (наші й SDK) — щоб користувач міг надіслати їх розробнику. */
object LogBuffer : Timber.Tree() {
    private const val MAX = 1500
    private val lines = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val p = when (priority) {
            Log.VERBOSE -> 'V'; Log.DEBUG -> 'D'; Log.INFO -> 'I'; Log.WARN -> 'W'; Log.ERROR -> 'E'; else -> 'A'
        }
        val line = buildString {
            append(synchronized(fmt) { fmt.format(Date()) }).append(' ').append(p).append(' ')
            append(tag ?: "-").append(": ").append(message.take(600))
            if (t != null && !message.contains(t.javaClass.simpleName)) append(" | ").append(t.toString())
        }
        synchronized(lines) {
            if (lines.size >= MAX) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun dump(): String = synchronized(lines) { lines.joinToString("\n") }
}
