package com.nepali.keyboard.engine

/**
 * Real-time Romanized Nepali phonetic transliteration engine.
 * Converts Latin input (e.g. "namaste", "dhanyabad", "nepal", "kshya") into Devanagari.
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

    private val CONSONANTS = listOf(
        "kshya" to "क्ष्य", "ksh" to "क्ष", "gya" to "ज्ञ", "tra" to "त्र",
        "kh" to "ख", "gh" to "घ", "ch" to "च", "chh" to "छ", "jh" to "झ",
        "th" to "थ", "dh" to "ध", "ph" to "फ", "bh" to "भ", "sh" to "श",
        "shh" to "ष", "ng" to "ङ", "yn" to "ञ", "Th" to "ठ", "Dh" to "ढ",
        "k" to "क", "g" to "ग", "j" to "ज", "t" to "त", "d" to "द",
        "n" to "न", "p" to "प", "b" to "ब", "m" to "म", "y" to "य",
        "r" to "र", "l" to "ल", "v" to "व", "w" to "व", "s" to "स",
        "h" to "ह", "T" to "ट", "D" to "ड", "N" to "ण", "f" to "फ",
        "z" to "ज़", "q" to "क"
    )

    private val SPECIAL_MODIFIERS = mapOf(
        "~" to "ँ", // Chandrabindu
        "M" to "ं", // Anusvara
        "H" to "ः", // Visarga
        "*" to "़"  // Nukta
    )

    /**
     * Transliterates a Romanized string into its top Devanagari representation.
     */
    fun transliterate(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder()
        var i = 0
        val len = input.length

        while (i < len) {
            val charAt = input[i]
            if (SPECIAL_MODIFIERS.containsKey(charAt.toString())) {
                sb.append(SPECIAL_MODIFIERS[charAt.toString()])
                i++
                continue
            }

            if (!charAt.isLetter()) {
                sb.append(charAt)
                i++
                continue
            }

            // Try matching consonant prefixes
            var matchedConsonant: String? = null
            var matchedDevConsonant: String? = null

            for ((latin, dev) in CONSONANTS) {
                if (input.regionMatches(i, latin, 0, latin.length, ignoreCase = true)) {
                    matchedConsonant = latin
                    matchedDevConsonant = dev
                    break
                }
            }

            if (matchedConsonant != null && matchedDevConsonant != null) {
                sb.append(matchedDevConsonant)
                i += matchedConsonant.length

                if (i >= len) {
                    // Halant at end if no vowel follows, or silent schwa
                    break
                }

                // Check for vowel after consonant
                var matchedVowelLen = 0
                var matchedMatra: String? = null

                val remaining = input.substring(i).lowercase()
                val vowelKeys = VOWEL_MATRA.keys.sortedByDescending { it.length }

                for (vk in vowelKeys) {
                    if (remaining.startsWith(vk)) {
                        matchedVowelLen = vk.length
                        matchedMatra = VOWEL_MATRA[vk]
                        break
                    }
                }

                if (matchedMatra != null) {
                    sb.append(matchedMatra)
                    i += matchedVowelLen
                } else {
                    // Next char is consonant or boundary -> apply halant unless next character is 'a' or implicit schwa
                    val nextChar = input[i]
                    if (nextChar.isLetter() && !isVowelChar(nextChar)) {
                        sb.append("्")
                    }
                }
            } else {
                // Standalone vowel matching
                var matchedVowelLen = 0
                var matchedVowelDev: String? = null
                val remaining = input.substring(i).lowercase()
                val vowelKeys = VOWEL_INDEPENDENT.keys.sortedByDescending { it.length }

                for (vk in vowelKeys) {
                    if (remaining.startsWith(vk)) {
                        matchedVowelLen = vk.length
                        matchedVowelDev = VOWEL_INDEPENDENT[vk]
                        break
                    }
                }

                if (matchedVowelDev != null) {
                    sb.append(matchedVowelDev)
                    i += matchedVowelLen
                } else {
                    sb.append(input[i])
                    i++
                }
            }
        }

        return sb.toString()
    }

    private fun isVowelChar(c: Char): Boolean {
        val lc = c.lowercaseChar()
        return lc == 'a' || lc == 'e' || lc == 'i' || lc == 'o' || lc == 'u'
    }

    /**
     * Generates transliteration candidates for given Romanized input.
     */
    fun getTransliterationCandidates(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        val primary = transliterate(input)
        val list = mutableListOf<String>()
        list.add(primary)

        // Generate common variations (e.g. explicitly adding halant or soft vowel variants)
        if (input.endsWith("a") && primary.length > 1) {
            val schwaTrimmed = primary.removeSuffix("ा")
            if (schwaTrimmed.isNotEmpty() && !list.contains(schwaTrimmed)) {
                list.add(schwaTrimmed)
            }
        }
        return list.distinct()
    }
}
