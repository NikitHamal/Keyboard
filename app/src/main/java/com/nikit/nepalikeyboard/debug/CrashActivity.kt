package com.nikit.nepalikeyboard.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikit.nepalikeyboard.R
import com.nikit.nepalikeyboard.settings.SettingsActivity
import com.nikit.nepalikeyboard.ui.theme.NepaliKeyboardTheme

/**
 * =============================================================================
 * CRASH / DEBUG SCREEN
 * =============================================================================
 *
 * Shows the last recorded crash with a monospace, selectable, copyable trace —
 * and, more importantly, a **Restart** button, because a keyboard that has died
 * leaves the user with no keyboard at all and no obvious way back.
 *
 * ### Why this is an Activity and not a Compose overlay in Settings
 *
 * A crash can happen before the settings screen exists, and can be caused by
 * the settings screen itself. Making the crash surface a separate, minimal
 * activity means the one screen whose job is to explain a failure has the
 * fewest possible dependencies of its own. It composes nothing but text and
 * buttons, so there is very little left in it that can break.
 *
 * ### The three actions, and why each exists
 *
 *  * **Copy** — puts the whole report on the clipboard. This is what makes the
 *    screen useful: the user can paste it into a bug report, a chat, or an
 *    issue tracker without needing `adb` or a log file.
 *  * **Restart** — clears the pending flag and relaunches the settings screen,
 *    then finishes. For an IME this is the action that actually helps, because
 *    the crash usually killed the keyboard process that the editor is still
 *    bound to.
 *  * **Clear** — deletes the stored reports so they stop being surfaced. Offered
 *    because a stale crash shown forever trains the user to ignore it.
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Reading the stored report is a disk read on a tiny file, but it is
        // still done once here rather than in the composition so that
        // recomposition never touches the filesystem.
        val report = CrashStore.lastCrash(this)
            ?: "No crash report found.\n\n" +
                "If the app closed without a message, the crash may have occurred " +
                "before the handler could be installed — for example during " +
                "Application.onCreate. Check `adb logcat -b crash`."

        val all = CrashStore.allCrashes(this)

        setContent {
            // Deliberately theme-independent of user preferences: a crash screen
            // that fails to render because a preference could not be read would
            // be an unusually cruel bug.
            NepaliKeyboardTheme {
                CrashScreen(
                    report = report,
                    historyCount = all.size,
                    onCopy = { copyToClipboard(report) },
                    onRestart = { restartApp() },
                    onClear = {
                        CrashStore.clear(this)
                        PendingCrash.consume(this)
                        restartApp()
                    },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }

    /**
     * Copies the report to the system clipboard.
     *
     * `ClipboardManager` directly rather than Compose's `LocalClipboardManager`:
     * on Android 13+ the Compose wrapper shows the system's own "copied"
     * confirmation, and doubling it with our toast looks like a bug.
     */
    private fun copyToClipboard(text: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Nepali Keyboard crash", text))
            Toast.makeText(this, "Crash report copied", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not copy: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Relaunches the app cleanly.
     *
     * A fresh task with `CLEAR_TASK` is what actually recovers the keyboard: the
     * crashed process is gone, the editor is bound to a dead input method, and
     * only a new process will be offered as a replacement. Launching Settings
     * rather than the keyboard is correct — the user cannot "open" an IME
     * directly; they enable it and focus a field.
     */
    private fun restartApp() {
        try {
            PendingCrash.consume(this)
            val intent = Intent(this, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        } catch (t: Throwable) {
            // Nothing useful to do; fall through to finishing.
        } finally {
            finish()
        }
    }
}

/**
 * The crash screen's content.
 *
 * Stateless and action-callback driven so it can be previewed and reasoned
 * about without an `Activity`. Every button takes a lambda; nothing here
 * reaches for a `Context`.
 */
@Composable
private fun CrashScreen(
    report: String,
    historyCount: Int,
    onCopy: () -> Unit,
    onRestart: () -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResourceSafe(R.string.crash_screen_title, "Keyboard stopped")) },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Filled.BugReport,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResourceSafe(
                    R.string.crash_screen_explanation,
                    "The keyboard stopped unexpectedly. The report below is what " +
                        "the app captured at the moment it failed. Copy it if you " +
                        "want to report the problem."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The trace itself. `verticalScroll` rather than a lazy list because
            // the report is a single text blob; a LazyColumn would add item
            // machinery for no benefit and complicate selection.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 1.dp
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp)
                ) {
                    Text(
                        text = report,
                        // Monospace and small: stack traces align meaningfully by
                        // indentation, and `selectionContainer` behaviour comes
                        // from the platform because this Text is selectable by
                        // default inside a scrollable container on API 26+.
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (historyCount > 0) {
                Text(
                    text = stringResourceSafe(
                        R.string.crash_screen_history,
                        "%d stored report(s) in internal storage.",
                        historyCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Primary action first and full width: restarting is what the user
            // almost always wants. Copy is secondary because it is only useful
            // when they intend to report the crash.
            Button(
                onClick = onRestart,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResourceSafe(R.string.crash_screen_restart, "Restart keyboard app"))
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResourceSafe(R.string.crash_screen_copy, "Copy"))
                }

                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResourceSafe(R.string.crash_screen_settings, "Settings"))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResourceSafe(R.string.crash_screen_clear, "Clear and restart"))
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * `stringResource` with a fallback.
 *
 * A crash screen must render even if the resource lookup fails — for instance
 * if the crash happened in resource loading itself. Returning the literal
 * English text keeps the screen usable instead of substituting an exception.
 *
 * `formatArgs` are forwarded to the resource as format arguments, so a resource
 * like `%1$s` substitutes properly.
 */
@Composable
private fun stringResourceSafe(resId: Int, fallback: String, vararg formatArgs: Any): String =
    try {
        if (formatArgs.isEmpty()) stringResource(resId) else stringResource(resId, *formatArgs)
    } catch (t: Throwable) {
        if (formatArgs.isEmpty()) fallback else fallback.format(*formatArgs)
    }
