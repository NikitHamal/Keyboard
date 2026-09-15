package com.nikit.nepalikeyboard.update

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * =============================================================================
 * IN-APP AUTO-UPDATER
 * =============================================================================
 *
 * Checks GitHub Releases for a newer APK and downloads + installs it without
 * leaving the app. This is the only code path in the entire application that
 * uses the INTERNET permission — the keyboard service itself never opens a
 * socket.
 *
 * ### Architecture
 *
 * The updater is a simple state machine observed by the settings UI:
 *
 *   IDLE  →  CHECKING  →  UPDATE_AVAILABLE / UP_TO_DATE / ERROR
 *                 ↓
 *          DOWNLOADING  →  DOWNLOADED / ERROR
 *                 ↓
 *          INSTALLING
 *
 * Every transition is published through [state] so the UI can react with a
 * banner, a progress bar, or a dismiss.
 *
 * ### Why GitHub Releases and not a custom server
 *
 * The APK is already published there by CI. Adding a second distribution
 * channel would mean two places to keep in sync and two failure modes to
 * diagnose. The Releases API is unauthenticated for public repos and returns
 * the asset URL directly — no scraping, no HTML parsing.
 *
 * ### Why PackageInstaller and not ACTION_VIEW on a file:// URI
 *
 * `ACTION_VIEW` with a file URI was removed in API 24 (FileUriExposedException).
 * `FileProvider` + `ACTION_INSTALL_PACKAGE` works but gives no progress
 * callback and no way to detect a cancelled install. `PackageInstaller` is
 * the modern API: it streams the APK into the system, reports progress, and
 * sends a PendingIntent when the user confirms or cancels.
 */
class UpdateManager private constructor(
    private val context: Context
) {

    /** The current state of the updater. */
    sealed class State {
        /** Not yet checked. */
        data object Idle : State()

        /** Querying the GitHub Releases API. */
        data object Checking : State()

        /** A newer version is available. */
        data class UpdateAvailable(
            val version: String,
            val downloadUrl: String,
            val sizeBytes: Long
        ) : State()

        /** The installed version is the latest. */
        data object UpToDate : State()

        /** Downloading the APK. [progress] is 0..100. */
        data class Downloading(val progress: Int) : State()

        /** APK downloaded, about to hand off to PackageInstaller. */
        data object Downloaded : State()

        /** Handing off to the system install prompt. */
        data object Installing : State()

        /** Something failed. [message] is a human-readable summary. */
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * The cached APK file. Kept in the app's cache directory so the system
     * can reclaim it if storage runs low.
     */
    private var cachedApk: File? = null

    /**
     * Checks GitHub Releases for a newer version.
     *
     * Safe to call multiple times: if a check is already in progress the call
     * is a no-op. The result is published through [state].
     */
    suspend fun checkForUpdate() {
        if (_state.value is State.Checking) return
        _state.value = State.Checking

        try {
            val release = withContext(Dispatchers.IO) { fetchLatestRelease() }
            if (release == null) {
                _state.value = State.UpToDate
                return
            }

            val (tagName, downloadUrl, sizeBytes) = release
            val remoteVersion = tagName.removePrefix("v").substringBefore("-")

            if (isNewer(remoteVersion, currentVersion())) {
                _state.value = State.UpdateAvailable(
                    version = remoteVersion,
                    downloadUrl = downloadUrl,
                    sizeBytes = sizeBytes
                )
            } else {
                _state.value = State.UpToDate
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Update check failed", t)
            _state.value = State.Error(t.message ?: "Update check failed")
        }
    }

    /**
     * Downloads the APK from [url] and streams it into the cache directory.
     *
     * Progress is published through [state] as [State.Downloading]. On
     * completion the state transitions to [State.Downloaded].
     */
    suspend fun downloadApk(url: String) {
        _state.value = State.Downloading(0)

        try {
            val file = withContext(Dispatchers.IO) {
                val target = File(context.cacheDir, "update.apk").apply {
                    if (exists()) delete()
                }
                downloadFile(url, target) { percent ->
                    _state.value = State.Downloading(percent)
                }
                target
            }
            cachedApk = file
            _state.value = State.Downloaded
        } catch (t: Throwable) {
            Log.w(TAG, "APK download failed", t)
            _state.value = State.Error(t.message ?: "Download failed")
        }
    }

    /**
     * Hands the downloaded APK to [PackageInstaller].
     *
     * The system shows its own confirmation dialog; the result arrives via
     * [InstallActivity]. This method does not block on the user's decision.
     */
    fun installApk(activity: Activity) {
        val file = cachedApk
        if (file == null || !file.exists()) {
            _state.value = State.Error("No APK to install")
            return
        }

        _state.value = State.Installing

        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            session.openWrite("update.apk", 0, file.length()).use { out: OutputStream ->
                file.inputStream().use { input ->
                    input.copyTo(out)
                }
                session.fsync(out)
            }

            // The intent fires when the user confirms or cancels the install
            // prompt. InstallActivity receives it and finishes immediately.
            val intent = Intent(context, InstallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                context, sessionId, intent, flags
            )

            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Install failed", t)
            _state.value = State.Error(t.message ?: "Install failed")
        }
    }

    /** Resets to idle so the user can check again. */
    fun reset() {
        _state.value = State.Idle
        cachedApk?.delete()
        cachedApk = null
    }

    // =========================================================================
    // Internals
    // =========================================================================

    private data class ReleaseInfo(
        val tagName: String,
        val downloadUrl: String,
        val sizeBytes: Long
    )

    private fun currentVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (t: Throwable) {
        "0.0.0"
    }

    /**
     * Fetches the latest release from GitHub's API.
     *
     * Returns null if there are no releases (the repo is new or releases are
     * disabled). Throws on network errors so the caller can surface them.
     */
    private fun fetchLatestRelease(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }

        return try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) {
                throw IllegalStateException("GitHub API returned ${conn.responseCode}")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tagName = json.getString("tag_name")

            // Find the APK asset.
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var apkSize: Long = 0
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    apkSize = asset.optLong("size", 0)
                    break
                }
            }

            if (apkUrl == null) return null

            ReleaseInfo(tagName, apkUrl, apkSize)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads a file with progress reporting.
     *
     * [onProgress] is called with 0..100 as bytes arrive. The callback runs
     * on the IO thread — the caller is responsible for dispatching to the
     * main thread if needed (the StateFlow collector in the UI handles this).
     */
    private fun downloadFile(url: String, target: File, onProgress: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }

        try {
            val total = conn.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1

            conn.inputStream.buffered().use { input ->
                target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Compares two semver strings. Returns true if [remote] is strictly newer
     * than [local].
     *
     * Handles versions like "1.0.0", "1.2.3", "2.0.0-beta1" (the pre-release
     * suffix is stripped before comparison).
     */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val l = local.substringBefore("-").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(r.size, l.size)
        for (i in 0 until maxLen) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv > lv) return true
            if (rv < lv) return false
        }
        return false
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val GITHUB_REPO = "NikitHamal/Keyboard"

        @Volatile
        private var instance: UpdateManager? = null

        fun get(context: Context): UpdateManager {
            return instance ?: synchronized(this) {
                instance ?: UpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
