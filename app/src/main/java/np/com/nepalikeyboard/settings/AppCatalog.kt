package np.com.nepalikeyboard.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Immutable
import np.com.nepalikeyboard.ime.NepaliImeService
import np.com.nepalikeyboard.util.KeyboardLog

/** How the system currently sees this keyboard. */
@Immutable
data class ImeStatus(
    val installed: Boolean = false,
    val enabled: Boolean = false,
    val selected: Boolean = false,
) {
    val ready: Boolean get() = enabled && selected
}

/**
 * Read-only view of the platform input-method state.
 *
 * The onboarding wizard needs three facts - is the IME installed for this user,
 * enabled in settings, picked as the current one - and the only way to obtain
 * them is through [InputMethodManager]. Nothing here mutates anything: Android
 * gives no API to enable or select an IME programmatically (by design), so the
 * wizard can only deep-link to the right settings screen and then poll.
 */
object AppCatalog {

    /** Our IME as a [ComponentName] (the platform identifies IMEs by `ComponentName.flattenToShortString()`). */
    fun component(context: Context): ComponentName = ComponentName(context.packageName, NepaliImeService::class.java.name)

    /**
     * Component id of our own IME service in the platform's own notation (`pkg/.ime.NepaliImeService`).
     * The previous implementation compared against `pkg/<full class name>`, which never matched
     * `InputMethodInfo.id` - that is why the setup status never turned green.
     */
    fun imeId(context: Context): String = component(context).flattenToShortString()

    private fun same(id: String?, cn: ComponentName): Boolean {
        if (id.isNullOrBlank()) return false
        return ComponentName.unflattenFromString(id.substringBefore(';')) == cn
    }

    fun status(context: Context): ImeStatus {
        val cn = component(context)
        var installed = false
        var enabled = false
        try {
            val manager = inputMethodManager(context)
            installed = manager?.inputMethodList.orEmpty().any { same(it.id, cn) }
            enabled = manager?.enabledInputMethodList.orEmpty().any { same(it.id, cn) }
        } catch (error: RuntimeException) {
            KeyboardLog.w("IME status query failed: ${error.message}")
        }
        // The secure settings are the source of truth the system settings screen uses; they also
        // cover the (rare) OEM builds where the IMM lists are filtered.
        try {
            val cr = context.contentResolver
            val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
            if (list.split(':').any { same(it, cn) }) enabled = true
        } catch (error: RuntimeException) {
            KeyboardLog.w("Unable to read enabled_input_methods: ${error.message}")
        }
        val selected = same(selectedImeId(context), cn)
        return ImeStatus(installed = installed || enabled, enabled = enabled || selected, selected = selected)
    }

    /** Every enabled keyboard except ours, so the user can see where they are. */
    fun otherEnabledKeyboards(context: Context): List<InputMethodInfo> {
        val manager = inputMethodManager(context) ?: return emptyList()
        return try {
            val cn = component(context)
            manager.enabledInputMethodList.orEmpty().filter { !same(it.id, cn) }
        } catch (error: RuntimeException) {
            KeyboardLog.w("IME list query failed: ${error.message}")
            emptyList()
        }
    }

    /** Opens the system "on-screen keyboards" list where the IME can be enabled. */
    fun openInputMethodSettings(context: Context) = start(
        context = context,
        action = Settings.ACTION_INPUT_METHOD_SETTINGS,
    )

    /**
     * Opens the picker. On several OEM skins this is the only way to also *select*
     * the keyboard, so the wizard offers it as the second step.
     */
    fun showInputMethodPicker(context: Context) {
        val manager = inputMethodManager(context)
        try {
            manager?.showInputMethodPicker()
        } catch (error: RuntimeException) {
            KeyboardLog.w("showInputMethodPicker failed: ${error.message}")
            openInputMethodSettings(context)
        }
    }

    private fun start(context: Context, action: String) {
        try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (error: RuntimeException) {
            KeyboardLog.e("Unable to open settings screen", error)
        }
    }

    private fun inputMethodManager(context: Context): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager

    /**
     * Which IME is currently selected.
     *
     * `InputMethodManager` exposes no public getter for this, so the secure
     * setting the framework itself maintains (`default_input_method`) is read
     * instead - the same value the system settings screen shows, read-only, no
     * permission, and no hidden API. `null` means "unknown", which the wizard
     * treats as "show the picker step anyway" rather than lying to the user.
     */
    fun selectedImeId(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    } catch (error: RuntimeException) {
        KeyboardLog.w("Unable to read default_input_method: ${error.message}")
        null
    }
}
