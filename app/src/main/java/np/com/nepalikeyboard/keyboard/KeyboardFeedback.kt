package np.com.nepalikeyboard.keyboard

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Immutable
import java.lang.ref.WeakReference
import np.com.nepalikeyboard.data.FeedbackStrength
import np.com.nepalikeyboard.util.KeyboardLog

/** The subset of settings the keystroke path needs to know about. */
@Immutable
data class FeedbackConfig(
    val hapticsEnabled: Boolean,
    val soundEnabled: Boolean,
    val strength: FeedbackStrength,
) {
    companion object {
        val Off = FeedbackConfig(hapticsEnabled = false, soundEnabled = false, strength = FeedbackStrength.MEDIUM)
    }
}

/**
 * Keypress haptics and audio.
 *
 * Haptics use `View.performHapticFeedback` with the constants the platform
 * exposes, which is exactly what the IME design guidelines recommend: it costs
 * no permissions (no `VIBRATE`), respects the user's global haptic setting
 * unless explicitly overridden, and never allocates a `VibrationEffect`.
 *
 * Strength maps onto three built-in constants rather than a raw amplitude, which
 * is the only way to vary intensity without the vibrator permission.
 *
 * Audio uses a lazily created [ToneGenerator]. IME windows are not focusable, so
 * `AudioManager.playSoundEffect` is not available to them; the tone generator is
 * the standard alternative. It is created on first use and released with the
 * input view.
 */
class KeyboardFeedback(rootView: View) {

    private val viewRef = WeakReference(rootView)
    private var toneGenerator: ToneGenerator? = null
    private var toneFailed = false

    fun onKeyPress(config: FeedbackConfig) {
        if (config.hapticsEnabled) {
            perform(mappedConstant(config.strength))
        }
        if (config.soundEnabled) playTone()
    }

    fun onLongPress(config: FeedbackConfig) {
        if (config.hapticsEnabled) {
            perform(HapticFeedbackConstants.LONG_PRESS or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
        if (config.soundEnabled) playTone()
    }

    /** Lighter tick while gliding the caret along the space bar. */
    fun onCursorStep(config: FeedbackConfig) {
        if (config.hapticsEnabled) {
            perform(HapticFeedbackConstants.TEXT_HANDLE_MOVE or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
    }

    /** Heavier tick for each word consumed by swipe-to-delete. */
    fun onDeleteStep(config: FeedbackConfig) {
        if (config.hapticsEnabled) {
            perform(HapticFeedbackConstants.CONTEXT_CLICK or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        }
    }

    /** Called when the input view is torn down. */
    fun release() {
        try {
            toneGenerator?.release()
        } catch (error: IllegalStateException) {
            KeyboardLog.w("ToneGenerator release failed: ${error.message}")
        }
        toneGenerator = null
    }

    private fun perform(constant: Int) {
        val view = viewRef.get() ?: return
        if (!view.isAttachedToWindow) return
        try {
            view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
        } catch (_: RuntimeException) {
            // Some OEMs throw when haptics are disabled system-wide.
        }
    }

    private fun mappedConstant(strength: FeedbackStrength): Int = when (strength) {
        FeedbackStrength.LIGHT -> HapticFeedbackConstants.KEYBOARD_TAP or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        FeedbackStrength.MEDIUM -> HapticFeedbackConstants.VIRTUAL_KEY or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        FeedbackStrength.STRONG -> HapticFeedbackConstants.LONG_PRESS or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    }

    private fun playTone() {
        if (toneFailed) return
        val generator = toneGenerator ?: createToneGenerator() ?: return
        try {
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
        } catch (_: RuntimeException) {
            toneFailed = true
        }
    }

    private fun createToneGenerator(): ToneGenerator? {
        if (toneFailed) return null
        return try {
            ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME_PERCENT).also { toneGenerator = it }
        } catch (error: RuntimeException) {
            // Audio hardware can be unavailable (call in progress, ringer mode).
            KeyboardLog.w("ToneGenerator unavailable: ${error.message}")
            toneFailed = true
            null
        }
    }

    private companion object {
        const val TONE_VOLUME_PERCENT = 24
        const val TONE_DURATION_MS = 22
    }
}
