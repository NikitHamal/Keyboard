package com.nikit.nepalikeyboard.translit

import com.nikit.nepalikeyboard.translit.TransliterationRules.Rule
import com.nikit.nepalikeyboard.translit.TransliterationRules.RuleKind
import com.nikit.nepalikeyboard.unicode.Devanagari

/**
 * Converts Romanized Nepali into Devanagari.
 *
 * ## Algorithm
 *
 * A single left-to-right scan over the input, at each position attempting the
 * **longest** rule that matches. When one matches, its Devanagari target is
 * emitted and the scan advances past the matched source. When none matches,
 * the code point is copied through verbatim so that digits, spaces, emoji, and
 * anything the tables do not know survive untouched.
 *
 * ## Vowel attachment
 *
 * The one piece of real grammar here is that a vowel occurring *after* a
 * consonant becomes a dependent matra rather than an independent letter:
 *
 * ```
 *   k  + a   ->  क  + ा   ->  का      (matra)
 *   "" + a   ->  अ                    (independent)
 *   k  + i   ->  क  + ि   ->  कि      (matra)
 *   k  + aa  ->  क  + ा   ->  का      (matra)
 * ```
 *
 * The `inherentA` flag tracks whether the syllable we just built is a bare
 * consonant carrying its inherent /a/. When the input has `ka` we must NOT
 * emit क + ा (which would be "kaa"); we emit क alone and let the inherent
 * vowel do the work. The flag is set when a consonant rule fires and cleared
 * as soon as any vowel, matra, virama, or sign is emitted.
 *
 * ## Schwa deletion
 *
 * Word-final inherent /a/ is deleted in Nepali, so `ram` must become राम
 * (र + ा + म), not रामा. The engine therefore defers the decision about a
 * trailing consonant-with-inherent-a until it knows whether more input is
 * coming. [RomanizedEngine.transliterate] handles this by taking a
 * `isComplete` flag: while the user is still typing, the trailing inherent
 * vowel is kept (so that `r` → र, `ra` → रा is not prematurely collapsed);
 * when the word is committed, a final schwa is dropped unless the word is in
 * [TransliterationRules.SCHWA_RETENTION_WORDS].
 *
 * The engine is otherwise **stateless and allocation-lean**: it writes into a
 * caller-supplied [StringBuilder] and does not build intermediate strings.
 */
object RomanizedEngine {

    /**
     * Result of a transliteration pass.
     *
     * @property devanagari the rendered Devanagari text
     * @property consumed how many input code units were consumed
     * @property endedOnConsonant true when the final emitted unit was a bare
     *         consonant still holding its inherent /a/, which means a pending
     *         schwa deletion decision is unresolved
     * @property syllableCount number of syllables emitted, used by the
     *         suggestion strip to show how much of the word is still tentative
     */
    data class Result(
        val devanagari: String,
        val consumed: Int,
        val endedOnConsonant: Boolean,
        val syllableCount: Int
    )

    /**
     * Transliterate [input].
     *
     * @param input the Romanized text. Case is significant for the rules that
     *        distinguish retroflex from dental consonants.
     * @param isComplete true when the word is finished (the user pressed space,
     *        punctuation, or accepted a suggestion), false while still typing.
     *        Only a completed word has its final schwa deleted.
     * @param out the destination builder. Cleared by this function; passed in
     *        rather than allocated so callers can reuse one instance.
     * @return a [Result] describing what was produced
     */
    fun transliterate(
        input: CharSequence,
        isComplete: Boolean,
        out: StringBuilder = StringBuilder(input.length * 2)
    ): Result {
        out.setLength(0)
        if (input.isEmpty()) {
            return Result("", 0, false, 0)
        }

        var i = 0
        val n = input.length
        var syllableCount = 0

        // ------------------------------------------------------------------
        // State carried across iterations.
        // ------------------------------------------------------------------

        /** True while the tail of `out` is a consonant holding its inherent /a/. */
        var inherentA = false

        /** True when the tail of `out` is a consonant without a following vowel. */
        var lastWasConsonant = false

        /** Index in `out` of the last consonant we emitted, or -1. */
        var lastConsonantIndex = -1

        /** True when the previous input token was an explicit virama. */
        var afterVirama = false

        loop@ while (i < n) {
            val startIndex = i
            val matched = matchRuleAt(input, i)

            if (matched == null) {
                // ---------------- Pass-through path ----------------
                val cp = input.codePointAtCompat(i)
                val cpLen = if (cp >= 0x10000) 2 else 1

                // A pass-through character terminates any pending syllable.
                if (inherentA && lastConsonantIndex >= 0) {
                    // Keep the inherent vowel as-is; nothing to delete, because
                    // a non-letter follows. This is the `ram,` case.
                    inherentA = false
                }
                out.appendCodePointCompat(input, i, cp, cpLen)
                lastWasConsonant = false
                afterVirama = false
                i += cpLen
                continue
            }

            val (rule, sourceLength) = matched
            i += sourceLength

            when (rule.kind) {
                RuleKind.CONSONANT -> {
                    // ------------------------------------------------------------------
                    // Anusvara assimilation.
                    // ------------------------------------------------------------------
                    // In Nepali orthography a nasal followed immediately by
                    // another consonant is normally written as anusvara (ं)
                    // rather than as the full nasal letter: "sanstha" is
                    // संस्था, not सन्स्था. We implement this as a lookahead at
                    // the moment we are about to emit a nasal.
                    //
                    // The rule applies to न and म only, and only when the next
                    // matched rule is also a consonant. When the nasal is *not*
                    // followed by a consonant (e.g. "naama") the full letter is
                    // kept, which is what makes "nepal" render नेपाल correctly.
                    if (isNasalConsonant(rule.target) && looksLikeConsonantAhead(input, i)) {
                        if (lastWasConsonant) {
                            // The nasal replaces a consonant's inherent vowel, so
                            // the previous consonant must take a virama first.
                            out.append(Devanagari.VIRAMA)
                        }
                        out.append(Devanagari.ANUSVARA)
                        lastWasConsonant = false
                        inherentA = false
                        afterVirama = false
                        syllableCount++
                        continue@loop
                    }

                    // A consonant directly following a bare consonant suppresses
                    // the first consonant's inherent /a/: "namaste" is
                    // न + म + स + ् + त + े, where the स carries a virama
                    // because the त follows it with no vowel between.
                    if (lastWasConsonant && inherentA) {
                        out.append(Devanagari.VIRAMA)
                    }

                    out.append(rule.target)
                    lastWasConsonant = true
                    inherentA = true
                    afterVirama = false
                    lastConsonantIndex = out.length - rule.target.length
                    syllableCount++
                }

                RuleKind.VOWEL -> {
                    val independent = rule.target[0]

                    // Detect the inherent-vowel case: the plain short "a".
                    // A consonant already carries an inherent /a/ in its own
                    // glyph, so "ka" must render as क alone — appending ा
                    // would wrongly produce का ("kaa").
                    val isInherentA = independent == Devanagari.A
                    val matra = Devanagari.matraForIndependentVowel(independent)

                    if (lastWasConsonant && !afterVirama) {
                        if (isInherentA) {
                            // Nothing to emit: the inherent /a/ is already
                            // represented by the bare consonant glyph.
                            inherentA = true
                        } else if (matra != null) {
                            // Real vowel sign: attach it to the consonant,
                            // replacing the inherent /a/.
                            out.append(matra)
                            inherentA = false
                            syllableCount++
                        } else {
                            // Vocalic liquids have no matra form in ordinary
                            // usage; emit virama + independent vowel.
                            out.append(Devanagari.VIRAMA).append(independent)
                            inherentA = false
                            syllableCount++
                        }
                    } else {
                        out.append(independent)
                        inherentA = false
                        lastWasConsonant = false
                        syllableCount++
                    }

                    lastWasConsonant = false
                    afterVirama = false
                }

                RuleKind.MATRA -> {
                    if (lastWasConsonant) {
                        out.append(rule.target)
                    } else {
                        // A matra with no host consonant: promote to the
                        // independent vowel so the text stays valid.
                        val indep = Devanagari.independentVowelForMatra(rule.target[0])
                        out.append(indep ?: rule.target)
                    }
                    lastWasConsonant = false
                    inherentA = false
                    afterVirama = false
                }

                RuleKind.SIGN -> {
                    out.append(rule.target)
                    lastWasConsonant = false
                    inherentA = false
                    afterVirama = false
                    // A sign closes the syllable.
                    lastConsonantIndex = -1
                }

                RuleKind.SILENT -> {
                    // Explicit vowel suppression: `ram'` -> राम, so the previous
                    // consonant loses its inherent /a/. We achieve that by
                    // writing a virama, but only if the consonant has not
                    // already taken a vowel sign.
                    if (lastWasConsonant && inherentA && lastConsonantIndex >= 0) {
                        out.append(Devanagari.VIRAMA)
                    } else if (lastWasConsonant && !inherentA) {
                        // Consonant + vowel sign: nothing to suppress.
                    }
                    lastWasConsonant = false
                    inherentA = false
                    afterVirama = false
                }

                RuleKind.PASSTHROUGH -> {
                    out.append(rule.target)
                    lastWasConsonant = false
                    inherentA = false
                    afterVirama = false
                }

                RuleKind.SEGMENT -> {
                    // Conjuncts and other multi-character segments. Emitted as
                    // a unit; they behave like a consonant for vowel attachment
                    // because their last character is a consonant or virama.
                    out.append(rule.target)
                    lastWasConsonant = true
                    inherentA = false
                    afterVirama = false
                    lastConsonantIndex = out.length - 1
                    syllableCount++
                }
            }

            // Guard: if a rule somehow matched zero characters we would spin.
            if (i == startIndex) {
                val cp = input.codePointAtCompat(startIndex)
                out.appendCodePointCompat(input, startIndex, cp, if (cp >= 0x10000) 2 else 1)
                i = startIndex + (if (cp >= 0x10000) 2 else 1)
            }
        }

        // ------------------------------------------------------------------
        // Trailing schwa resolution.
        // ------------------------------------------------------------------
        // Nepali deletes the word-final inherent /a/: `ram` is राम (/ram/),
        // not रामा. The engine never *writes* the inherent vowel — a bare
        // consonant glyph already carries it — so the deletion is expressed by
        // the absence of a matra rather than by removing anything from `out`.
        //
        // The only case that needs an explicit edit is a word that must KEEP
        // its final vowel ([TransliterationRules.SCHWA_RETENTION_WORDS]). When
        // that happens and the output ends on a bare consonant, we append the
        // AA matra so the vowel is actually pronounced.
        if (isComplete && lastWasConsonant && inherentA && endsWithConsonant(out)) {
            val romanized = input.toString()
            if (TransliterationRules.keepsFinalSchwa(romanized) &&
                romanized.length > 2
            ) {
                out.append(Devanagari.SIGN_AA)
            }
        }

        return Result(
            devanagari = out.toString(),
            consumed = i,
            endedOnConsonant = lastWasConsonant && inherentA,
            syllableCount = syllableCount
        )
    }

    /**
     * Convenience overload that returns just the Devanagari string. Allocates;
     * not for the hot path.
     */
    fun toDevanagari(input: String, isComplete: Boolean = false): String =
        transliterate(input, isComplete).devanagari

    /**
     * Try to match the longest rule starting at [index].
     *
     * @return the matched rule paired with the number of code units it
     *         consumed, or null when nothing matches
     */
    private fun matchRuleAt(input: CharSequence, index: Int): Pair<Rule, Int>? {
        val first = input[index]

        // Fast reject: non-ASCII input can never start a rule, and neither can
        // an ASCII character that no rule begins with.
        if (first.code >= 128 || !TransliterationRules.RULE_INITIALS[first.code]) {
            return null
        }

        val candidates = TransliterationRules.candidatesFor(first)
        if (candidates.isEmpty()) return null

        for (rule in candidates) {
            val len = rule.source.length
            if (index + len > input.length) continue

            if (rule.caseSensitive) {
                if (regionMatchesExact(input, index, rule.source)) {
                    return rule to len
                }
            } else {
                if (regionMatchesIgnoreCase(input, index, rule.source)) {
                    return rule to len
                }
            }
        }
        return null
    }

    /**
     * True when [target] is a Devanagari nasal consonant that participates in
     * anusvara assimilation: न (na) or म (ma).
     *
     * ङ (nga) and ञ (nya) are deliberately excluded. They are already the
     * correct written forms for their sounds — "ng" and "ny" always produce
     * them — and converting them to anusvara would lose the place-of-
     * articulation information that Nepali orthography preserves.
     */
    private fun isNasalConsonant(target: String): Boolean {
        if (target.length != 1) return false
        val c = target[0]
        return c == Devanagari.NA || c == Devanagari.MA
    }

    /**
     * Look ahead from [index] and report whether the next matched rule is a
     * consonant or a conjunct.
     *
     * This is a speculative one-token parse. It does not allocate and does not
     * mutate state; it is safe to call from inside the main scan.
     */
    private fun looksLikeConsonantAhead(input: CharSequence, index: Int): Boolean {
        if (index >= input.length) return false
        val next = matchRuleAt(input, index) ?: return false
        return when (next.first.kind) {
            RuleKind.CONSONANT -> true
            // A conjunct segment behaves like a consonant for this purpose.
            RuleKind.SEGMENT -> true
            else -> false
        }
    }

    /** Exact, case-sensitive region match with no allocation. */
    private fun regionMatchesExact(
        input: CharSequence,
        index: Int,
        pattern: String
    ): Boolean {
        for (k in pattern.indices) {
            if (input[index + k] != pattern[k]) return false
        }
        return true
    }

    /** Case-insensitive region match with no allocation and no locale cost. */
    private fun regionMatchesIgnoreCase(
        input: CharSequence,
        index: Int,
        pattern: String
    ): Boolean {
        for (k in pattern.indices) {
            val a = input[index + k]
            val b = pattern[k]
            if (a == b) continue
            if (a.lowercaseChar() != b.lowercaseChar()) return false
        }
        return true
    }

    /**
     * True when [out] currently ends with a Devanagari consonant that is not
     * followed by a vowel sign or virama — i.e. it still carries its inherent
     * /a/ and is therefore a schwa-deletion candidate.
     */
    private fun endsWithConsonant(out: StringBuilder): Boolean {
        if (out.isEmpty()) return false
        val last = out[out.length - 1]
        return Devanagari.isConsonant(last)
    }

    // ======================================================================
    // Code point helpers. Kotlin's CharSequence has no built-in codePointAt,
    // and Character.codePointAt allocates nothing but requires a CharSequence
    // overload we cannot rely on across API levels, so we inline it.
    // ======================================================================

    private fun CharSequence.codePointAtCompat(index: Int): Int {
        if (index >= length) return -1
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length) {
            val low = this[index + 1]
            if (low.isLowSurrogate()) return Character.toCodePoint(high, low)
        }
        return high.code
    }

    private fun StringBuilder.appendCodePointCompat(
        input: CharSequence,
        index: Int,
        cp: Int,
        cpLen: Int
    ) {
        if (cpLen == 2) {
            append(input[index]).append(input[index + 1])
        } else {
            append(input[index])
        }
    }

    // ======================================================================
    // Capitalisation helpers used by the QWERTY layer
    // ======================================================================

    /**
     * Apply a shift state to a Latin letter. Returns the input unchanged when
     * it is not a letter or when [shift] is false.
     */
    fun applyShift(ch: Char, shift: Boolean): Char =
        if (shift) ch.uppercaseChar() else ch
}
