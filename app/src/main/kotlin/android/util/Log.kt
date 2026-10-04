// Desktop stand-in for android.util.Log.
//
// Forty of the ported files log through Log.d/i/w/e with a tag. Keeping the
// class where they expect it means those files stay identical to the Android
// app. Output goes to stderr as "W/Tag: message", the shape logcat shows, so
// the in-app debug log collector can parse it the same way.
package android.util

import java.io.PrintWriter
import java.io.StringWriter

object Log {
    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6
    const val ASSERT = 7

    /** Minimum level that is printed; DEBUG and below are dropped unless raised. */
    @JvmStatic @Volatile var minLevel: Int = INFO

    /** Hook for the in-app log collector: every line goes here, printed or not. */
    @JvmStatic @Volatile var sink: ((level: Int, tag: String, message: String) -> Unit)? = null

    @JvmStatic fun v(tag: String, msg: String): Int = println(VERBOSE, tag, msg)
    @JvmStatic fun v(tag: String, msg: String, tr: Throwable?): Int = println(VERBOSE, tag, msg + stackTrace(tr))
    @JvmStatic fun d(tag: String, msg: String): Int = println(DEBUG, tag, msg)
    @JvmStatic fun d(tag: String, msg: String, tr: Throwable?): Int = println(DEBUG, tag, msg + stackTrace(tr))
    @JvmStatic fun i(tag: String, msg: String): Int = println(INFO, tag, msg)
    @JvmStatic fun i(tag: String, msg: String, tr: Throwable?): Int = println(INFO, tag, msg + stackTrace(tr))
    @JvmStatic fun w(tag: String, msg: String): Int = println(WARN, tag, msg)
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = println(WARN, tag, msg + stackTrace(tr))
    @JvmStatic fun w(tag: String, tr: Throwable?): Int = println(WARN, tag, stackTrace(tr).trimStart())
    @JvmStatic fun e(tag: String, msg: String): Int = println(ERROR, tag, msg)
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable?): Int = println(ERROR, tag, msg + stackTrace(tr))
    @JvmStatic fun wtf(tag: String, msg: String): Int = println(ASSERT, tag, msg)
    @JvmStatic fun wtf(tag: String, msg: String, tr: Throwable?): Int = println(ASSERT, tag, msg + stackTrace(tr))
    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = level >= minLevel

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val sw = StringWriter()
        tr.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /**
     * Every line reaches [sink], whatever its level: the in-app debug log shows
     * DEBUG lines as logcat did. [minLevel] only gates the console. The
     * console copy is printed under [isEmittingConsoleLine], which tells the
     * debug collector's stderr tee that the sink already has this line.
     */
    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int {
        sink?.invoke(priority, tag, msg)
        if (priority < minLevel) return 0
        val letter = when (priority) { VERBOSE -> 'V'; DEBUG -> 'D'; INFO -> 'I'; WARN -> 'W'; ERROR -> 'E'; else -> 'F' }
        val line = "$letter/$tag: $msg"
        emitting.set(true)
        try {
            System.err.println(line)
        } finally {
            emitting.set(false)
        }
        return line.length
    }

    private val emitting = ThreadLocal.withInitial { false }

    /** True while this thread is printing a Log line to the console. */
    @JvmStatic fun isEmittingConsoleLine(): Boolean = emitting.get()

    private fun stackTrace(tr: Throwable?): String = if (tr == null) "" else "\n" + getStackTraceString(tr)
}
