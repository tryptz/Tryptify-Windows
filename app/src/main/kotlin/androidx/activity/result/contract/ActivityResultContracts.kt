package androidx.activity.result.contract

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResult
import tf.monochrome.desktop.platform.FilePickers

/**
 * A contract is what to ask for and what comes back. On Android the system
 * ran it in another activity; here [perform] runs the desktop equivalent on
 * the UI thread and returns the same result type, so the screens' result
 * handlers are unchanged. Picked files come back as `file:` URIs, which the
 * Context shim's contentResolver opens.
 */
abstract class ActivityResultContract<I, O> {
    abstract fun perform(input: I): O
}

object ActivityResultContracts {
    /** Input: a MIME filter such as "text/" + "*". */
    class GetContent : ActivityResultContract<String, Uri?>() {
        override fun perform(input: String): Uri? = FilePickers.openFile(listOf(input))?.let(Uri::fromFile)
    }

    /** Input: accepted MIME types. */
    class OpenDocument : ActivityResultContract<Array<String>, Uri?>() {
        override fun perform(input: Array<String>): Uri? = FilePickers.openFile(input.toList())?.let(Uri::fromFile)
    }

    /** Input: a starting location, ignored like most Android pickers do. */
    class OpenDocumentTree : ActivityResultContract<Uri?, Uri?>() {
        override fun perform(input: Uri?): Uri? = FilePickers.chooseFolder()?.let(Uri::fromFile)
    }

    /** Input: the suggested file name. */
    class CreateDocument(private val mimeType: String = "*/*") : ActivityResultContract<String, Uri?>() {
        override fun perform(input: String): Uri? = FilePickers.saveFile(input, mimeType)?.let(Uri::fromFile)
    }

    /** Desktop apps hold no runtime permissions: everything Android asked for is already allowed. */
    class RequestPermission : ActivityResultContract<String, Boolean>() {
        override fun perform(input: String): Boolean = true
    }

    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>() {
        override fun perform(input: Array<String>): Map<String, Boolean> = input.associateWith { true }
    }

    /** Android-only activities (screen capture consent, system settings) report a cancel. */
    class StartActivityForResult : ActivityResultContract<Intent, ActivityResult>() {
        override fun perform(input: Intent): ActivityResult = ActivityResult(ActivityResult.RESULT_CANCELED, null)
    }
}
