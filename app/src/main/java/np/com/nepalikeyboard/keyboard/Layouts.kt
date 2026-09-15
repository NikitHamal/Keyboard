package np.com.nepalikeyboard.keyboard

import java.util.concurrent.ConcurrentHashMap
import np.com.nepalikeyboard.R

/**
 * Every keyboard layer in the app, built once and cached.
 *
 * Layout construction happens the first time a layer is requested (and again
 * when the shift / number-row / numeral configuration changes). The cached
 * [KeyboardLayout] instances are immutable, so the renderer can hand the same
 * object to the measure pass on every frame without any per-frame work.
 */
object LayoutCatalog {

    private val cache = ConcurrentHashMap<Int, KeyboardLayout>(32)

    /** Resolves a layer with the requested modifiers, memoised. */
    fun resolve(
        id: LayoutId,
        shifted: Boolean = false,
        numberRow: Boolean = false,
        devanagariNumerals: Boolean = false,
    ): KeyboardLayout {
        val key = packKey(id, shifted, numberRow, devanagariNumerals)
        cache[key]?.let { return it }
        val built = build(id, shifted, numberRow, devanagariNumerals)
        return cache.putIfAbsent(key, built) ?: built
    }

    private fun packKey(id: LayoutId, shifted: Boolean, numberRow: Boolean, devanagariNumerals: Boolean): Int {
        var key = id.ordinal
        if (shifted) key = key or 0x100
        if (numberRow) key = key or 0x200
        if (devanagariNumerals) key = key or 0x400
        return key
    }

    private fun build(
        id: LayoutId,
        shifted: Boolean,
        numberRow: Boolean,
        devanagariNumerals: Boolean,
    ): KeyboardLayout {
        val base = when (id) {
            LayoutId.ENGLISH -> qwerty(LayoutId.ENGLISH, shifted, numberRow, devanagariNumerals)
            LayoutId.ROMAN -> qwerty(LayoutId.ROMAN, shifted, numberRow, devanagariNumerals)
            LayoutId.EMAIL -> qwerty(LayoutId.EMAIL, shifted, numberRow, devanagariNumerals, urlRow = EMAIL_ROW)
            LayoutId.URI -> qwerty(LayoutId.URI, shifted, numberRow, devanagariNumerals, urlRow = URI_ROW)
            LayoutId.DEVANAGARI -> devanagari(numberRow, devanagariNumerals)
            LayoutId.DEVANAGARI_ALT -> devanagariAlt()
            LayoutId.SYMBOLS -> symbols(numberRow)
            LayoutId.SYMBOLS_ALT -> symbolsAlt(numberRow)
            LayoutId.NUMERIC -> numeric()
            LayoutId.PHONE -> phone()
        }
        return base
    }

    // -----------------------------------------------------------------------
    // Builders
    // -----------------------------------------------------------------------

    private fun charKey(
        output: String,
        label: String = output,
        style: KeyLabelStyle = KeyLabelStyle.LATIN,
        weight: Float = 1f,
        longPress: List<String> = emptyList(),
    ): KeyDef = KeyDef(
        id = KeyDef.keyId(KeyKind.CHARACTER, output),
        label = label,
        output = output,
        kind = KeyKind.CHARACTER,
        weight = weight,
        labelStyle = style,
        longPress = longPress,
    )

    private fun devanagariKey(
        output: String,
        label: String = output,
        weight: Float = 1f,
        longPress: List<String> = emptyList(),
    ): KeyDef = charKey(output, label, KeyLabelStyle.DEVANAGARI, weight, longPress)

    private fun functionKey(
        kind: KeyKind,
        label: String = "",
        weight: Float = 1f,
        labelRes: Int = 0,
        output: String = "",
        longPress: List<String> = emptyList(),
    ): KeyDef = KeyDef(
        id = KeyDef.keyId(kind, output),
        label = label,
        output = output,
        kind = kind,
        weight = weight,
        labelStyle = if (label.isEmpty()) KeyLabelStyle.ICON else KeyLabelStyle.ACTION,
        labelRes = labelRes,
        longPress = longPress,
    )

    private fun spaceKey(weight: Float = 4f): KeyDef =
        functionKey(kind = KeyKind.SPACE, weight = weight, labelRes = R.string.key_space)

    private fun enterKey(weight: Float = 2.5f, label: String = ""): KeyDef =
        functionKey(kind = KeyKind.ENTER, weight = weight, label = label)

    private fun backspaceKey(weight: Float = 1.5f): KeyDef =
        functionKey(kind = KeyKind.BACKSPACE, weight = weight, labelRes = R.string.key_backspace)

    private fun shiftKey(weight: Float = 1.5f): KeyDef =
        functionKey(kind = KeyKind.SHIFT, weight = weight, labelRes = R.string.key_shift)

    private fun symbolsKey(weight: Float = 1.5f): KeyDef =
        functionKey(kind = KeyKind.SYMBOLS, weight = weight, labelRes = R.string.key_symbols)

    private fun layoutKey(weight: Float = 1.5f): KeyDef =
        functionKey(kind = KeyKind.LAYOUT_SWITCH, weight = weight, labelRes = R.string.key_layout)

    private fun emojiKey(weight: Float = 1f): KeyDef =
        functionKey(kind = KeyKind.EMOJI, weight = weight, labelRes = R.string.key_emoji)

    private fun numberRowKeys(devanagariNumerals: Boolean, nepaliFlavoured: Boolean): KeyRow {
        val symbols = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
        val keys = ArrayList<KeyDef>(10)
        for (digit in '1'..'9') {
            val index = digit - '1'
            keys += digitKey(digit, devanagariNumerals, nepaliFlavoured, symbols[index])
        }
        keys += digitKey('0', devanagariNumerals, nepaliFlavoured, symbols[9])
        return KeyRow(keys)
    }

    private fun digitKey(
        asciiDigit: Char,
        devanagariNumerals: Boolean,
        nepaliFlavoured: Boolean,
        longPressSymbol: String,
    ): KeyDef {
        val devanagari = np.com.nepalikeyboard.engine.unicode.Devanagari.toDevanagariDigit(asciiDigit).toString()
        val useDevanagari = nepaliFlavoured && devanagariNumerals
        val output = if (useDevanagari) devanagari else asciiDigit.toString()
        val alternate = if (useDevanagari) asciiDigit.toString() else devanagari
        val longPress = if (nepaliFlavoured) listOf(alternate, longPressSymbol) else listOf(longPressSymbol)
        return charKey(
            output = output,
            label = output,
            style = if (useDevanagari) KeyLabelStyle.DEVANAGARI else KeyLabelStyle.LATIN,
            longPress = longPress,
        )
    }

    // -----------------------------------------------------------------------
    // Latin layouts
    // -----------------------------------------------------------------------

    private val EMAIL_ROW: List<String> = listOf("@", ".", "_", "-", ".com", "/", ":")
    private val URI_ROW: List<String> = listOf("/", ":", ".", "-", "_", "~", ".com", ".np", "?", "&")

    private fun qwerty(
        id: LayoutId,
        shifted: Boolean,
        numberRow: Boolean,
        devanagariNumerals: Boolean,
        urlRow: List<String> = emptyList(),
    ): KeyboardLayout {
        val rows = ArrayList<KeyRow>(6)

        if (urlRow.isNotEmpty()) {
            rows += KeyRow(
                keys = urlRow.map { value -> charKey(value, weight = if (value.length > 1) 1.6f else 1f) },
                indentLeft = 0f,
                indentRight = 0f,
            )
        }

        if (numberRow) rows += numberRowKeys(devanagariNumerals, nepaliFlavoured = false)

        rows += KeyRow(
            keys = "qwertyuiop".map { letter -> latinLetter(letter, shifted) },
        )
        rows += KeyRow(
            keys = "asdfghjkl".map { letter -> latinLetter(letter, shifted) },
            indentLeft = 0.5f,
            indentRight = 0.5f,
        )
        rows += KeyRow(
            keys = listOf(
                shiftKey(),
                latinLetter('z', shifted),
                latinLetter('x', shifted),
                latinLetter('c', shifted),
                latinLetter('v', shifted),
                latinLetter('b', shifted),
                latinLetter('n', shifted),
                latinLetter('m', shifted),
                backspaceKey(),
            ),
        )
        // Bottom row: symbols, comma, space, period, the always-available script
        // switch and the editor action. Weights total 10 so the row lines up with
        // the letter rows above it.
        rows += KeyRow(
            keys = listOf(
                symbolsKey(1.5f),
                charKey(",", longPress = listOf(";", ":", "\u0964")),
                spaceKey(3.5f),
                charKey(".", longPress = listOf(".com", ".np", "\u2026", ".org")),
                functionKey(KeyKind.LAYOUT_SWITCH, weight = 1f, labelRes = R.string.key_layout),
                enterKey(2f),
            ),
        )
        return KeyboardLayout(id, rows)
    }

    private fun latinLetter(letter: Char, shifted: Boolean): KeyDef {
        val lower = letter.toString()
        val output = if (shifted) lower.uppercase() else lower
        val longPress = LATIN_ACCENTS[lower].orEmpty().let { accents ->
            if (shifted) accents.map { it.uppercase() } else accents
        }
        return charKey(output = output, label = output, longPress = longPress)
    }

    /** Latin diacritics reachable by holding a letter. */
    private val LATIN_ACCENTS: Map<String, List<String>> = mapOf(
        "a" to listOf("\u00E1", "\u00E0", "\u00E2", "\u00E4", "\u00E3", "\u00E5"),
        "c" to listOf("\u00E7", "\u0107", "\u010D"),
        "e" to listOf("\u00E9", "\u00E8", "\u00EA", "\u00EB"),
        "i" to listOf("\u00ED", "\u00EC", "\u00EE", "\u00EF"),
        "n" to listOf("\u00F1", "\u0144"),
        "o" to listOf("\u00F3", "\u00F2", "\u00F4", "\u00F6", "\u00F5", "\u00F8"),
        "s" to listOf("\u015B", "\u0161", "\u015F"),
        "u" to listOf("\u00FA", "\u00F9", "\u00FB", "\u00FC"),
        "y" to listOf("\u00FD", "\u00FF"),
        "z" to listOf("\u017E", "\u017A", "\u017C"),
    )

    // -----------------------------------------------------------------------
    // Devanagari layouts
    // -----------------------------------------------------------------------

    /** Independent vowel reached by holding a dependent vowel sign (matra). */
    private val MATRA_TO_VOWEL: Map<String, String> = mapOf(
        "\u093E" to "\u0906", // ा -> आ
        "\u093F" to "\u0907", // ि -> इ
        "\u0940" to "\u0908", // ी -> ई
        "\u0941" to "\u0909", // ु -> उ
        "\u0942" to "\u090A", // ू -> ऊ
        "\u0947" to "\u090F", // े -> ए
        "\u0948" to "\u0910", // ै -> ऐ
        "\u094B" to "\u0913", // ो -> ओ
        "\u094C" to "\u0914", // ौ -> औ
        "\u0943" to "\u090B", // ृ -> ऋ
    )

    /** Nukta form reached by holding a consonant (क -> क़, ड -> ड़ ...). */
    private val NUKTA_FORMS: Map<String, String> = mapOf(
        "\u0915" to "\u0915\u093C", // क -> क़
        "\u0916" to "\u0916\u093C", // ख -> ख़
        "\u0917" to "\u0917\u093C", // ग -> ग़
        "\u091C" to "\u091C\u093C", // ज -> ज़
        "\u0921" to "\u0921\u093C", // ड -> ड़
        "\u0922" to "\u0922\u093C", // ढ -> ढ़
        "\u092B" to "\u092B\u093C", // फ -> फ़
        "\u092F" to "\u092F\u093C", // य -> य़
    )

    private fun devanagariKeyWithLongPress(output: String): KeyDef {
        val longPress = buildList {
            MATRA_TO_VOWEL[output]?.let { add(it) }
            NUKTA_FORMS[output]?.let { add(it) }
            when (output) {
                "\u0902" -> add("\u0901")            // ं -> ँ
                "\u0903" -> add("\u093D")            // ः -> ऽ
                "\u094D" -> add("\u093C")            // ् -> ़
                "\u0905" -> add("\u0906")            // अ -> आ
                "\u0907" -> addAll(listOf("\u0908", "\u0908\u0902"))
                "\u0909" -> add("\u090A")            // उ -> ऊ
                "\u090F" -> addAll(listOf("\u0910", "\u0913", "\u0914"))
                "\u0913" -> add("\u0914")            // ओ -> औ
                "\u090B" -> add("\u0960")            // ऋ -> ॠ
            }
        }
        return devanagariKey(output = output, longPress = longPress)
    }

    private fun devanagari(numberRow: Boolean, devanagariNumerals: Boolean): KeyboardLayout {
        val rows = ArrayList<KeyRow>(6)
        if (numberRow) rows += numberRowKeys(devanagariNumerals, nepaliFlavoured = true)

        // Row 1: dependent vowel signs (matras) + anusvara.
        rows += KeyRow(
            keys = listOf(
                "\u093E", "\u093F", "\u0940", "\u0941", "\u0942",
                "\u0947", "\u0948", "\u094B", "\u094C", "\u0902",
            ).map { devanagariKeyWithLongPress(it) },
        )
        // Row 2 + 3: the workhorse consonants and the independent vowels that are
        // not reachable from a matra long-press.
        rows += KeyRow(
            keys = listOf(
                "\u0915", "\u0916", "\u0917", "\u0918", "\u091A",
                "\u091B", "\u091C", "\u091F", "\u0920", "\u0921",
            ).map { devanagariKeyWithLongPress(it) },
        )
        rows += KeyRow(
            keys = listOf(
                "\u0922", "\u0924", "\u0925", "\u0926", "\u0927",
                "\u0928", "\u092A", "\u092B", "\u092C", "\u0913",
            ).map { devanagariKeyWithLongPress(it) },
        )
        // Row 4: shift into the extended page, more consonants, backspace.
        rows += KeyRow(
            keys = buildList {
                add(functionKey(KeyKind.SHIFT, weight = 1.5f, labelRes = R.string.key_shift))
                for (letter in listOf("\u092D", "\u092E", "\u092F", "\u0930", "\u0932", "\u0935", "\u0938")) {
                    add(devanagariKeyWithLongPress(letter))
                }
                add(backspaceKey())
            },
        )
        // Row 5: halant, chandrabindu, space, danda, script switch, enter.
        rows += KeyRow(
            keys = listOf(
                symbolsKey(1.5f),
                devanagariKeyWithLongPress("\u094D"),
                devanagariKeyWithLongPress("\u0901"),
                spaceKey(3f),
                devanagariKey("\u0964"),
                functionKey(KeyKind.LAYOUT_SWITCH, weight = 1f, labelRes = R.string.key_layout),
                enterKey(1.5f),
            ),
        )
        return KeyboardLayout(LayoutId.DEVANAGARI, rows)
    }

    private fun devanagariAlt(): KeyboardLayout {
        val rows = ArrayList<KeyRow>(5)
        rows += KeyRow(
            keys = listOf(
                "\u0919", "\u091E", "\u0923", "\u091D", "\u0937",
                "\u0939", "\u0936", "\u0915\u094D\u0937", "\u0924\u094D\u0930", "\u091C\u094D\u091E",
            ).map { devanagariKeyWithLongPress(it) },
        )
        rows += KeyRow(
            keys = listOf(
                "\u093D", "\u093C", "\u0903", "\u0943", "\u0960",
                "\u0950", "\u0964", "\u0965", "\u096E", "\u096F",
            ).map { devanagariKeyWithLongPress(it) },
        )
        rows += KeyRow(
            keys = buildList {
                add(functionKey(KeyKind.SHIFT, weight = 1f, label = "\u0905"))
                for (digit in listOf("\u0966", "\u0967", "\u0968", "\u0969", "\u096A", "\u096B", "\u096C", "\u096D")) {
                    add(devanagariKey(digit))
                }
                add(backspaceKey(1f))
            },
        )
        rows += KeyRow(
            keys = listOf(
                symbolsKey(),
                charKey(",", longPress = listOf(";", ":")),
                spaceKey(4.5f),
                charKey(".", longPress = listOf(".com", ".np")),
                enterKey(2f),
            ),
        )
        return KeyboardLayout(LayoutId.DEVANAGARI_ALT, rows)
    }

    // -----------------------------------------------------------------------
    // Symbols
    // -----------------------------------------------------------------------

    private val SYMBOLS_ROW_1 = listOf("!", "@", "#", "$", "%", "^", "&", "*", "(", ")")
    private val SYMBOLS_ROW_2 = listOf("-", "_", "=", "+", "[", "]", "{", "}", "/", "\\")
    private val SYMBOLS_ROW_3 = listOf("\"", "'", ";", ":", "~", "`", "|")
    private val SYMBOLS_ALT_ROW_1 = listOf("\u00B0", "\u00B1", "\u00D7", "\u00F7", "\u2260", "\u2248", "\u221E", "\u2122", "\u00A9", "\u00AE")
    private val SYMBOLS_ALT_ROW_2 = listOf("\u20B9", "$", "\u20AC", "\u00A3", "\u00A5", "\u20A9", "\u20BD", "\u00A2", "\u00A4", "\u20BA")
    private val SYMBOLS_ALT_ROW_3 = listOf(";", ":", "'", "\"", "\u00AB", "\u00BB", "\u2026", "\u203D", "\u00A1", "\u00BF")
    private val SYMBOLS_ALT_ROW_4 = listOf("`", "\u00B4", "^", "\u00A8", "\u02DC", "\u00AF", "\u02DA")

    private fun symbols(numberRow: Boolean): KeyboardLayout {
        val rows = ArrayList<KeyRow>(6)
        if (numberRow) rows += numberRowKeys(devanagariNumerals = false, nepaliFlavoured = false)
        rows += KeyRow(SYMBOLS_ROW_1.map { charKey(it) })
        rows += KeyRow(SYMBOLS_ROW_2.map { charKey(it) })
        rows += KeyRow(
            keys = buildList {
                add(functionKey(KeyKind.LAYOUT_SWITCH, weight = 1.5f, labelRes = R.string.key_letters))
                for (symbol in SYMBOLS_ROW_3) add(charKey(symbol))
                add(backspaceKey())
            },
        )
        rows += KeyRow(
            keys = listOf(
                functionKey(KeyKind.SYMBOLS, weight = 1.5f, label = "1/2"),
                emojiKey(),
                spaceKey(4f),
                charKey(".", longPress = listOf(".com", ".np", "\u2026")),
                enterKey(2.5f),
            ),
        )
        return KeyboardLayout(LayoutId.SYMBOLS, rows)
    }

    private fun symbolsAlt(numberRow: Boolean): KeyboardLayout {
        val rows = ArrayList<KeyRow>(6)
        if (numberRow) rows += numberRowKeys(devanagariNumerals = false, nepaliFlavoured = false)
        rows += KeyRow(SYMBOLS_ALT_ROW_1.map { charKey(it) })
        rows += KeyRow(SYMBOLS_ALT_ROW_2.map { charKey(it) })
        rows += KeyRow(SYMBOLS_ALT_ROW_3.map { charKey(it) })
        rows += KeyRow(
            keys = buildList {
                add(functionKey(KeyKind.LAYOUT_SWITCH, weight = 1.5f, labelRes = R.string.key_letters))
                for (symbol in SYMBOLS_ALT_ROW_4) add(charKey(symbol))
                add(backspaceKey())
            },
        )
        rows += KeyRow(
            keys = listOf(
                functionKey(KeyKind.SYMBOLS, weight = 1.5f, label = "2/2"),
                emojiKey(),
                spaceKey(4f),
                charKey(".", longPress = listOf(".com", ".np", "\u2026")),
                enterKey(2.5f),
            ),
        )
        return KeyboardLayout(LayoutId.SYMBOLS_ALT, rows)
    }

    // -----------------------------------------------------------------------
    // Specialised numeric layouts (EditorInfo driven)
    // -----------------------------------------------------------------------

    private fun numeric(): KeyboardLayout {
        val rows = listOf(
            KeyRow(listOf(charKey("1"), charKey("2"), charKey("3"), backspaceKey(1f))),
            KeyRow(listOf(charKey("4"), charKey("5"), charKey("6"), charKey("-"))),
            KeyRow(listOf(charKey("7"), charKey("8"), charKey("9"), charKey("/"))),
            KeyRow(listOf(charKey(".", longPress = listOf(",")), charKey("0"), charKey(","), enterKey(1f))),
        )
        return KeyboardLayout(LayoutId.NUMERIC, rows)
    }

    private fun phone(): KeyboardLayout {
        val rows = listOf(
            KeyRow(listOf(charKey("1"), charKey("2"), charKey("3"), backspaceKey(1f))),
            KeyRow(listOf(charKey("4"), charKey("5"), charKey("6"), charKey("-"))),
            KeyRow(listOf(charKey("7"), charKey("8"), charKey("9"), charKey("+"))),
            KeyRow(listOf(charKey("*"), charKey("0"), charKey("#"), enterKey(1f))),
        )
        return KeyboardLayout(LayoutId.PHONE, rows)
    }
}
