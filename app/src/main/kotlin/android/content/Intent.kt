package android.content

import android.net.Uri

/**
 * The few intents the ported screens fire: open a link, share text or a file,
 * and the permission flags SAF calls pass. [Context.startActivity] turns them
 * into their desktop meaning (browser, Explorer, clipboard); anything else
 * throws [ActivityNotFoundException], which those call sites already handle.
 */
class Intent(var action: String? = null, var data: Uri? = null) {
    private val extras = LinkedHashMap<String, Any?>()
    var type: String? = null
    var flags: Int = 0
        private set

    constructor(action: String?) : this(action, null)

    fun setAction(action: String?): Intent = apply { this.action = action }
    fun setData(data: Uri?): Intent = apply { this.data = data }
    fun setType(type: String?): Intent = apply { this.type = type }
    fun setDataAndType(data: Uri?, type: String?): Intent = apply { this.data = data; this.type = type }
    fun addFlags(flags: Int): Intent = apply { this.flags = this.flags or flags }
    fun setFlags(flags: Int): Intent = apply { this.flags = flags }
    fun putExtra(name: String, value: Any?): Intent = apply { extras[name] = value }
    fun getStringExtra(name: String): String? = extras[name] as? String
    @Suppress("UNCHECKED_CAST")
    fun <T> getExtra(name: String): T? = extras[name] as? T
    fun hasExtra(name: String): Boolean = name in extras

    companion object {
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_CHOOSER = "android.intent.action.CHOOSER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        const val EXTRA_STREAM = "android.intent.extra.STREAM"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
        const val EXTRA_INTENT = "android.intent.extra.INTENT"
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002
        const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

        /** The chooser is the desktop action itself; the title has nowhere to go. */
        fun createChooser(target: Intent, @Suppress("UNUSED_PARAMETER") title: CharSequence?): Intent =
            Intent(ACTION_CHOOSER).putExtra(EXTRA_INTENT, target)
    }
}

class ActivityNotFoundException(message: String? = null) : RuntimeException(message)
