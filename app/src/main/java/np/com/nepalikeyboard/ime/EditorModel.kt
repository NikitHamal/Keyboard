package np.com.nepalikeyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Immutable
import np.com.nepalikeyboard.R
import np.com.nepalikeyboard.keyboard.LayoutId
import np.com.nepalikeyboard.util.KeyboardLog

/** Coarse editor classification derived from `EditorInfo.inputType`. */
enum class EditorKind {
    TEXT,
    MULTILINE,
    EMAIL,
    URI,
    PASSWORD,
    NUMBER,
    DECIMAL,
    PHONE,
    DATE,
    TIME,
}

/**
 * Immutable, pre-digested view of the current `EditorInfo`.
 *
 * `EditorInfo` is a mutable framework object that can be recycled by the system;
 * copying the fields we care about into this descriptor is what lets the rest of
 * the app reason about the editor without holding a reference to it.
 */
@Immutable
data class EditorDescriptor(
    val kind: EditorKind,
    val sensitive: Boolean,
    val autoCorrectAllowed: Boolean,
    val sentenceCapitalization: Boolean,
    val multiline: Boolean,
    val action: Int,
    val actionLabelRes: Int,
    val customActionLabel: String?,
    val noEnterAction: Boolean,
    val suggestedLayout: LayoutId,
    val packageName: String?,
) {
    /** True when Enter must insert a newline instead of performing an action. */
    val enterInsertsNewline: Boolean
        get() = multiline || noEnterAction || action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED

    companion object {
        val Text = EditorDescriptor(
            kind = EditorKind.TEXT,
            sensitive = false,
            autoCorrectAllowed = true,
            sentenceCapitalization = true,
            multiline = false,
            action = EditorInfo.IME_ACTION_UNSPECIFIED,
            actionLabelRes = R.string.action_default,
            customActionLabel = null,
            noEnterAction = false,
            suggestedLayout = LayoutId.ROMAN,
            packageName = null,
        )

        /**
         * Digests an [EditorInfo]. Never throws: an unexpected combination of
         * flags degrades to a plain text editor rather than crashing the IME.
         */
        fun from(info: EditorInfo?): EditorDescriptor {
            if (info == null) return Text
            return try {
                val inputType = info.inputType
                val variation = inputType and InputType.TYPE_MASK_VARIATION
                val clazz = inputType and InputType.TYPE_MASK_CLASS

                val sensitive = isSensitive(inputType, info)
                val kind = classify(clazz, variation, sensitive)
                val multiline = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 &&
                    (clazz == InputType.TYPE_CLASS_TEXT)
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
                val actionLabel = if (info.actionLabel != null) info.actionLabel.toString() else null
                val actionLabelRes = actionLabelResFor(action, info.actionId)
                val personalizedLearningBlocked =
                    (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

                EditorDescriptor(
                    kind = kind,
                    sensitive = sensitive,
                    autoCorrectAllowed = !sensitive && !personalizedLearningBlocked &&
                        (clazz == InputType.TYPE_CLASS_TEXT),
                    sentenceCapitalization =
                        (inputType and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0,
                    multiline = multiline,
                    action = action,
                    actionLabelRes = actionLabelRes,
                    customActionLabel = actionLabel,
                    noEnterAction = noEnterAction,
                    suggestedLayout = suggestedLayout(kind, clazz),
                    packageName = info.packageName,
                )
            } catch (error: RuntimeException) {
                KeyboardLog.w("EditorInfo digest failed: ${error.message}")
                Text
            }
        }

        private fun isSensitive(inputType: Int, info: EditorInfo): Boolean {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val clazz = inputType and InputType.TYPE_MASK_CLASS
            if (clazz == InputType.TYPE_CLASS_TEXT) {
                if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                ) {
                    return true
                }
            }
            if (clazz == InputType.TYPE_CLASS_NUMBER &&
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return true
            }
            // Fields that explicitly opt out of personalized learning are treated
            // as sensitive: no suggestions, no learning, no clipboard capture.
            if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
            return false
        }

        private fun classify(clazz: Int, variation: Int, sensitive: Boolean): EditorKind = when (clazz) {
            InputType.TYPE_CLASS_TEXT -> when {
                sensitive -> EditorKind.PASSWORD
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> EditorKind.EMAIL
                variation == InputType.TYPE_TEXT_VARIATION_URI -> EditorKind.URI
                else -> EditorKind.TEXT
            }

            InputType.TYPE_CLASS_NUMBER -> when (variation) {
                InputType.TYPE_NUMBER_VARIATION_PASSWORD -> EditorKind.PASSWORD
                InputType.TYPE_NUMBER_VARIATION_NORMAL -> EditorKind.NUMBER
                else -> EditorKind.NUMBER
            }

            InputType.TYPE_CLASS_PHONE -> EditorKind.PHONE

            InputType.TYPE_CLASS_DATETIME -> when (variation) {
                InputType.TYPE_DATETIME_VARIATION_TIME -> EditorKind.TIME
                else -> EditorKind.DATE
            }

            else -> if (sensitive) EditorKind.PASSWORD else EditorKind.TEXT
        }

        private fun suggestedLayout(kind: EditorKind, clazz: Int): LayoutId = when (kind) {
            EditorKind.NUMBER, EditorKind.DECIMAL -> LayoutId.NUMERIC
            EditorKind.PHONE -> LayoutId.PHONE
            EditorKind.EMAIL -> LayoutId.EMAIL
            EditorKind.URI -> LayoutId.URI
            EditorKind.PASSWORD -> if (clazz == InputType.TYPE_CLASS_NUMBER) LayoutId.NUMERIC else LayoutId.ENGLISH
            EditorKind.DATE, EditorKind.TIME -> LayoutId.NUMERIC
            EditorKind.MULTILINE, EditorKind.TEXT -> LayoutId.ROMAN
        }

        private fun actionLabelResFor(action: Int, actionId: Int): Int = when (action) {
            EditorInfo.IME_ACTION_GO -> R.string.action_go
            EditorInfo.IME_ACTION_SEARCH -> R.string.action_search
            EditorInfo.IME_ACTION_SEND -> R.string.action_send
            EditorInfo.IME_ACTION_NEXT -> R.string.action_next
            EditorInfo.IME_ACTION_DONE -> R.string.action_done
            EditorInfo.IME_ACTION_PREVIOUS -> R.string.action_previous
            EditorInfo.IME_ACTION_NONE -> R.string.action_none
            else -> if (actionId != 0) R.string.action_default else R.string.action_default
        }
    }
}
