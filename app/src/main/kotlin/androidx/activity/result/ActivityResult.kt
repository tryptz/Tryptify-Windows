package androidx.activity.result

import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/** Android's result of a started activity; the desktop only ever reports a cancel. */
data class ActivityResult(val resultCode: Int, val data: Intent?) {
    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}

/** Launches a contract's desktop equivalent (a file dialog, mostly). */
abstract class ActivityResultLauncher<I> {
    abstract val contract: ActivityResultContract<I, *>
    abstract fun launch(input: I)
    open fun unregister() = Unit
}

/** `launcher.launch()` for contracts whose input is Unit, as on Android. */
fun ActivityResultLauncher<Unit>.launch() = launch(Unit)
