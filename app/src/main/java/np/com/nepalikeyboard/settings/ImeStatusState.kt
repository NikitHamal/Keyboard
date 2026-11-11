package np.com.nepalikeyboard.settings

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/**
 * Live view of "is this keyboard enabled / selected?".
 *
 * Three triggers keep it fresh, because the user leaves the app for the system keyboard screens and each OEM
 * behaves differently on return:
 *  1. `ON_RESUME` (coming back from system settings or the picker dialog),
 *  2. a [ContentObserver] on `enabled_input_methods` / `default_input_method` (fires the moment the toggle flips),
 *  3. a 1.2 s safety poll while the screen is resumed.
 */
@Composable
fun rememberImeStatus(): State<ImeStatus> {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(AppCatalog.status(context)) }

    DisposableEffect(owner, context) {
        val refresh = { state.value = AppCatalog.status(context) }
        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(lifecycleObserver)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        try {
            val cr = context.contentResolver
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS), false, observer)
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false, observer)
        } catch (_: RuntimeException) {
        }
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (_: RuntimeException) {
            }
        }
    }
    LaunchedEffect(owner, context) {
        while (true) {
            delay(1200)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val next = AppCatalog.status(context)
                if (next != state.value) state.value = next
            }
        }
    }
    return state
}
