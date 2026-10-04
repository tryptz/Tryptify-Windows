package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import java.awt.EventQueue

class ManagedActivityResultLauncher<I, O> internal constructor(
    override val contract: ActivityResultContract<I, O>,
    private val onResult: State<(O) -> Unit>,
) : ActivityResultLauncher<I>() {
    /**
     * Runs after the current event, as Android delivers results after the
     * click that launched them; the dialog then blocks only its own loop.
     */
    override fun launch(input: I) {
        EventQueue.invokeLater { onResult.value(contract.perform(input)) }
    }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> {
    val current = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract, current) }
}
