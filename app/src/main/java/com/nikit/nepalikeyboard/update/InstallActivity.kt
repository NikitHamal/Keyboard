package com.nikit.nepalikeyboard.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.util.Log

/**
 * Transparent activity that receives the [PackageInstaller] callback when the
 * user confirms or cancels the system install prompt.
 *
 * ### Why an Activity and not a BroadcastReceiver
 *
 * `PackageInstaller.Session.commit` accepts an `IntentSender`, which can be
 * either a `PendingIntent.getActivity` or a `PendingIntent.getBroadcast`. A
 * broadcast receiver would work, but on API 26+ a manifest-registered receiver
 * for an implicit broadcast is subject to the background-execution limits. An
 * explicit activity is unconditional and works on every API level the app
 * supports (26+).
 *
 * ### Why it finishes immediately
 *
 * The activity exists only to observe the result. It has no UI of its own —
 * the system's install prompt is the UI. Once the user has acted, there is
 * nothing left to show, so we finish before the user ever sees a blank screen.
 */
class InstallActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The system wants the user to confirm. Launch the confirmation
                // intent and stay alive until it returns.
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    startActivity(confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // Don't finish yet — wait for onResume after the user acts.
                return
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "APK installed successfully")
                // The system will restart the app process with the new version.
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {
                Log.w(TAG, "Install failed: status=$status message=$message")
            }
        }

        finish()
    }

    override fun onResume() {
        super.onResume()
        // If we reach onResume without STATUS_PENDING_USER_ACTION, the user
        // has already acted (confirmed or cancelled). Finish.
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        if (status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            finish()
        }
    }

    companion object {
        private const val TAG = "InstallActivity"
    }
}
