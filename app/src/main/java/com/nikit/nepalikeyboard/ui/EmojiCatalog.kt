package com.nikit.nepalikeyboard.ui

import com.nikit.nepalikeyboard.R

/**
 * =============================================================================
 * THE EMOJI CATALOGUE
 * =============================================================================
 *
 * A hand-curated set of emoji grouped into the eight categories the picker
 * exposes.
 *
 * ### Why a hand-written table rather than the full Unicode data files
 *
 * The complete emoji set is roughly 3,600 sequences, and representing it
 * faithfully requires `emoji-sequences.txt`, the variation-selector rules, the
 * ZWJ join rules behind every skin-tone and gender combination, and the
 * regional-indicator pairs for flags. That is a file-format project in its own
 * right, and doing it badly yields a picker full of tofu boxes.
 *
 * This catalogue picks the sequences people actually type instead. Every entry
 * is a single, fully-qualified sequence with no combining skin-tone modifier,
 * so it renders identically from Android 8 upwards. The cost is breadth; the
 * benefit is that nothing in the picker is ever a blank rectangle.
 *
 * ### Search
 *
 * Each entry carries keyword aliases, and search matches on those — not on the
 * emoji, because there is no way to type an emoji into a search box with this
 * keyboard on screen. Aliases include both English and Romanized Nepali terms,
 * since a user of a Nepali keyboard is as likely to search for "mitho" as for
 * "tasty".
 *
 * ### Ordering
 *
 * Roughly by frequency of use within each category, so the first row is what a
 * user most likely wants. That is the cheapest available improvement to a
 * picker's ergonomics and it costs nothing at runtime.
 */
object EmojiCatalog {

    /**
     * One emoji and the words that should find it.
     *
     * @property glyph the emoji sequence itself
     * @property keywords lower-case search terms, English and Romanized Nepali
     */
    data class Entry(
        val glyph: String,
        val keywords: String
    ) {
        /** True when the keyword blob contains [query], already lower-cased. */
        fun matches(query: String): Boolean = keywords.contains(query)
    }

    /**
     * A picker category.
     *
     * @property id stable identifier, used as the tab key
     * @property labelRes tab label
     * @property entries the emoji in this category
     */
    data class Category(
        val id: String,
        val labelRes: Int,
        val entries: List<Entry>
    )

    /** Smileys and emotion faces. */
    private val SMILEYS = listOf(
        e("\uD83D\uDE00", "grin smile happy khushi hasira"),
        e("\uD83D\uDE04", "smile joy happy khushi hasira"),
        e("\uD83D\uDE01", "grin beam happy khushi"),
        e("\uD83D\uDE06", "laugh xd hasira hasso"),
        e("\uD83E\uDD23", "rofl laugh lol hasso"),
        e("\uD83D\uDE02", "tears joy laugh hasira"),
        e("\uD83D\uDE05", "sweat smile nervous"),
        e("\uD83D\uDE07", "innocent angel halo"),
        e("\uD83D\uDE0A", "blush smile shy laj"),
        e("\uD83D\uDE0D", "heart eyes love maya"),
        e("\uD83E\uDD70", "smiling hearts love maya"),
        e("\uD83D\uDE18", "kiss love maya"),
        e("\uD83D\uDE17", "kiss love maya"),
        e("\uD83D\uDE0B", "yum tasty delicious mitho"),
        e("\uD83D\uDE1B", "tongue playful"),
        e("\uD83E\uDD2A", "zany goofy"),
        e("\uD83D\uDE10", "neutral meh"),
        e("\uD83D\uDE11", "expressionless blank"),
        e("\uD83D\uDE36", "no mouth speechless"),
        e("\uD83D\uDE44", "thinking hmm soch"),
        e("\uD83E\uDD14", "thinking hmm soch"),
        e("\uD83D\uDE0F", "smirk smug"),
        e("\uD83D\uDE12", "unamused annoyed"),
        e("\uD83D\uDE13", "grimace awkward"),
        e("\uD83D\uDE14", "relieved calm"),
        e("\uD83D\uDE15", "pensive sad"),
        e("\uD83E\uDD28", "triumph proud"),
        e("\uD83D\uDE16", "confused worried"),
        e("\uD83D\uDE1F", "worried anxious chinta"),
        e("\uD83D\uDE41", "frown sad dukha"),
        e("\uD83D\uDE22", "cry sad dukha runa"),
        e("\uD83D\uDE2D", "sob cry sad dukha"),
        e("\uD83D\uDE24", "angry rage ris"),
        e("\uD83D\uDE20", "angry ris"),
        e("\uD83E\uDD2C", "swear cursing"),
        e("\uD83D\uDE21", "rage angry ris"),
        e("\uD83D\uDE31", "scream shock dar"),
        e("\uD83D\uDE28", "fearful scared dar"),
        e("\uD83D\uDE30", "cold freezing jado"),
        e("\uD83D\uDE25", "sad sweat disappointed"),
        e("\uD83D\uDE2A", "tired sleepy nidra"),
        e("\uD83E\uDD24", "sleeping tired nidra"),
        e("\uD83D\uDE34", "sleep zzz nidra"),
        e("\uD83E\uDD12", "shush quiet"),
        e("\uD83E\uDD15", "sick thermometer birami"),
        e("\uD83E\uDD27", "sneeze sick"),
        e("\uD83E\uDD20", "explode mind blown"),
        e("\uD83E\uDD73", "party celebrate jashn"),
        e("\uD83E\uDD79", "star struck wow"),
        e("\uD83E\uDD2F", "shrug maybe"),
        e("\uD83D\uDE33", "flushed shy laj"),
        e("\uD83E\uDD75", "hot sweaty garmi"),
        e("\uD83D\uDE37", "mask sick birami"),
        e("\uD83E\uDD11", "nerd glasses"),
        e("\uD83D\uDE0E", "cool sunglasses"),
        e("\uD83E\uDD34", "wink playful"),
        e("\uD83D\uDE09", "wink cheerful"),
        e("\uD83E\uDD17", "hug huggi"),
        e("\uD83E\uDD2D", "salute namaste"),
        e("\uD83D\uDE4F", "please thanks folded hands namaste dhanyabad"),
        e("\uD83D\uDC4D", "thumbs up ok thik"),
        e("\uD83D\uDC4E", "thumbs down no nah"),
        e("\uD83D\uDC4C", "ok perfect thik"),
        e("\uD83D\uDC4F", "clap applause"),
        e("\uD83D\uDE4C", "raise hands praise")
    )

    /** People, gestures, and roles. */
    private val PEOPLE = listOf(
        e("\uD83D\uDC4B", "wave hello hi namaste"),
        e("\uD83E\uDD1D", "handshake deal"),
        e("\uD83D\uDE4F", "folded hands please thanks namaste"),
        e("\uD83D\uDC4A", "fist bump"),
        e("\uD83D\uDC64", "person human manis"),
        e("\uD83D\uDC65", "people group samuha"),
        e("\uD83D\uDC66", "boy chora"),
        e("\uD83D\uDC67", "girl chori"),
        e("\uD83D\uDC68", "man purush"),
        e("\uD83D\uDC69", "woman mahila"),
        e("\uD83D\uDC74", "old man budo"),
        e("\uD83D\uDC75", "old woman budhi"),
        e("\uD83D\uDC76", "baby bacha"),
        e("\uD83D\uDC70", "bride dulahi wedding bihe"),
        e("\uD83E\uDD35", "groom dulaha wedding bihe"),
        e("\uD83D\uDC78", "police officer prahari"),
        e("\uD83D\uDC77", "worker labour kamdar"),
        e("\uD83E\uDDD1\u200D\u2695\uFE0F", "doctor health daktar"),
        e("\uD83E\uDDD1\u200D\uD83C\uDFEB", "teacher guru shikshak"),
        e("\uD83E\uDDD1\u200D\uD83C\uDF3E", "farmer kisan"),
        e("\uD83E\uDDD1\u200D\uD83C\uDF73", "cook chef"),
        e("\uD83E\uDDD1\u200D\uD83D\uDCBB", "developer coder programmer"),
        e("\uD83E\uDDD1\u200D\uD83C\uDFA8", "artist kalakar"),
        e("\uD83E\uDDD1\u200D\u2708\uFE0F", "pilot"),
        e("\uD83D\uDCAA", "strong muscle bal"),
        e("\uD83D\uDC82", "guard soldier sainik"),
        e("\uD83E\uDDD8", "meditate yoga dhyan"),
        e("\uD83C\uDFC3", "run running daud"),
        e("\uD83D\uDEB6", "walk walking hid"),
        e("\uD83E\uDD38", "cartwheel"),
        e("\uD83D\uDD7A", "dance nach"),
        e("\uD83C\uDFCB\uFE0F", "gym workout"),
        e("\uD83D\uDE4B", "raise hand"),
        e("\uD83D\uDE47", "gesture no"),
        e("\uD83D\uDC81", "shrug sorry"),
        e("\uD83D\uDC86", "pray namaste puja"),
        e("\uD83D\uDC85", "nail polish"),
        e("\uD83E\uDD33", "superhero"),
        e("\uD83E\uDDD9", "wizard magic jadu"),
        e("\uD83C\uDF85", "santa"),
        e("\uD83D\uDC83", "dancer nach"),
        e("\uD83D\uDD75\uFE0F", "detective"),
        e("\uD83D\uDC54", "speaking"),
        e("\uD83D\uDC6B", "couple love maya"),
        e("\uD83D\uDC8F", "kiss couple"),
        e("\uD83E\uDD32", "high five"),
        e("\uD83D\uDC50", "open hands"),
        e("\uD83E\uDD1F", "fist")
    )

    /** Animals and nature. */
    private val ANIMALS = listOf(
        e("\uD83D\uDC36", "dog kukur"),
        e("\uD83D\uDC31", "cat biralo"),
        e("\uD83D\uDC2D", "mouse musa"),
        e("\uD83D\uDC39", "rabbit khargosh"),
        e("\uD83E\uDD8A", "fox"),
        e("\uD83D\uDC3B", "bear bhalu"),
        e("\uD83D\uDC3C", "panda"),
        e("\uD83E\uDD81", "lion sher"),
        e("\uD83D\uDC2F", "tiger bagh"),
        e("\uD83D\uDC2E", "cow gai"),
        e("\uD83D\uDC37", "pig sungur"),
        e("\uD83D\uDC38", "frog bhyaguto"),
        e("\uD83D\uDC35", "monkey bandar"),
        e("\uD83D\uDC14", "chicken kukhura"),
        e("\uD83D\uDC13", "hatching egg anda"),
        e("\uD83D\uDC23", "egg anda"),
        e("\uD83D\uDC26", "bird chara"),
        e("\uD83E\uDD86", "owl"),
        e("\uD83D\uDC27", "duck hans"),
        e("\uD83E\uDD85", "eagle chil"),
        e("\uD83D\uDC1D", "snake sarpa"),
        e("\uD83D\uDC22", "turtle kachhapa"),
        e("\uD83D\uDC0A", "snail"),
        e("\uD83D\uDC1B", "bug insect kira"),
        e("\uD83D\uDC1C", "ant kamila"),
        e("\uD83D\uDC1E", "bee honey maha"),
        e("\uD83E\uDD8B", "butterfly putali"),
        e("\uD83D\uDD77\uFE0F", "spider makura"),
        e("\uD83D\uDC19", "octopus"),
        e("\uD83D\uDC1F", "fish machha"),
        e("\uD83D\uDC20", "tropical fish machha"),
        e("\uD83D\uDC2C", "dolphin"),
        e("\uD83D\uDC0B", "whale"),
        e("\uD83D\uDC0D", "crocodile"),
        e("\uD83D\uDC10", "elephant hatti"),
        e("\uD83E\uDD84", "unicorn"),
        e("\uD83D\uDC2A", "horse ghoda"),
        e("\uD83D\uDC2B", "goat bakra"),
        e("\uD83D\uDC11", "sheep bheda"),
        e("\uD83D\uDC3A", "wolf"),
        e("\uD83E\uDD81", "lion"),
        e("\uD83C\uDF3F", "herb plant botany"),
        e("\uD83C\uDF31", "seedling plant biruwa"),
        e("\uD83C\uDF32", "tree rukh"),
        e("\uD83C\uDF34", "palm tree"),
        e("\uD83C\uDF35", "cactus"),
        e("\uD83C\uDF3E", "rice sheaf dhan"),
        e("\uD83C\uDF3A", "flower phool"),
        e("\uD83C\uDF37", "tulip flower phool"),
        e("\uD83C\uDF39", "rose flower phool gulab"),
        e("\uD83C\uDF3B", "sunflower suryamukhi"),
        e("\uD83C\uDF3C", "blossom flower phool"),
        e("\uD83C\uDF08", "rainbow indreni"),
        e("\u2600\uFE0F", "sun gham"),
        e("\uD83C\uDF24\uFE0F", "sun cloud weather"),
        e("\u26C5", "cloud sun"),
        e("\uD83C\uDF25\uFE0F", "cloud rain"),
        e("\uD83C\uDF27\uFE0F", "rain cloud pani"),
        e("\u26C8\uFE0F", "storm thunder chatyang"),
        e("\u2744\uFE0F", "snowflake snow him"),
        e("\u26A1", "lightning bijuli"),
        e("\uD83C\uDF0A", "wave sea samudra"),
        e("\uD83C\uDF0D", "earth globe world sansar"),
        e("\uD83C\uDF19", "moon chandra jun"),
        e("\u2B50", "star tara"),
        e("\uD83C\uDF1F", "glowing star tara"),
        e("\u2604\uFE0F", "comet"),
        e("\uD83D\uDD25", "fire aago")
    )

    /** Food and drink. */
    private val FOOD = listOf(
        e("\uD83C\uDF4E", "apple syau"),
        e("\uD83C\uDF4C", "banana kera"),
        e("\uD83C\uDF47", "grapes angoor"),
        e("\uD83C\uDF49", "watermelon tarbuja"),
        e("\uD83C\uDF4A", "orange santra"),
        e("\uD83C\uDF4B", "lemon kagati"),
        e("\uD83C\uDF53", "strawberry"),
        e("\uD83C\uDF51", "peach aaru"),
        e("\uD83C\uDF52", "cherry"),
        e("\uD83E\uDD65", "mango aap"),
        e("\uD83C\uDF4D", "pineapple bhuyikatahar"),
        e("\uD83E\uDD51", "avocado"),
        e("\uD83C\uDF45", "tomato golbheda"),
        e("\uD83E\uDD55", "cucumber kakro"),
        e("\uD83C\uDF3D", "corn makai"),
        e("\uD83C\uDF36\uFE0F", "hot pepper khursani"),
        e("\uD83E\uDDC4", "carrot gajar"),
        e("\uD83E\uDD66", "broccoli"),
        e("\uD83E\uDDC5", "garlic lasun"),
        e("\uD83E\uDDC2", "onion pyaj"),
        e("\uD83E\uDD54", "potato alu"),
        e("\uD83C\uDF5E", "bread roti"),
        e("\uD83E\uDD68", "dumpling momo"),
        e("\uD83C\uDF5A", "cooked rice bhat"),
        e("\uD83C\uDF5B", "curry tarkari"),
        e("\uD83C\uDF5C", "steaming bowl soup"),
        e("\uD83C\uDF5D", "spaghetti noodles chowmin"),
        e("\uD83C\uDF5F", "burger"),
        e("\uD83C\uDF54", "hamburger"),
        e("\uD83C\uDF55", "pizza"),
        e("\uD83C\uDF2D", "hot dog"),
        e("\uD83C\uDF2E", "taco"),
        e("\uD83C\uDF2F", "burrito"),
        e("\uD83C\uDF57", "meat on bone masu"),
        e("\uD83C\uDF63", "sushi"),
        e("\uD83C\uDF69", "ice cream"),
        e("\uD83C\uDF66", "doughnut"),
        e("\uD83C\uDF6A", "cake"),
        e("\uD83C\uDF70", "shortcake"),
        e("\uD83C\uDF6B", "chocolate"),
        e("\uD83C\uDF6C", "candy"),
        e("\uD83C\uDF6D", "lollipop"),
        e("\uD83C\uDF6E", "custard"),
        e("\uD83C\uDF6F", "honey maha"),
        e("\u2615", "coffee chiya"),
        e("\uD83C\uDF75", "tea chiya"),
        e("\uD83E\uDD64", "drink juice ras"),
        e("\uD83E\uDD6B", "beverage glass"),
        e("\uD83E\uDD5B", "milk doodh"),
        e("\uD83C\uDF7A", "beer"),
        e("\uD83C\uDF7B", "beers"),
        e("\uD83C\uDF77", "wine"),
        e("\uD83D\uDCA7", "water drop pani"),
        e("\uD83E\uDDC1", "salt nun"),
        e("\uD83C\uDF71", "bento box"),
        e("\uD83C\uDF58", "poultry leg"),
        e("\uD83C\uDF59", "rice cracker"),
        e("\uD83C\uDF5D", "noodles"),
        e("\uD83E\uDD68", "momo"),
        e("\uD83C\uDF72", "pot of food")
    )

    /** Travel, places, and transport. */
    private val TRAVEL = listOf(
        e("\uD83D\uDE97", "car gadi"),
        e("\uD83D\uDE95", "taxi"),
        e("\uD83D\uDE8C", "bus"),
        e("\uD83D\uDEB2", "bicycle cycle"),
        e("\uD83C\uDFCD\uFE0F", "motorcycle bike"),
        e("\uD83D\uDE9A", "truck"),
        e("\uD83D\uDE82", "train rel"),
        e("\uD83D\uDE84", "train rel"),
        e("\u2708\uFE0F", "airplane hawaijahaj"),
        e("\uD83D\uDE81", "airplane hawaijahaj"),
        e("\uD83D\uDE80", "rocket"),
        e("\uD83D\uDEA2", "ship jahaj"),
        e("\u26F5", "boat"),
        e("\uD83D\uDEA4", "speedboat"),
        e("\uD83D\uDEB4", "bike cyclist"),
        e("\uD83D\uDEA6", "traffic light"),
        e("\uD83C\uDFE0", "house ghar"),
        e("\uD83C\uDFE1", "house garden ghar"),
        e("\uD83C\uDFE2", "office karyalaya"),
        e("\uD83C\uDFE5", "hospital aspatal"),
        e("\uD83C\uDFEB", "school biddyalaya"),
        e("\uD83D\uDD4C", "temple mandir"),
        e("\uD83D\uDD4D", "mosque"),
        e("\u26EA", "church"),
        e("\uD83C\uDFF0", "castle durbar"),
        e("\uD83C\uDFD5\uFE0F", "camping tent"),
        e("\uD83C\uDF05", "sunrise suryodaya"),
        e("\uD83C\uDF04", "sunset"),
        e("\uD83C\uDF0B", "volcano"),
        e("\uD83D\uDDFB", "mountain himal"),
        e("\uD83C\uDFD4\uFE0F", "snow mountain himal"),
        e("\uD83C\uDFD6\uFE0F", "desert"),
        e("\uD83C\uDFDD\uFE0F", "island"),
        e("\uD83C\uDFD7\uFE0F", "stadium"),
        e("\uD83D\uDDFC", "map naksha"),
        e("\uD83E\uDDED", "compass"),
        e("\uD83D\uDEA1", "police car"),
        e("\uD83D\uDE91", "ambulance"),
        e("\uD83D\uDE92", "fire engine damkal"),
        e("\uD83D\uDEA8", "traffic cone"),
        e("\u26FD", "fuel petrol"),
        e("\uD83D\uDEA7", "construction"),
        e("\uD83D\uDEF3\uFE0F", "motor scooter"),
        e("\uD83C\uDFA1", "ferris wheel"),
        e("\uD83C\uDFD9\uFE0F", "city night shar"),
        e("\uD83C\uDF07", "cityscape shar"),
        e("\uD83C\uDF06", "city sunset"),
        e("\uD83D\uDE89", "railway"),
        e("\uD83D\uDE8A", "minibus"),
        e("\uD83D\uDE9C", "tractor"),
        e("\uD83D\uDEE9\uFE0F", "motorway"),
        e("\uD83D\uDEE4\uFE0F", "railway track"),
        e("\uD83C\uDF0C", "night sky"),
        e("\uD83C\uDF0E", "globe americas"),
        e("\uD83C\uDF10", "globe asia")
    )

    /** Objects, tools, and everyday things. */
    private val OBJECTS = listOf(
        e("\uD83D\uDCF1", "phone mobile"),
        e("\uD83D\uDCBB", "laptop computer"),
        e("\u2328\uFE0F", "keyboard"),
        e("\uD83D\uDDA5\uFE0F", "desktop computer"),
        e("\uD83D\uDDA8\uFE0F", "printer"),
        e("\uD83D\uDDB1\uFE0F", "mouse"),
        e("\uD83D\uDCBD", "floppy disk"),
        e("\uD83D\uDCBE", "cd dvd"),
        e("\uD83C\uDFA5", "movie film"),
        e("\uD83D\uDCF7", "camera"),
        e("\uD83D\uDCF9", "video camera"),
        e("\uD83D\uDCFA", "tv television"),
        e("\uD83D\uDCFB", "radio"),
        e("\uD83C\uDFA7", "headphones"),
        e("\uD83C\uDFA4", "microphone"),
        e("\u23F0", "alarm clock ghadi"),
        e("\u231A", "watch ghadi"),
        e("\u23F1\uFE0F", "stopwatch"),
        e("\uD83D\uDD0B", "battery"),
        e("\uD83D\uDD0C", "plug"),
        e("\uD83D\uDCA1", "light bulb batti"),
        e("\uD83D\uDD26", "flashlight"),
        e("\uD83D\uDD0D", "magnifier search khoj"),
        e("\uD83D\uDD12", "lock talcha"),
        e("\uD83D\uDD13", "unlock"),
        e("\uD83D\uDD11", "key sacho"),
        e("\uD83D\uDD28", "hammer hathoda"),
        e("\uD83E\uDE93", "axe bancharo"),
        e("\uD83D\uDD27", "wrench"),
        e("\uD83D\uDD29", "screwdriver"),
        e("\u2699\uFE0F", "gear settings"),
        e("\u2696\uFE0F", "scales balance"),
        e("\uD83D\uDC8A", "pill medicine aushadhi"),
        e("\uD83D\uDC89", "syringe"),
        e("\uD83E\uDE79", "bandage"),
        e("\uD83D\uDD2C", "microscope"),
        e("\uD83D\uDD2D", "telescope"),
        e("\uD83D\uDCDA", "books kitab"),
        e("\uD83D\uDCD5", "book kitab"),
        e("\uD83D\uDCD6", "book kitab"),
        e("\uD83D\uDCD3", "notebook copy"),
        e("\uD83D\uDCDD", "memo"),
        e("\u270F\uFE0F", "pencil"),
        e("\uD83D\uDD8A\uFE0F", "pen kalem"),
        e("\uD83D\uDD8B\uFE0F", "paintbrush"),
        e("\uD83D\uDCCF", "ruler"),
        e("\uD83D\uDCC1", "folder"),
        e("\uD83D\uDCC4", "page paper"),
        e("\uD83D\uDCC5", "calendar patro"),
        e("\uD83D\uDCC8", "chart increase"),
        e("\uD83D\uDCC9", "chart decrease"),
        e("\uD83D\uDCB0", "money bag paisa"),
        e("\uD83D\uDCB5", "dollar paisa"),
        e("\uD83D\uDCB7", "yen"),
        e("\uD83D\uDCB3", "credit card"),
        e("\uD83E\uDDFE", "receipt bil"),
        e("\uD83D\uDCBC", "briefcase"),
        e("\uD83C\uDF92", "backpack jhola"),
        e("\uD83D\uDC51", "crown mukut"),
        e("\uD83D\uDC8D", "ring aunthi"),
        e("\uD83D\uDC8E", "gemstone"),
        e("\uD83D\uDC8C", "love letter maya"),
        e("\uD83C\uDF81", "gift upahar"),
        e("\uD83C\uDF88", "balloon"),
        e("\uD83C\uDF89", "party popper"),
        e("\uD83C\uDF8A", "confetti"),
        e("\uD83C\uDF86", "fireworks"),
        e("\uD83D\uDD6F\uFE0F", "candle"),
        e("\uD83E\uDE94", "mirror aina"),
        e("\uD83D\uDECF\uFE0F", "bed"),
        e("\uD83D\uDEAA", "door dhoka"),
        e("\uD83E\uDE91", "chair kursi"),
        e("\uD83D\uDEBF", "shower"),
        e("\uD83D\uDEC1", "bathtub"),
        e("\uD83E\uDDF9", "toothbrush"),
        e("\uD83E\uDDF4", "broom kucho"),
        e("\uD83E\uDEA3", "bucket baltin"),
        e("\uD83E\uDDF8", "soap sabun"),
        e("\uD83E\uDDFB", "thread dhago"),
        e("\uD83E\uDEA1", "needle suilo"),
        e("\uD83D\uDC55", "shirt"),
        e("\uD83D\uDC57", "jeans"),
        e("\uD83D\uDC58", "dress"),
        e("\uD83E\uDDE5", "coat"),
        e("\uD83D\uDC62", "shoe juta"),
        e("\uD83D\uDC5F", "sandal chappal"),
        e("\uD83E\uDDE2", "socks"),
        e("\uD83E\uDDE4", "scarf"),
        e("\uD83D\uDC52", "hat topi"),
        e("\uD83D\uDC53", "glasses chasma"),
        e("\uD83D\uDC54", "necktie"),
        e("\uD83C\uDFA9", "top hat"),
        e("\uD83D\uDC5C", "handbag jhola"),
        e("\uD83C\uDF92", "backpack")
    )

    /** Symbols, signs, and abstract marks. */
    private val SYMBOLS = listOf(
        e("\u2764\uFE0F", "red heart maya"),
        e("\uD83E\uDDE1", "orange heart"),
        e("\uD83D\uDC9B", "yellow heart"),
        e("\uD83D\uDC9A", "green heart"),
        e("\uD83D\uDC99", "blue heart"),
        e("\uD83D\uDC9C", "purple heart"),
        e("\uD83D\uDDA4", "black heart"),
        e("\uD83E\uDD0D", "white heart"),
        e("\uD83D\uDC94", "broken heart dukha"),
        e("\uD83D\uDC95", "two hearts"),
        e("\uD83D\uDC96", "sparkling heart"),
        e("\uD83D\uDC97", "growing heart"),
        e("\uD83D\uDC93", "beating heart"),
        e("\u2705", "check green done"),
        e("\u274C", "cross red no"),
        e("\u2B55", "circle red"),
        e("\u2757", "exclamation important"),
        e("\u2753", "question"),
        e("\u2755", "question white"),
        e("\u2754", "question grey"),
        e("\u203C\uFE0F", "double exclamation"),
        e("\u26A0\uFE0F", "warning chetavani"),
        e("\uD83D\uDD34", "red circle"),
        e("\uD83D\uDFE0", "orange circle"),
        e("\uD83D\uDFE1", "yellow circle"),
        e("\uD83D\uDFE2", "green circle"),
        e("\uD83D\uDD35", "blue circle"),
        e("\uD83D\uDFE3", "purple circle"),
        e("\u26AB", "black circle"),
        e("\u26AA", "white circle"),
        e("\uD83D\uDD3A", "small orange diamond"),
        e("\uD83D\uDD3B", "small blue diamond"),
        e("\u2B06\uFE0F", "up arrow"),
        e("\u2B07\uFE0F", "down arrow"),
        e("\u2B05\uFE0F", "left arrow"),
        e("\u27A1\uFE0F", "right arrow"),
        e("\uD83D\uDD01", "repeat"),
        e("\uD83D\uDD02", "repeat once"),
        e("\u25B6\uFE0F", "play"),
        e("\u23F8\uFE0F", "pause"),
        e("\u23F9\uFE0F", "stop"),
        e("\u23EE\uFE0F", "previous"),
        e("\u23ED\uFE0F", "next"),
        e("\uD83D\uDD0A", "speaker loud awaaj"),
        e("\uD83D\uDD07", "speaker mute"),
        e("\uD83D\uDD14", "bell ghanti"),
        e("\uD83D\uDD15", "bell muted"),
        e("\uD83C\uDFB5", "music note sangeet"),
        e("\uD83C\uDFB6", "music notes sangeet"),
        e("\u2714\uFE0F", "check mark"),
        e("\u2716\uFE0F", "multiply"),
        e("\u2795", "plus"),
        e("\u2796", "minus"),
        e("\u2797", "divide"),
        e("\u267B\uFE0F", "recycle"),
        e("\uD83D\uDD31", "peace"),
        e("\u262F\uFE0F", "dharma wheel dharma"),
        e("\uD83C\uDFAF", "target"),
        e("\uD83C\uDFC6", "trophy"),
        e("\uD83C\uDFC5", "medal"),
        e("\uD83C\uDF96\uFE0F", "medal military"),
        e("\u26D4", "no entry"),
        e("\uD83D\uDEAB", "prohibited no"),
        e("\uD83D\uDD1E", "no one under eighteen"),
        e("\uD83D\uDCF5", "no mobile phones"),
        e("\u267F", "wheelchair"),
        e("\uD83D\uDEB9", "mens"),
        e("\uD83D\uDEBA", "womens"),
        e("\uD83D\uDEBB", "restroom"),
        e("\uD83D\uDEBC", "baby symbol"),
        e("\uD83D\uDEBE", "water closet"),
        e("\u26A1", "high voltage bijuli"),
        e("\u267B\uFE0F", "recycle"),
        e("\u269C\uFE0F", "fleur de lis"),
        e("\u262E\uFE0F", "peace symbol"),
        e("\u2626\uFE0F", "orthodox cross"),
        e("\u271D\uFE0F", "latin cross"),
        e("\u2638\uFE0F", "wheel of dharma"),
        e("\uD83D\uDD49\uFE0F", "om hindu"),
        e("\u2721\uFE0F", "star of david"),
        e("\uD83D\uDD2F", "six pointed star"),
        e("\uD83D\uDCF6", "vibration mode"),
        e("\uD83D\uDCF3", "phone off"),
        e("\uD83D\uDD1D", "no smoking"),
        e("\uD83D\uDD1B", "no bicycles"),
        e("\uD83D\uDD04", "arrows clockwise"),
        e("\uD83D\uDD03", "arrows counterclockwise"),
        e("\uD83D\uDD00", "shuffle"),
        e("\uD83D\uDD05", "low brightness"),
        e("\uD83D\uDD06", "high brightness"),
        e("\uD83D\uDCF2", "phone with arrow"),
        e("\uD83D\uDCE3", "loudspeaker"),
        e("\uD83D\uDCE2", "megaphone"),
        e("\uD83D\uDD50", "clock twelve"),
        e("\uD83D\uDD51", "clock one"),
        e("\uD83D\uDD5B", "clock twelve thirty"),
        e("\uD83D\uDD67", "clock twelve thirty"),
        e("\uD83D\uDD70\uFE0F", "clock one thirty"),
        e("\u231B", "hourglass"),
        e("\u23F3", "hourglass flowing"),
        e("\uD83D\uDCE2", "announcement")
    )

    /** Country flags. */
    private val FLAGS = listOf(
        e("\uD83C\uDDF3\uD83C\uDDF5", "nepal flag"),
        e("\uD83C\uDDEE\uD83C\uDDF3", "india bharat flag"),
        e("\uD83C\uDDE8\uD83C\uDDED", "china flag"),
        e("\uD83C\uDDFA\uD83C\uDDF8", "usa america flag"),
        e("\uD83C\uDDEC\uD83C\uDDE7", "uk britain flag"),
        e("\uD83C\uDDEF\uD83C\uDDF5", "japan flag"),
        e("\uD83C\uDDF0\uD83C\uDDF7", "south korea flag"),
        e("\uD83C\uDDE6\uD83C\uDDFA", "australia flag"),
        e("\uD83C\uDDE8\uD83C\uDDE6", "canada flag"),
        e("\uD83C\uDDE9\uD83C\uDDEA", "germany flag"),
        e("\uD83C\uDDEB\uD83C\uDDF7", "france flag"),
        e("\uD83C\uDDEE\uD83C\uDDF9", "italy flag"),
        e("\uD83C\uDDEA\uD83C\uDDF8", "spain flag"),
        e("\uD83C\uDDF7\uD83C\uDDFA", "russia flag"),
        e("\uD83C\uDDE7\uD83C\uDDF7", "brazil flag"),
        e("\uD83C\uDDF2\uD83C\uDDFD", "mexico flag"),
        e("\uD83C\uDDFF\uD83C\uDDE6", "south africa flag"),
        e("\uD83C\uDDE6\uD83C\uDDEA", "uae emirates flag"),
        e("\uD83C\uDDF8\uD83C\uDDE6", "saudi arabia flag"),
        e("\uD83C\uDDF1\uD83C\uDDF0", "sri lanka flag"),
        e("\uD83C\uDDE7\uD83C\uDDE9", "bangladesh flag"),
        e("\uD83C\uDDF5\uD83C\uDDF0", "pakistan flag"),
        e("\uD83C\uDDE6\uD83C\uDDEB", "afghanistan flag"),
        e("\uD83C\uDDF9\uD83C\uDDED", "thailand flag"),
        e("\uD83C\uDDF2\uD83C\uDDFE", "malaysia flag"),
        e("\uD83C\uDDF8\uD83C\uDDEC", "singapore flag"),
        e("\uD83C\uDDEE\uD83C\uDDE9", "indonesia flag"),
        e("\uD83C\uDDF5\uD83C\uDDED", "philippines flag"),
        e("\uD83C\uDDFB\uD83C\uDDF3", "vietnam flag"),
        e("\uD83C\uDDF9\uD83C\uDDF7", "turkey flag"),
        e("\uD83C\uDDEA\uD83C\uDDEC", "egypt flag"),
        e("\uD83C\uDDF3\uD83C\uDDEC", "nigeria flag"),
        e("\uD83C\uDDF0\uD83C\uDDEA", "kenya flag"),
        e("\uD83C\uDDF5\uD83C\uDDF9", "portugal flag"),
        e("\uD83C\uDDF3\uD83C\uDDF1", "netherlands flag"),
        e("\uD83C\uDDE7\uD83C\uDDEA", "belgium flag"),
        e("\uD83C\uDDF8\uD83C\uDDEA", "sweden flag"),
        e("\uD83C\uDDF3\uD83C\uDDF4", "norway flag"),
        e("\uD83C\uDDE9\uD83C\uDDF0", "denmark flag"),
        e("\uD83C\uDDEB\uD83C\uDDEE", "finland flag"),
        e("\uD83C\uDDF5\uD83C\uDDF1", "poland flag"),
        e("\uD83C\uDDEC\uD83C\uDDF7", "greece flag"),
        e("\uD83C\uDDFA\uD83C\uDDE6", "ukraine flag"),
        e("\uD83C\uDDF7\uD83C\uDDF4", "romania flag"),
        e("\uD83C\uDDED\uD83C\uDDFA", "hungary flag"),
        e("\uD83C\uDDE8\uD83C\uDDFF", "czechia flag"),
        e("\uD83C\uDDE6\uD83C\uDDF9", "austria flag"),
        e("\uD83C\uDDF6\uD83C\uDDE6", "qatar flag"),
        e("\uD83C\uDDF0\uD83C\uDDFC", "kuwait flag"),
        e("\uD83C\uDDE7\uD83C\uDDED", "bahrain flag"),
        e("\uD83C\uDDF4\uD83C\uDDF2", "oman flag"),
        e("\uD83C\uDDEF\uD83C\uDDF4", "jordan flag"),
        e("\uD83C\uDDF1\uD83C\uDDE7", "lebanon flag"),
        e("\uD83C\uDDF8\uD83C\uDDFE", "syria flag"),
        e("\uD83C\uDDEE\uD83C\uDDF6", "iraq flag"),
        e("\uD83C\uDDEE\uD83C\uDDF7", "iran flag"),
        e("\uD83C\uDDF2\uD83C\uDDF2", "myanmar flag"),
        e("\uD83C\uDDF0\uD83C\uDDED", "cambodia flag"),
        e("\uD83C\uDDF1\uD83C\uDDE6", "laos flag"),
        e("\uD83C\uDDF2\uD83C\uDDF3", "mongolia flag"),
        e("\uD83C\uDDF0\uD83C\uDDFF", "kazakhstan flag"),
        e("\uD83C\uDDFA\uD83C\uDDFF", "uzbekistan flag"),
        e("\uD83C\uDDEE\uD83C\uDDF1", "israel flag"),
        e("\uD83C\uDDF5\uD83C\uDDF8", "palestine flag"),
        e("\uD83C\uDDF2\uD83C\uDDF9", "malta flag"),
        e("\uD83C\uDDF8\uD83C\uDDF0", "slovakia flag"),
        e("\uD83C\uDDF8\uD83C\uDDEE", "slovenia flag"),
        e("\uD83C\uDDED\uD83C\uDDF7", "croatia flag"),
        e("\uD83C\uDDF7\uD83C\uDDF8", "serbia flag"),
        e("\uD83C\uDDE7\uD83C\uDDEC", "bulgaria flag"),
        e("\uD83C\uDDEB\uD83C\uDDEE", "finland"),
        e("\uD83C\uDDF8\uD83C\uDDEA", "sweden")
    )

    /**
     * Every category, in tab order.
     *
     * Recents is deliberately absent: it comes from user data rather than the
     * static catalogue, and on a fresh install there is nothing in it, so it
     * must not occupy a tab until it has content.
     */
    val CATEGORIES: List<Category> = listOf(
        Category("smileys", R.string.emoji_category_smileys, SMILEYS),
        Category("people", R.string.emoji_category_people, PEOPLE),
        Category("animals", R.string.emoji_category_animals, ANIMALS),
        Category("food", R.string.emoji_category_food, FOOD),
        Category("travel", R.string.emoji_category_travel, TRAVEL),
        Category("objects", R.string.emoji_category_objects, OBJECTS),
        Category("symbols", R.string.emoji_category_symbols, SYMBOLS),
        Category("flags", R.string.emoji_category_flags, FLAGS)
    )

    /**
     * Flat index used by search.
     *
     * Built once on first search. A linear scan over a few hundred entries is
     * faster than an inverted index would be to construct, and what matters is
     * that the list is not rebuilt per keystroke — which it is not.
     */
    private val ALL: List<Entry> by lazy(LazyThreadSafetyMode.NONE) {
        val out = ArrayList<Entry>(768)
        for (category in CATEGORIES) out.addAll(category.entries)
        out
    }

    /** Number of emoji in the static catalogue, excluding recents. */
    val size: Int get() = ALL.size

    /**
     * Finds every emoji whose keywords contain [query].
     *
     * Ordered by the catalogue's own ordering, so the most common emoji in a
     * category surface first. Duplicates are removed: the catalogue contains a
     * few intentional repetitions where an emoji genuinely belongs to two
     * concepts, and a search result list that repeats itself reads as broken.
     */
    fun search(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val needle = query.trim().lowercase()
        val seen = HashSet<String>(32)
        val out = ArrayList<String>(32)
        for (entry in ALL) {
            if (!entry.matches(needle)) continue
            if (seen.add(entry.glyph)) out.add(entry.glyph)
        }
        return out
    }

    /** Concise constructor for the tables above. */
    private fun e(glyph: String, keywords: String): Entry = Entry(glyph, keywords)
}
