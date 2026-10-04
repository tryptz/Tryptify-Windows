package tf.monochrome.desktop.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The window's stand-in for Android's toasts: one message at a time, newest
 * wins, shown for the duration the caller asked for. Posted from any thread
 * (the Toast shim is called from coroutines as often as from click handlers).
 */
object Toasts {
    const val SHORT_MS = 2_000L
    const val LONG_MS = 3_500L

    data class Message(val text: String, val durationMs: Long, val id: Long)

    private val _current = MutableStateFlow<Message?>(null)
    val current: StateFlow<Message?> = _current.asStateFlow()
    private var nextId = 0L

    @Synchronized
    fun post(text: String, durationMs: Long = SHORT_MS) {
        _current.value = Message(text, durationMs, nextId++)
    }

    fun dismiss() {
        _current.value = null
    }

    @Synchronized
    internal fun expire(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

/** Draws the current toast over the window content; place it last in the root Box. */
@Composable
fun BoxScope.ToastHost() {
    val message by Toasts.current.collectAsState()
    LaunchedEffect(message?.id) {
        val m = message ?: return@LaunchedEffect
        delay(m.durationMs)
        Toasts.expire(m.id)
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
    ) {
        Box(
            Modifier
                .widthIn(max = 480.dp)
                .background(Color(0xE6202020), RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(message?.text.orEmpty(), color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
