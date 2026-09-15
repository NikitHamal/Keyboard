#!/usr/bin/env python3
"""
Generates app/src/main/assets/dict/ne_lexicon.json

The Devanagari below is written as literal UTF-8 text rather than composed from
Unicode code points. An earlier version built each word from chr(0x09XX)
fragments, which made matra/independent-vowel confusions almost invisible in
review and produced several silently wrong words (e.g. हामरो for हाम्रो).
Literal text is directly reviewable by a Nepali speaker, which is the only
reliable check for this kind of data.

The file is written with encoding="utf-8" and ensure_ascii=False, so the JSON
on disk contains real Devanagari. The Kotlin loader reads it as UTF-8.

Usage:  python tools/build_lexicon.py
"""
import json
import os

# ---------------------------------------------------------------------------
# Vocabulary: (devanagari, canonical_roman, frequency, [alternate spellings])
#
# `canonical_roman` is the spelling the phonetic engine produces for the word.
# `aliases` are the spellings users actually type, which is how the lexicon
# resolves the inherent ambiguity of Romanized Nepali: नेपाल is canonically
# "nepaal" but is overwhelmingly typed "nepal".
#
# Frequencies are relative in [0, 1], roughly Zipfian over a chat corpus.
# ---------------------------------------------------------------------------
WORDS = [
    # ---------------- greetings, politeness ----------------
    ("नमस्ते", "namaste", 1.00, ["namasthe", "namastey"]),
    ("धन्यवाद", "dhanyabaad", 0.98, ["dhanyabad", "dhanybaad", "dhanyavaad"]),
    ("नमस्कार", "namaskaar", 0.86, ["namaskar"]),
    ("स्वागत", "swaagat", 0.66, ["swagat", "swagata"]),
    ("क्षमा", "kshamaa", 0.58, ["kshama", "chhama", "khama"]),
    ("माफ", "maapha", 0.34, ["maaf", "maaph"]),
    ("राम्रो", "raamro", 0.94, ["ramro", "ramroo"]),
    ("सन्चै", "sanchai", 0.82, ["sancai", "sanchhai"]),
    ("ठिक", "thika", 0.84, ["thik", "theek"]),
    ("हुन्छ", "hunchha", 0.88, ["huncha", "hun6a"]),
    ("भयो", "bhayo", 0.80, ["vayo"]),
    ("छ", "chha", 0.92, ["cha", "chhha"]),
    ("हो", "ho", 0.90, []),
    ("होइन", "hoina", 0.74, ["haina", "hoena"]),
    ("कृपया", "kripayaa", 0.30, ["kripaya"]),

    # ---------------- pronouns, determiners ----------------
    ("मेरो", "mero", 0.96, ["meroo"]),
    ("तिम्रो", "timro", 0.78, ["tumro", "timirro"]),
    ("हाम्रो", "haamro", 0.88, ["hamro", "hamaroo"]),
    ("हामी", "haami", 0.70, ["hami", "hamee"]),
    ("तिमी", "timi", 0.66, ["tamee", "timii"]),
    ("तपाईं", "tapaaim", 0.44, ["tapai", "tapaai", "tapaain"]),
    ("उनी", "uni", 0.40, ["uuni"]),
    ("यहाँ", "yahaa", 0.62, ["yaha"]),
    ("त्यहाँ", "tyahaa", 0.58, ["tyaha"]),
    ("कहाँ", "kahaa", 0.64, ["kaha"]),
    ("कि", "ki", 0.70, []),
    ("के", "ke", 0.76, []),
    ("किन", "kina", 0.72, ["kana"]),
    ("कहिले", "kahile", 0.52, ["kabile"]),
    ("अहिले", "ahile", 0.68, ["ahily"]),
    ("पहिले", "pahile", 0.60, ["paile", "pahila"]),
    ("त्यो", "tyo", 0.66, ["tiyo"]),
    ("यो", "yo", 0.78, []),
    ("कुन", "kuna", 0.40, ["kun"]),
    ("सबै", "sabai", 0.56, ["sab"]),
    ("सबैभन्दा", "sabaibhanda", 0.30, ["sabaibhandaa"]),

    # ---------------- people ----------------
    ("मान्छे", "maanche", 0.62, ["manche", "maanchhe"]),
    ("बाउ", "baau", 0.44, ["bau", "bubu"]),
    ("आमा", "aamaa", 0.86, ["ama", "aama"]),
    ("दिदी", "didi", 0.66, ["deedi"]),
    ("भाइ", "bhaai", 0.70, ["bhai", "bhhai"]),
    ("दाइ", "daai", 0.58, ["dai", "daaai"]),
    ("छोरा", "chhora", 0.48, ["chora"]),
    ("छोरी", "chhori", 0.44, ["chori"]),
    ("साथी", "saathi", 0.72, ["sathi", "saathii"]),
    ("संगै", "sangai", 0.54, ["sanga"]),
    ("नाम", "naama", 0.90, ["nam", "naam"]),
    ("गुरु", "guru", 0.36, []),
    ("मानिस", "maanisa", 0.50, ["manis"]),
    ("परिवार", "pariwaara", 0.42, ["pariwar"]),

    # ---------------- places ----------------
    ("नेपाल", "nepaal", 0.99, ["nepal", "nepAl"]),
    ("नेपाली", "nepaali", 0.90, ["nepali"]),
    ("काठमाडौं", "kaathmaadaum", 0.84, ["kathmandu", "kathmadaum", "kathmaadau"]),
    ("पोखरा", "pokharaa", 0.52, ["pokhara", "pokhra"]),
    ("जनकपुर", "janakapura", 0.24, ["janakpur"]),
    ("घर", "ghara", 0.86, ["ghar"]),
    ("बाटो", "baato", 0.58, ["bato", "battu"]),
    ("काम", "kaama", 0.80, ["kam", "kaam"]),
    ("साथ", "saatha", 0.62, ["sath", "saath"]),
    ("देश", "desha", 0.82, ["desh", "deshh"]),
    ("हिमाल", "himaala", 0.46, ["himal"]),
    ("तराई", "taraai", 0.12, ["tarai"]),
    ("सहर", "sahara", 0.40, ["sahar", "shahar"]),
    ("गाउँ", "gaaum", 0.48, ["gau", "gaun", "gaum"]),

    # ---------------- food, drink ----------------
    ("पानी", "paani", 0.88, ["pani", "panni"]),
    ("खाना", "khaanaa", 0.90, ["khana", "khanna"]),
    ("भात", "bhaata", 0.66, ["bhat"]),
    ("दाल", "daala", 0.58, ["dal"]),
    ("तरकारी", "tarkaari", 0.48, ["tarkari"]),
    ("मासु", "maasu", 0.52, ["masu"]),
    ("दुध", "dudha", 0.50, ["dudh", "doodh"]),
    ("चिया", "chiyaa", 0.62, ["chiya", "chai"]),
    ("कफी", "kaphi", 0.30, ["kafi", "coffee"]),
    ("पैसा", "paisaa", 0.56, ["paisa"]),
    ("रुपैयाँ", "rupaiyaam", 0.42, ["rupiya", "rupaya"]),
    ("मिठाई", "mithaai", 0.36, ["mithai"]),
    ("रोटी", "roti", 0.44, []),

    # ---------------- time ----------------
    ("आज", "aaja", 0.92, ["aja", "aaj"]),
    ("भोलि", "bholi", 0.62, ["bholee"]),
    ("अस्ति", "asti", 0.38, []),
    ("साँझ", "saaumjha", 0.34, ["sanjha", "sajha"]),
    ("बेलुका", "belukaa", 0.26, ["beluka"]),
    ("दिन", "dina", 0.76, ["din"]),
    ("रात", "raata", 0.68, ["rat", "raat"]),
    ("महिना", "mahinaa", 0.44, ["mahina"]),
    ("समय", "samaya", 0.50, ["samay"]),
    ("बर्ष", "barsa", 0.28, ["baras"]),
    ("हप्ता", "haptaa", 0.32, ["hapta"]),

    # ---------------- quantity, degree ----------------
    ("धेरै", "dherai", 0.86, ["dherei"]),
    ("थोरै", "thorai", 0.62, ["thorei"]),
    ("निकै", "nikai", 0.44, []),
    ("अलि", "ali", 0.32, ["alee"]),
    ("धेरैजसो", "dheraijaso", 0.14, []),
    ("एकदम", "ekadama", 0.26, ["ekdam"]),

    # ---------------- function words ----------------
    ("र", "ra", 0.94, []),
    ("पनि", "pani", 0.88, ["panni"]),
    ("तर", "tara", 0.80, ["tarra"]),
    ("भने", "bhane", 0.72, []),
    ("त", "ta", 0.58, []),
    ("म", "ma", 0.76, []),
    ("नि", "ni", 0.26, []),
    ("सम्म", "samma", 0.30, []),
    ("पछा", "pachhaa", 0.36, ["pachha", "pachhi"]),
    ("माथि", "maathi", 0.48, ["mathi"]),
    ("तला", "talaa", 0.34, ["tala"]),
    ("बिच", "bicha", 0.28, ["bich"]),
    ("बाहिर", "baahira", 0.26, ["bahira"]),
    ("भित्र", "bhitra", 0.30, ["bhittra"]),
    ("सुरु", "suru", 0.40, ["suruu"]),
    ("अन्त", "anta", 0.18, []),
    ("अनि", "ani", 0.52, ["anee"]),

    # ---------------- verbs ----------------
    ("गर्ने", "garne", 0.74, ["garna", "garnu"]),
    ("गर्न", "garna", 0.50, ["garnaa"]),
    ("गरे", "gare", 0.44, []),
    ("गर", "gara", 0.40, []),
    ("खाने", "khaane", 0.38, ["khane"]),
    ("जाने", "jaane", 0.42, ["jane"]),
    ("आउ", "aau", 0.30, ["au"]),
    ("आउनु", "aaunu", 0.22, ["aunu"]),
    ("हुन", "huna", 0.36, ["hunaa"]),
    ("भन", "bhana", 0.34, []),
    ("देख", "dekha", 0.32, ["dekh"]),
    ("मिल", "mila", 0.14, ["milan"]),
    ("पढाइ", "padhaai", 0.30, ["padhai", "paddhai"]),
    ("सक्ने", "sakne", 0.26, ["sakna"]),
    ("दिने", "dine", 0.24, ["dina"]),
    ("लिने", "line", 0.22, ["lina"]),
    ("सुने", "sune", 0.18, ["suna"]),

    # ---------------- adjectives ----------------
    ("नराम्रो", "naraamro", 0.44, ["naramro"]),
    ("सानो", "saano", 0.56, ["sano"]),
    ("ठुला", "thulaa", 0.50, ["thula", "thulo"]),
    ("नया", "nayaa", 0.42, ["naya"]),
    ("पुरानो", "puraano", 0.34, ["purano"]),
    ("नै", "nai", 0.40, []),
    ("सुन्दर", "sundara", 0.32, ["sundar"]),
    ("मुख", "mukha", 0.20, []),
    ("असल", "asala", 0.18, ["asal"]),

    # ---------------- numbers ----------------
    ("एक", "eka", 0.62, ["ek"]),
    ("दुई", "dui", 0.58, ["doo"]),
    ("तिन", "tina", 0.48, ["tin"]),
    ("चार", "chaara", 0.42, ["char", "chaar"]),
    ("पाँच", "paaumcha", 0.40, ["panch", "paanch"]),
    ("सात", "saata", 0.28, ["sat", "saat"]),
    ("आठ", "aatha", 0.26, ["ath", "aath"]),
    ("नौ", "nau", 0.24, []),
    ("दस", "dasa", 0.34, ["das"]),
    ("सय", "saya", 0.22, ["sau"]),
    ("हजार", "hajaara", 0.20, ["hajar", "hajaar"]),
    ("लाख", "laakha", 0.12, ["lakh", "laakh"]),

    # ---------------- modern, technology ----------------
    ("फोन", "phona", 0.56, ["phone", "fone"]),
    ("मोबाइल", "mobaaila", 0.52, ["mobile", "mobail"]),
    ("कम्प्युटर", "kamyutara", 0.40, ["computer", "computar"]),
    ("इन्टरनेट", "intaraneta", 0.34, ["internet"]),
    ("फेसबुक", "phesabuka", 0.30, ["facebook", "fb"]),
    ("इमेल", "imela", 0.42, ["email", "imel"]),
    ("मेसेज", "meseja", 0.38, ["message"]),
    ("भिडियो", "bhiddiyo", 0.28, ["video", "bhidiyo"]),
    ("गित", "giita", 0.44, ["git", "geet"]),
    ("मुबी", "mubi", 0.26, ["movie"]),
    ("खेल", "khela", 0.36, ["khel"]),
    ("फोटो", "photo", 0.34, ["photto"]),
    ("गेम", "gema", 0.18, ["game"]),
    ("अनलाइन", "analaaina", 0.24, ["online"]),
    ("लिंक", "linka", 0.22, ["link"]),
]


def build_entries():
    """Collapse duplicate Devanagari forms, merging aliases and frequencies."""
    by_dev = {}
    order = []
    for dev, roman, freq, aliases in WORDS:
        if dev in by_dev:
            existing = by_dev[dev]
            for a in list(aliases) + [roman]:
                if a and a != existing[1] and a not in existing[3]:
                    existing[3].append(a)
            if freq > existing[2]:
                existing[2] = freq
        else:
            by_dev[dev] = [dev, roman, freq, list(aliases)]
            order.append(dev)

    entries = []
    for dev in order:
        _dev, roman, freq, aliases = by_dev[dev]
        uniq = sorted({a for a in aliases if a and a.lower() != roman.lower()})
        entries.append({
            "d": dev,
            "r": roman.lower(),
            "f": round(freq, 4),
            "a": uniq,
            "c": "n",
        })
    entries.sort(key=lambda e: (-e["f"], e["r"]))
    return entries


# ---------------------------------------------------------------------------
# Bigrams, written in Romanized form for readability and resolved against the
# vocabulary. "" as the first element means "commonly sentence-initial".
# ---------------------------------------------------------------------------
BIGRAM_PAIRS = [
    ("", "namaste", 0.62), ("", "dhanyabaad", 0.24), ("", "kaathmaadaum", 0.10),
    ("", "mero", 0.14), ("", "ma", 0.18), ("", "ke", 0.12), ("", "yo", 0.10),
    ("namaste", "dhanyabaad", 0.30), ("namaste", "hunchha", 0.14),
    ("dhanyabaad", "namaste", 0.28), ("dhanyabaad", "hunchha", 0.18),
    ("ma", "raamro", 0.22), ("ma", "khaanaa", 0.16), ("ma", "jaane", 0.12),
    ("ma", "garna", 0.14), ("ma", "kaathmaadaum", 0.10), ("ma", "thika", 0.12),
    ("mero", "naama", 0.30), ("mero", "ghara", 0.22), ("mero", "desha", 0.20),
    ("mero", "saathi", 0.14), ("mero", "kaama", 0.10),
    ("timro", "naama", 0.40), ("timro", "ghara", 0.18), ("timro", "kaama", 0.12),
    ("haamro", "desha", 0.36), ("haamro", "nepaal", 0.22), ("haamro", "ghara", 0.14),
    ("nepaal", "raamro", 0.20), ("nepaal", "saano", 0.14), ("nepaal", "himaala", 0.12),
    ("kaathmaadaum", "raamro", 0.16), ("kaathmaadaum", "jaane", 0.12),
    ("paani", "khaanaa", 0.18), ("paani", "chiyaa", 0.10),
    ("khaanaa", "khaane", 0.20), ("khaanaa", "bhaata", 0.14),
    ("ghara", "jaane", 0.18), ("ghara", "bhayo", 0.12),
    ("kina", "bhayo", 0.26), ("kina", "garna", 0.16),
    ("ke", "bhayo", 0.30), ("ke", "garna", 0.20), ("ke", "hunchha", 0.18),
    ("raamro", "chha", 0.24), ("raamro", "bhayo", 0.14), ("raamro", "maanche", 0.12),
    ("sanchai", "chha", 0.28), ("sanchai", "hunchha", 0.12),
    ("dherai", "raamro", 0.22), ("dherai", "dina", 0.10), ("dherai", "paani", 0.08),
    ("aaja", "bhayo", 0.16), ("aaja", "jaane", 0.14), ("aaja", "khaanaa", 0.10),
    ("bholi", "jaane", 0.18), ("bholi", "bhayo", 0.10),
    ("desha", "raamro", 0.18), ("desha", "maathi", 0.10),
    ("saathi", "sangai", 0.16), ("saathi", "raamro", 0.14),
    ("kaama", "bhayo", 0.14), ("kaama", "garna", 0.16),
    ("thika", "chha", 0.34), ("thika", "hunchha", 0.16),
    ("hunchha", "ki", 0.12), ("chha", "ki", 0.16),
    ("paisaa", "chha", 0.14), ("paisaa", "dine", 0.10),
    ("hoina", "hunchha", 0.14), ("ho", "ki", 0.14),
    ("bhaata", "khaane", 0.16), ("chiyaa", "khaane", 0.14),
    ("naama", "ke", 0.30), ("naama", "ho", 0.12),
    ("garna", "sakne", 0.18), ("jaane", "bhayo", 0.12),
    ("dina", "raata", 0.14), ("suru", "garna", 0.12),
]


def main():
    entries = build_entries()

    roman_to_dev = {}
    for e in entries:
        roman_to_dev[e["r"]] = e["d"]
        for a in e["a"]:
            roman_to_dev.setdefault(a.lower(), e["d"])

    bigrams = []
    seen = set()
    unresolved = []
    for a, b, f in BIGRAM_PAIRS:
        da = "" if a == "" else roman_to_dev.get(a.lower())
        db = roman_to_dev.get(b.lower())
        if a != "" and da is None:
            unresolved.append(a)
            continue
        if db is None:
            unresolved.append(b)
            continue
        key = (da, db)
        if key in seen:
            continue
        seen.add(key)
        bigrams.append({"a": da, "b": db, "f": round(f, 4)})

    asset = {
        "metadata": {
            "version": 1,
            "locale": "ne",
            "wordCount": len(entries),
            "source": "Hand-curated modern Nepali conversational vocabulary",
            "license": "CC0-1.0",
        },
        "words": entries,
        "bigrams": bigrams,
    }

    out_dir = os.path.join("app", "src", "main", "assets", "dict")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "ne_lexicon.json")
    with open(out_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(asset, fh, ensure_ascii=False, indent=1)
        fh.write("\n")

    print(f"wrote {out_path}")
    print(f"  words:      {len(entries)}")
    print(f"  bigrams:    {len(bigrams)}")
    print(f"  bytes:      {os.path.getsize(out_path)}")
    if unresolved:
        print(f"  UNRESOLVED bigram endpoints: {sorted(set(unresolved))}")


if __name__ == "__main__":
    main()
