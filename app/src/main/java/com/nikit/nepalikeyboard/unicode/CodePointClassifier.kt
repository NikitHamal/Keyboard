package com.nikit.nepalikeyboard.unicode

/**
 * Classification of a code point for the purposes of grapheme-cluster and
 * Devanagari akshara segmentation.
 *
 * We deliberately implement our own classifier rather than relying on
 * [java.text.BreakIterator] or ICU:
 *
 *  * `BreakIterator.getCharacterInstance()` follows legacy UAX #29 behaviour on
 *    older Android releases and, critically, breaks Devanagari aksharas apart
 *    at matra boundaries on several OEM builds. Splitting क + ि into two
 *    "characters" makes backspace destroy the vowel sign and leaves a
 *    dangling consonant with no visible vowel.
 *  * `android.icu` grapheme iteration is correct but allocates, and allocates
 *    on the keystroke path, which is exactly what we must not do.
 *
 * The categories below are limited to the distinctions we actually need.
 */
internal enum class CodePointClass {
    /** Anything unremarkable (ASCII letters, digits, most symbols). */
    OTHER,

    /** U+0000..U+001F and U+007F, plus C1 controls. */
    CONTROL,

    /** U+000D CARRIAGE RETURN. */
    CR,

    /** U+000A LINE FEED. */
    LF,

    /** High surrogate of a UTF-16 pair. */
    SURROGATE_HIGH,

    /** Low surrogate of a UTF-16 pair. */
    SURROGATE_LOW,

    /**
     * Spacing combining mark: a combining character with the Unicode property
     * `General_Category = Mc` (e.g. Devanagari matras, Bengali vowel signs,
     * Tamil vowel signs). These are visually attached after the base and must
     * never start a cluster.
     */
    SPACING_MARK,

    /**
     * Non-spacing combining mark: `General_Category = Mn`. Includes Devanagari
     * anusvara, chandrabindu, halant, nukta, and the vast majority of combining
     * diacritics in other scripts.
     */
    NON_SPACING_MARK,

    /** Enclosing mark: `General_Category = Me`. */
    ENCLOSING_MARK,

    /**
     * Devanagari sign virama / halant (U+094D) specifically. Classifying this
     * separately lets us implement conjunct formation — a halant *joins* the
     * next consonant into the same akshara, rather than merely attaching to the
     * previous one.
     */
    DEVANAGARI_HALANT,

    /** Zero-width joiner U+200D. */
    ZWJ,

    /** Zero-width non-joiner U+200C. */
    ZWNJ,

    /** Variation selector U+FE0E / U+FE0F. */
    VARIATION_SELECTOR,

    /** Regional indicator U+1F1E6..U+1F1FF (flag sequences). */
    REGIONAL_INDICATOR,

    /**
     * Emoji modifier (Fitzpatrick skin tone, U+1F3FB..U+1F3FF) and emoji tag
     * sequences. Both extend the preceding cluster.
     */
    EMOJI_EXTEND,

    /** U+0020 and other U+00A0-category whitespace. */
    SPACE,
}

/**
 * Maps UTF-16 code units / code points to [CodePointClass].
 *
 * The table is intentionally a `when` ladder over codepoint *ranges* rather
 * than a lookup array. A 1 114 112-entry array would cost ~1.1 MB of heap for
 * a keyboard, which is unacceptable; the ladder has roughly two dozen branches
 * and JIT-compiles into a jump chain.
 */
internal object CodePointClassifier {

    /** Devanagari block start. */
    private const val DEVANAGARI_START = 0x0900
    /** Devanagari block end. */
    private const val DEVANAGARI_END = 0x097F
    /** Devanagari Extended block start. */
    private const val DEVANAGARI_EXT_START = 0xA8E0
    private const val DEVANAGARI_EXT_END = 0xA8FF
    /** Vedic Extensions. */
    private const val VEDIC_EXT_START = 0x1CD0
    private const val VEDIC_EXT_END = 0x1CFF

    /**
     * Classify a single code point.
     *
     * Must be called with a real code point (superseded pairs already merged by
     * the caller). Passing a lone code *unit* is safe but will report
     * [CodePointClass.SURROGATE_HIGH] / [CodePointClass.SURROGATE_LOW].
     */
    fun classify(cp: Int): CodePointClass = when (cp) {
        // ---- Controls ---------------------------------------------------
        in 0x00..0x1F, in 0x7F..0x9F -> CodePointClass.CONTROL
        0x0D -> CodePointClass.CR
        0x0A -> CodePointClass.LF

        // ---- Surrogates -------------------------------------------------
        in 0xD800..0xDBFF -> CodePointClass.SURROGATE_HIGH
        in 0xDC00..0xDFFF -> CodePointClass.SURROGATE_LOW

        // ---- Joiners & selectors ---------------------------------------
        0x200D -> CodePointClass.ZWJ
        0x200C -> CodePointClass.ZWNJ
        in 0xFE00..0xFE0F -> CodePointClass.VARIATION_SELECTOR
        0xE0100, in 0xE0100..0xE01EF -> CodePointClass.VARIATION_SELECTOR

        // ---- Whitespace -------------------------------------------------
        0x20, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F,
        0x205F, 0x3000 -> CodePointClass.SPACE

        // ---- Devanagari specificity (checked before the generic mark scan,
        //      because a mark's *role* in an akshara matters more than its
        //      Unicode category) ------------------------------------------
        0x094D -> CodePointClass.DEVANAGARI_HALANT

        // ---- Emoji ------------------------------------------------------
        in 0x1F1E6..0x1F1FF -> CodePointClass.REGIONAL_INDICATOR
        in 0x1F3FB..0x1F3FF -> CodePointClass.EMOJI_EXTEND
        0xE0020, in 0xE0020..0xE007F -> CodePointClass.EMOJI_EXTEND
        0x20E3 -> CodePointClass.EMOJI_EXTEND // combining enclosing keycap
        0xFE0F -> CodePointClass.VARIATION_SELECTOR

        // ---- Generic combining marks ------------------------------------
        // Non-spacing marks (Mn): the big ranges we care about.
        in 0x0300..0x036F, // Combining Diacritical Marks
        in 0x0483..0x0489,
        in 0x0591..0x05BD, 0x05BF, in 0x05C1..0x05C2, in 0x05C4..0x05C5, 0x05C7,
        in 0x0610..0x061A, in 0x064B..0x065F, 0x0670, in 0x06D6..0x06DC,
        in 0x06DF..0x06E4, in 0x06E7..0x06E8, in 0x06EA..0x06ED,
        0x0711, in 0x0730..0x074A,
        in 0x07A6..0x07B0, in 0x07EB..0x07F3,
        in 0x0816..0x0819, in 0x081B..0x0823, in 0x0825..0x0827,
        in 0x0829..0x082D, in 0x0859..0x085B,
        in 0x08E3..0x0902, 0x093A, 0x093C,
        in 0x0941..0x0948, 0x094D, in 0x0951..0x0957,
        in 0x0962..0x0963,
        0x0981, 0x09BC, in 0x09C1..0x09C4, 0x09CD,
        in 0x0A01..0x0A02, 0x0A3C, in 0x0A41..0x0A42, in 0x0A47..0x0A48,
        in 0x0A4B..0x0A4D, 0x0A51, in 0x0A70..0x0A71, 0x0A75,
        0x0B01, 0x0B3C, 0x0B3F, in 0x0B41..0x0B44, 0x0B4D, 0x0B56,
        0x0C00, in 0x0C3E..0x0C40, in 0x0C46..0x0C48, in 0x0C4A..0x0C4D,
        in 0x0D00..0x0D01, in 0x0D3B..0x0D3C, in 0x0D41..0x0D44, 0x0D4D,
        0x0E31, in 0x0E34..0x0E3A, in 0x0E47..0x0E4E,
        0x0EB1, in 0x0EB4..0x0EB9, in 0x0EBB..0x0EBC, in 0x0EC8..0x0ECD,
        in 0x0F18..0x0F19, 0x0F35, 0x0F37, 0x0F39, in 0x0F71..0x0F7E,
        in 0x0F80..0x0F84, in 0x0F86..0x0F87, in 0x0F8D..0x0F97,
        in 0x0F99..0x0FBC, 0x0FC6,
        in 0x102D..0x1030, in 0x1032..0x1037, in 0x1039..0x103A,
        in 0x1058..0x1059, in 0x105E..0x1060,
        in 0x135D..0x135F,
        in 0x1712..0x1714, in 0x1732..0x1734, in 0x1752..0x1753,
        in 0x1772..0x1773, in 0x17B4..0x17B5, in 0x17B7..0x17BD, 0x17C6,
        in 0x17C9..0x17D3, 0x17DD,
        in 0x180B..0x180D, 0x18A9,
        in 0x1920..0x1922, in 0x1927..0x1928, 0x1932, in 0x1939..0x193B,
        in 0x1A17..0x1A18, 0x1A1B, 0x1A56, in 0x1A58..0x1A5E, 0x1A60,
        0x1A62, in 0x1A65..0x1A6C, in 0x1A73..0x1A7C, 0x1A7F,
        in 0x1AB0..0x1ABE,
        in 0x1B00..0x1B03, 0x1B34, in 0x1B36..0x1B3A, 0x1B3C, 0x1B42,
        in 0x1B6B..0x1B73,
        in 0x1DC0..0x1DF5, in 0x1DFC..0x1DFF,
        in 0x20D0..0x20F0,
        in 0x2CEF..0x2CF1, 0x2D7F, in 0x2DE0..0x2DFF,
        in 0x302A..0x302D, in 0x3099..0x309A,
        in 0xA66F..0xA672, in 0xA674..0xA67D, in 0xA69E..0xA69F,
        in 0xA6F0..0xA6F1,
        0xA802, 0xA806, 0xA80B, in 0xA825..0xA826, 0xA8C4,
        in 0xA8E0..0xA8F1,
        in 0xA926..0xA92D, in 0xA947..0xA951, in 0xA980..0xA982, 0xA9B3,
        in 0xA9B6..0xA9B9, 0xA9BC,
        in 0xAA29..0xAA2E, in 0xAA31..0xAA32, in 0xAA35..0xAA36, 0xAA43,
        0xAA4C, 0xAAB0, in 0xAAB2..0xAAB4, in 0xAAB7..0xAAB8, in 0xAABE..0xAABF,
        0xAAC1,
        in 0xAAEC..0xAAED, 0xAAF6, 0xABE5, 0xABE8, 0xABED,
        0xFB1E,
        in 0xFE00..0xFE0F, in 0xFE20..0xFE2F,
        0x101FD, 0x102E0,
        in 0x10376..0x1037A,
        in 0x10A01..0x10A03, in 0x10A05..0x10A06, in 0x10A0C..0x10A0F,
        in 0x10A38..0x10A3A, 0x10A3F,
        in 0x10AE5..0x10AE6,
        0x11001, in 0x11038..0x11046, in 0x1107F..0x11081,
        in 0x110B3..0x110B6, in 0x110B9..0x110BA,
        in 0x1D165..0x1D169, in 0x1D16D..0x1D172,
        in 0x1D17B..0x1D182, in 0x1D185..0x1D18B,
        in 0x1D1AA..0x1D1AD, in 0x1D242..0x1D244,
        in 0x1DA00..0x1DA36, in 0x1DA3B..0x1DA6C, 0x1DA75, 0x1DA84,
        in 0x1DA9B..0x1DA9F, in 0x1DAA1..0x1DAAF,
        in 0x1E000..0x1E006, in 0x1E008..0x1E018, in 0x1E01B..0x1E021,
        in 0x1E023..0x1E024, in 0x1E026..0x1E02A,
        in 0x1E8D0..0x1E8D6,
        in 0x1E944..0x1E94A,
        in 0xE0100..0xE01EF
        -> CodePointClass.NON_SPACING_MARK

        // Spacing marks (Mc): vowels that have their own advance width.
        0x0903, 0x093B, in 0x093E..0x0940, in 0x0949..0x094C, 0x094E,
        in 0x0982..0x0983, in 0x09BE..0x09C0, in 0x09C7..0x09C8,
        in 0x09CB..0x09CC, 0x09D7,
        0x0A03, in 0x0A3E..0x0A40, 0x0A83, in 0x0ABE..0x0AC0,
        0x0AC9, in 0x0ACB..0x0ACC, in 0x0B02..0x0B03, 0x0B3E, 0x0B40,
        in 0x0B47..0x0B48, in 0x0B4B..0x0B4C, 0x0B57, in 0x0BBE..0x0BBF,
        0x0BC1, 0x0BC2, in 0x0BC6..0x0BC8, in 0x0BCA..0x0BCC, 0x0BD7,
        in 0x0C01..0x0C03, in 0x0C41..0x0C44,
        in 0x0C82..0x0C83, 0x0CBE, in 0x0CC0..0x0CC4, in 0x0CC7..0x0CC8,
        in 0x0CCA..0x0CCB, in 0x0CD5..0x0CD6,
        in 0x0D02..0x0D03, in 0x0D3E..0x0D40, in 0x0D46..0x0D48,
        in 0x0D4A..0x0D4C, 0x0D57,
        in 0x0D82..0x0D83, in 0x0DCF..0x0DD1, in 0x0DD8..0x0DDF,
        in 0x0DF2..0x0DF3,
        0x0E33, 0x0EB3,
        in 0x0F3E..0x0F3F, 0x0F7F,
        in 0x102B..0x102C, 0x1031, 0x1038, in 0x103B..0x103C,
        in 0x1056..0x1057, in 0x1062..0x1064, in 0x1067..0x106D,
        in 0x1083..0x1084, in 0x1087..0x108C, 0x108F,
        in 0x109A..0x109C,
        0x17B6, in 0x17BE..0x17C5, in 0x17C7..0x17C8,
        in 0x1923..0x1926, in 0x1929..0x192B, in 0x1930..0x1931,
        in 0x1933..0x1938,
        in 0x1A19..0x1A1A, 0x1A55, 0x1A57, 0x1A61, 0x1A63, 0x1A64,
        in 0x1A6D..0x1A72, 0x1B04, 0x1B35, 0x1B3B, in 0x1B3D..0x1B41,
        in 0x1B43..0x1B44,
        0x1B82, 0x1BA1, in 0x1BA6..0x1BA7, 0x1BAA,
        0x1BE7, in 0x1BEA..0x1BEC, 0x1BEE, in 0x1BF2..0x1BF3,
        in 0xA823..0xA824, 0xA827, in 0xA880..0xA881, in 0xA8B4..0xA8C3,
        in 0xA952..0xA953, 0xA983, in 0xA9B4..0xA9B5, in 0xA9BA..0xA9BB,
        in 0xA9BD..0xA9C0,
        in 0xAA2F..0xAA30, in 0xAA33..0xAA34, 0xAA4D,
        0xAAEB, in 0xAAEE..0xAAEF, 0xAAF5,
        in 0xABE3..0xABE4, in 0xABE6..0xABE7, in 0xABE9..0xABEA,
        0x11000, 0x11002, 0x11082, in 0x110B0..0x110B2,
        in 0x110B7..0x110B8,
        in 0x1D165..0x1D166, in 0x1D16D..0x1D172
        -> CodePointClass.SPACING_MARK

        // Enclosing marks (Me).
        in 0x0488..0x0489, in 0x20DD..0x20E0, in 0x20E2..0x20E4, 0x302E,
        0x302F, in 0xA670..0xA672
        -> CodePointClass.ENCLOSING_MARK

        else -> CodePointClass.OTHER
    }

    /**
     * True when the class attaches to a preceding base character rather than
     * standing alone. Used by the segmentation loop.
     */
    fun isExtending(cls: CodePointClass): Boolean = when (cls) {
        CodePointClass.NON_SPACING_MARK,
        CodePointClass.SPACING_MARK,
        CodePointClass.ENCLOSING_MARK,
        CodePointClass.VARIATION_SELECTOR,
        CodePointClass.ZWJ,
        CodePointClass.ZWNJ,
        CodePointClass.EMOJI_EXTEND
        -> true

        else -> false
    }

    /**
     * True when the class is an emoji "pictographic-ish" modifier that should
     * glue onto a preceding emoji even across a ZWJ.
     */
    fun isEmojiModifier(cls: CodePointClass): Boolean =
        cls == CodePointClass.EMOJI_EXTEND

    /** True for Devanagari combining marks that must stay inside an akshara. */
    fun isDevanagariMark(cls: CodePointClass): Boolean =
        cls == CodePointClass.NON_SPACING_MARK ||
            cls == CodePointClass.SPACING_MARK ||
            cls == CodePointClass.ENCLOSING_MARK ||
            cls == CodePointClass.DEVANAGARI_HALANT

    /** True when [cp] lies in the Devanagari or Devanagari Extended block. */
    fun isDevanagariCodePoint(cp: Int): Boolean =
        cp in DEVANAGARI_START..DEVANAGARI_END ||
            cp in DEVANAGARI_EXT_START..DEVANAGARI_EXT_END ||
            cp in VEDIC_EXT_START..VEDIC_EXT_END
}
