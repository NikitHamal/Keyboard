package np.com.nepalikeyboard.engine

import np.com.nepalikeyboard.engine.unicode.Devanagari

/**
 * Deterministic romanized-Nepali -> Devanagari transliteration.
 *
 * The engine is pure, stateless, thread safe and allocation-light: it renders a
 * whole roman buffer in a single left-to-right pass with a reusable
 * [StringBuilder] supplied by the caller, so no intermediate lists, regexes or
 * substrings are created on the keystroke path.
 *
 * Reference behaviour (spec examples, all covered by unit expectations):
 * ```
 * namaste   -> नमस्ते
 * dhanyabad -> धन्यबाद   (literal)     धन्यवाद   (dictionary candidate)
 * nepal     -> नेपाल
 * mero      -> मेरो
 * kshya     -> क्ष्य
 * tra       -> त्र
 * gya       -> ज्ञ
 * sanga     -> संग
 * ```
 */
object PhoneticEngine {

    private const val VIRAMA = PhoneticRules.VIRAMA_CHAR
    private const val ANUSVARA = PhoneticRules.ANUSVARA_CHAR

    /** Transliterates [roman] and returns a fresh String (UI-facing helper). */
    fun transliterate(roman: CharSequence, devanagariDigits: Boolean = false): String {
        if (roman.isEmpty()) return ""
        val builder = StringBuilder(roman.length * 2 + 8)
        transliterateInto(roman, builder, devanagariDigits)
        return builder.toString()
    }

    /**
     * Transliterates [roman] into [out], which is *not* cleared: callers that
     * reuse a scratch builder must call `out.setLength(0)` first. Reusing the
     * builder is what keeps the keystroke path allocation free.
     */
    fun transliterateInto(
        roman: CharSequence,
        out: StringBuilder,
        devanagariDigits: Boolean = false,
    ) {
        val end = roman.length
        var index = 0
        var pendingConsonant = false

        while (index < end) {
            val rule = PhoneticRules.match(roman, index, end)
            if (rule == null) {
                val ch = roman[index]
                when {
                    devanagariDigits && ch in '0'..'9' -> out.append(Devanagari.toDevanagariDigit(ch))
                    ch == '|' -> out.append(Devanagari.DANDA)
                    else -> out.append(ch)
                }
                pendingConsonant = false
                index++
                continue
            }
            when (rule.kind) {
                UnitKind.CONSONANT -> {
                    // A consonant directly after a consonant needs a virama:
                    // म + ् + स + ् + त + े = मस्ते. Word-final consonants keep
                    // their inherent 'a' (नेपाल, रात, देश) - Devanagari words do
                    // not end in a halant.
                    if (pendingConsonant) out.append(VIRAMA)
                    out.append(rule.independent)
                    pendingConsonant = true
                }

                UnitKind.VOWEL -> {
                    out.append(if (pendingConsonant) rule.dependent else rule.independent)
                    pendingConsonant = false
                }

                UnitKind.ANUSVARA -> {
                    out.append(ANUSVARA)
                    pendingConsonant = false
                }
            }
            index += rule.roman.length
        }
    }

    /**
     * True when the roman buffer is a complete, committable word: it contains at
     * least one vowel-producing rule or is longer than a single stray consonant.
     * Used to decide whether "space" commits or just inserts a space.
     */
    fun isCommittable(roman: CharSequence): Boolean {
        if (roman.isEmpty()) return false
        var vowels = 0
        var index = 0
        val end = roman.length
        while (index < end) {
            val rule = PhoneticRules.match(roman, index, end)
            if (rule == null) {
                index++
                continue
            }
            if (rule.kind == UnitKind.VOWEL) vowels++
            index += rule.roman.length
        }
        return vowels > 0 || roman.length > 2
    }

    /**
     * The keyboard needs a "capitalize the first letter" hint for the roman
     * strip. This returns the roman buffer with an ASCII capital on the first
     * letter, which is how auto-capitalization is displayed without altering the
     * transliteration (capitals are transliteration-significant, so the buffer
     * itself always stores the effective, shift-resolved characters).
     */
    fun displayForm(roman: CharSequence): String {
        if (roman.isEmpty()) return ""
        val first = roman[0]
        if (first !in 'a'..'z') return roman.toString()
        val builder = StringBuilder(roman.length)
        builder.append(first - 32)
        builder.append(roman, 1, roman.length)
        return builder.toString()
    }

    /** Lowercases only ASCII, leaving any Devanagari untouched. */
    fun asciiLowercase(text: CharSequence): String {
        var needs = false
        for (index in 0 until text.length) {
            val ch = text[index]
            if (ch in 'A'..'Z') {
                needs = true
                break
            }
        }
        if (!needs) return text.toString()
        val builder = StringBuilder(text.length)
        for (index in 0 until text.length) {
            val ch = text[index]
            builder.append(if (ch in 'A'..'Z') ch + 32 else ch)
        }
        return builder.toString()
    }
}
