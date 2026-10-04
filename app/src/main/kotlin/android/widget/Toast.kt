package android.widget

import android.content.Context
import tf.monochrome.desktop.platform.Toasts
import tf.monochrome.desktop.res.StringKey

/**
 * Android's Toast, for the 37 call sites in the ported screens. A desktop
 * window has no system toast, so [show] hands the text to [Toasts], which the
 * window root draws as a transient message at the bottom of the window.
 */
class Toast private constructor(private val text: CharSequence, private val duration: Int) {
    fun show() {
        Toasts.post(text.toString(), if (duration == LENGTH_LONG) Toasts.LONG_MS else Toasts.SHORT_MS)
    }

    fun cancel() = Toasts.dismiss()

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1

        @Suppress("UNUSED_PARAMETER")
        fun makeText(context: Context?, text: CharSequence, duration: Int): Toast = Toast(text, duration)

        fun makeText(context: Context?, resId: StringKey, duration: Int): Toast =
            Toast(context?.getString(resId) ?: tf.monochrome.desktop.res.Strings.get(resId), duration)
    }
}
