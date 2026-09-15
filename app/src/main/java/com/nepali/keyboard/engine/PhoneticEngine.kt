package com.nepali.keyboard.engine

/**
 * Real-time Romanized Nepali phonetic transliteration engine.
 * Converts Latin input (e.g. "namaste" -> नमस्ते, "dhanyabad" -> धन्यबद,
 * "nepal" -> नेपल, "mero" -> मेरो, "kshya" -> क्ष्य) into Devanagari.
 *
 * Parsing rules: longest-match consonant clusters first, then the longest
 * vowel/matra key at each position. "am"/"ah" resolve to anusvara/visarga only
 * as a syllable coda (never when m/h starts the next syllable). "n"/"m"
 * assimilate to anusvara only before stops; before semivowels and sibilants
 * they conjoin with a halant. A halant is inserted only between two consonants
 * with no vowel key between them; word-final consonants carry the implicit
 * schwa (no trailing halant). Residual ambiguities (nepal -> नेपाल, timi ->
 * तिमी) are resolved by [LexiconEngine] fuzzy matching.
 */
object PhoneticEngine {

    private val VOWEL_INDEPENDENT = mapOf(
        "a" to "अ", "aa" to "आ", "i" to "इ", "ee" to "ई", "ii" to "ई",
        "u" to "उ", "oo" to "ऊ", "uu" to "ऊ", "ri" to "ऋ", "e" to "ए",
        "ai" to "ऐ", "o" to "ओ", "au" to "औ", "am" to "अं", "ah" to "अः"
    )

    private val VOWEL_MATRA = mapOf(
        "a" to "", "aa" to "ा", "i" to "ि", "ee" to "ी", "ii" to "ी",
        "u" to "ु", "oo" to "ू", "uu" to "ू", "ri" to "ृ", "e" to "े",
        "ai" to "ै", "o" to "ो", "au" to "ौ", "am" to "ं", "ah" to "ः"
    )

    // Ordered longest-first so multi-letter clusters win over single letters.
    // NOTE: dental aspirates th/dh match case-insensitively (typing "Thaha"
    // still yields थह); explicit retroflexes Th/Dh/N/T revert to dental at
    // word starts (sentence-case tolerance: "Nepal" -> नेपल) but bind mid-word
    // ("baThak", "maNDal" -> मण्डल) so they stay typeable. Only the sibilant
    // "Sh" is strictly retroflex-sensitive even mid-word (mid-word "sh" can
    // still reach श; "Sh" is needed for ष).
    private val CONSONANTS = listOf(
        "kshya" to "क्ष्य", "ksh" to "क्ष", "gya" to "ज्ञ", "tra" to "त्र",
        "chh" to "छ", "nga" to "ङ", "kh" to "ख", "gh" to "घ", "ch" to "च",
        "jh" to "झ", "Th" to "ठ", "Dh" to "ढ", "th" to "थ", "dh" to "ध",
        "ph" to "फ", "bh" to "भ", "shh" to "ष", "Sh" to "ष", "sh" to "श",
        "yn" to "ञ", "T" to "ट", "D" to "ड", "N" to "ण",
        "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "b" to "ब", "m" to "म", "y" to "य",
        "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "f" to "फ", "z" to "ज़", "q" to "क"
    )

    private val SPECIAL_MODIFIERS = mapOf(
        "~" to "ँ",
        "M" to "ं",
        "H" to "ः",
        "*" to "़"
    )

    private val VOWEL_KEYS_BY_LENGTH = VOWEL_MATRA.keys.sortedByDescending { it.length }
    private val INDEPENDENT_KEYS_BY_LENGTH = VOWEL_INDEPENDENT.keys.sortedByDescending { it.length }

    private fun isVowelLetter(c: Char): Boolean {
        return when (c.lowercaseChar()) {
            'a', 'e', 'i', 'o', 'u' -> true
            else -> false
        }
    }

    /** Matches a matra key at [pos]; am/ah only as syllable coda. */
    private fun matchMatraAt(lower: String, pos: Int): Pair<String, String>? {
        for (vk in VOWEL_KEYS_BY_LENGTH) {
            if (!lower.startsWith(vk, pos)) continue
            if (vk == "am" || vk == "ah") {
                val after = pos + vk.length
                // m/h followed by a vowel letter starts the next syllable.
                if (after < lower.length && isVowelLetter(lower[after])) continue
            }
            return vk to (VOWEL_MATRA[vk] ?: "")
        }
        return null
    }

    private fun matchConsonantAt(input: String, pos: Int): Pair<String, String>? {
        for ((latin, dev) in CONSONANTS) {
            if (input.regionMatches(pos, latin, 0, latin.length, ignoreCase = true)) {
                // Case handling: lowercase never consumes a retroflex entry;
                // word starts fall through to the dental ("Nepal" -> नेपल);
                // only mid-word capitals bind ("baThak", "maNDal" -> मण्डल).
                if (latin == "T" || latin == "D" || latin == "N" || latin == "Th" ||
                    latin == "Dh" || latin == "Sh"
                ) {
                    if (input[pos].isLowerCase()) continue
                    if (pos == 0 || !input[pos - 1].isLetter()) continue
                }
                return latin to dev
            }
        }
        return null
    }

    /**
     * Transliterates a Romanized string into its top Devanagari representation.
     */
    fun transliterate(input: String): String {
        if (input.isEmpty()) return ""
        val lower = input.lowercase()
        val sb = StringBuilder()
        var i = 0
        val len = input.length

        while (i < len) {
            val charAt = input[i]
            val modifier = SPECIAL_MODIFIERS[charAt.toString()]
            if (modifier != null) {
                sb.append(modifier)
                i++
                continue
            }

            if (!charAt.isLetter()) {
                sb.append(charAt)
                i++
                continue
            }

            // Medial "ng" is always anusvara (sanga -> संग); intercept before
            // consonant matching. Word-initial "nga" falls through to the
            // consonant table so the rare explicit ङ stays typeable.
            if (i > 0 && (charAt == 'n' || charAt == 'N') && lower.startsWith("ng", i)) {
                sb.append("ं")
                i += 1
                continue
            }

            val consonant = matchConsonantAt(input, i)
            if (consonant != null) {
                val (latin, devanagari) = consonant
                sb.append(devanagari)
                i += latin.length

                if (i >= len) break

                val matra = matchMatraAt(lower, i)
                if (matra != null) {
                    sb.append(matra.second)
                    i += matra.first.length
                } else if (input[i].isLetter() && !isVowelLetter(input[i])) {
                    // Consonant cluster with no vowel between -> halant join.
                    sb.append("्")
                }
            } else {
                // Standalone vowel at syllable start; am/ah only word-initially
                // or after a non-letter (else the matra path owns the position).
                var matchedLen = 0
                var matchedDev: String? = null
                for (vk in INDEPENDENT_KEYS_BY_LENGTH) {
                    if (!lower.startsWith(vk, i)) continue
                    if ((vk == "am" || vk == "ah") && i > 0 && input[i - 1].isLetter()) continue
                    matchedLen = vk.length
                    matchedDev = VOWEL_INDEPENDENT[vk]
                    break
                }
                if (matchedDev != null) {
                    sb.append(matchedDev)
                    i += matchedLen
                } else {
                    sb.append(input[i])
                    i++
                }
            }
        }

        return sb.toString()
    }

    /**
     * Generates transliteration candidates for given Romanized input.
     */
    fun getTransliterationCandidates(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val primary = transliterate(input)
        val list = mutableListOf(primary)

        if (input.endsWith("a", ignoreCase = true) && primary.length > 1) {
            val schwaTrimmed = primary.removeSuffix("ा")
            if (schwaTrimmed.isNotEmpty() && !list.contains(schwaTrimmed)) {
                list.add(schwaTrimmed)
            }
        }
        return list.distinct()
    }
}
