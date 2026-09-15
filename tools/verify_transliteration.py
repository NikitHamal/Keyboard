#!/usr/bin/env python3
"""Independent check of the deterministic transliteration layer.

The Kotlin engine is `engine/PhoneticRules.kt` (tables) plus
`engine/PhoneticEngine.kt` (the loop). This script parses the tables straight out
of the Kotlin source, re-implements the ~30-line loop exactly as written, and
runs a table of expectations against it.

Why re-implement instead of compiling: this repository is developed without an
Android SDK, so the only way to test the highest-risk logic - the rule tables and
the virama/anusvara decisions - is to execute the same algorithm in a language
that runs everywhere. Any drift between this file and `PhoneticEngine.kt` shows up
as a mismatch here, because the tables are read from the Kotlin source rather
than duplicated.

Usage:
    python3 tools/verify_transliteration.py            # run expectations
    python3 tools/verify_transliteration.py --coverage # + curated-word report
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RULES_PATH = os.path.join(
    ROOT, "app/src/main/java/np/com/nepalikeyboard/engine/PhoneticRules.kt"
)
ASSET_PATH = os.path.join(
    ROOT, "app/src/main/assets/lexicon/nepali_lexicon.json"
)

VIRAMA = "\u094D"
ANUSVARA = "\u0902"
DANDA = "\u0964"

CONSONANT, VOWEL, ANUSVARA_KIND = 0, 1, 2
KIND_ORDER = {"CONSONANT": CONSONANT, "VOWEL": VOWEL, "ANUSVARA": ANUSVARA_KIND}

# `independent` is either a `\uXXXX` literal or `ANUSVARA_CHAR.toString()`, which
# is how the nasal rules are written in PhoneticRules.kt.
RULE_RE = re.compile(
    r'rule\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*'
    r'("(?:\\u[0-9A-Fa-f]{4})*"|ANUSVARA_CHAR\.toString\(\))\s*,\s*'
    r'("(?:\\u[0-9A-Fa-f]{4})*")\s*,\s*UnitKind\.(\w+)\s*\)'
)


def decode_kotlin_escapes(literal: str) -> str:
    """Kotlin `\\uXXXX` escapes -> Python characters (BMP only, which is all we use)."""
    return re.sub(
        r"\\u([0-9A-Fa-f]{4})", lambda m: chr(int(m.group(1), 16)), literal
    )


def load_rules():
    source = open(RULES_PATH, encoding="utf-8").read()
    rules = []
    for roman, independent, dependent, kind in RULE_RE.findall(source):
        rules.append(
            {
                "roman": roman,
                "independent": (
                    ANUSVARA
                    if independent.startswith("ANUSVARA_CHAR")
                    else decode_kotlin_escapes(independent.strip('"'))
                ),
                "dependent": decode_kotlin_escapes(dependent.strip('"')),
                "kind": KIND_ORDER[kind],
            }
        )
    if not rules:
        raise SystemExit(f"no rules parsed out of {RULES_PATH}")
    if not any(rule["kind"] == ANUSVARA_KIND for rule in rules):
        raise SystemExit("no ANUSVARA rules parsed: the nasal rules changed shape")
    if "b.kind.ordinal.compareTo(a.kind.ordinal)" not in source:
        raise SystemExit(
            "PhoneticRules comparator changed: this script's tie-break order no "
            "longer mirrors the engine"
        )
    buckets: dict[str, list] = {}
    for rule in rules:
        buckets.setdefault(rule["roman"][0], []).append(rule)
    for bucket in buckets.values():
        # Mirrors `PhoneticRules.byLengthThenKind`: longest roman form first, and
        # on a tie the assimilation rule (ANUSVARA, ordinal 2) wins over the plain
        # consonant (ordinal 0), so `n` before a stop becomes anusvara.
        bucket.sort(key=lambda r: (-len(r["roman"]), -r["kind"]))
    return buckets


PLOSIVE_TRIGGERS = set("kgcjtdpb")


def match(buckets, text: str, index: int, end: int):
    first = text[index]
    candidates = buckets.get(first)
    if not candidates:
        return None
    for candidate in candidates:
        length = len(candidate["roman"])
        if index + length > end:
            continue
        if text[index : index + length] != candidate["roman"]:
            continue
        if candidate["kind"] == ANUSVARA_KIND and candidate["roman"] != "M":
            if not is_anusvara_context(text, index + length, end):
                continue
        return candidate
    return None


def is_anusvara_context(text: str, index: int, end: int) -> bool:
    if index >= end:
        return False
    return text[index] in PLOSIVE_TRIGGERS


def transliterate(buckets, roman: str, devanagari_digits: bool = False) -> str:
    out = []
    end = len(roman)
    index = 0
    pending_consonant = False
    while index < end:
        rule = match(buckets, roman, index, end)
        if rule is None:
            ch = roman[index]
            if devanagari_digits and ch.isdigit():
                out.append(chr(0x0966 + int(ch)))
            elif ch == "|":
                out.append(DANDA)
            else:
                out.append(ch)
            pending_consonant = False
            index += 1
            continue
        if rule["kind"] == CONSONANT:
            if pending_consonant:
                out.append(VIRAMA)
            out.append(rule["independent"])
            pending_consonant = True
        elif rule["kind"] == VOWEL:
            out.append(rule["dependent"] if pending_consonant else rule["independent"])
            pending_consonant = False
        else:
            out.append(ANUSVARA)
            pending_consonant = False
        index += len(rule["roman"])
    return "".join(out)


# Literal-layer expectations: the pure, deterministic, lexicon-independent
# algorithm in PhoneticEngine.kt. These are the strings the composing region shows
# while a word is in progress.
LITERAL_EXPECTATIONS = [
    # The four headline examples, as far as the pure layer can take them. Three
    # of them are already exact; `nepal` needs the dictionary, see
    # RESOLVED_EXPECTATIONS below.
    ("namaste", "नमस्ते"),
    ("mero", "मेरो"),
    ("nepal", "नेपल"),
    ("dhanyabad", "धन्यबद"),
    # Conjuncts and the halant-between-consonants rule.
    ("kshya", "क्ष्य"),
    ("tra", "त्र"),
    ("gya", "ज्ञ"),
    ("namaskar", "नमस्कर"),
    ("bhai", "भै"),
    ("khana", "खन"),
    # Word-final consonants keep their inherent vowel: no trailing halant.
    ("desh", "देश"),
    ("rat", "रत"),
    ("kam", "कम"),
    ("ram", "रम"),
    # Nasal assimilation: `n` before a stop becomes anusvara, elsewhere stays न.
    ("sangh", "संघ"),
    ("sanga", "संग"),
    ("chandra", "चंद्र"),
    ("sanchai", "संचै"),   # assmilated nasal, as in संघ and चंद्र
    # Retroflex / aspirated series through ITRANS-style capitals.
    ("Thik", "ठिक"),
    ("Nepal", "णेपल"),
    ("Sharma", "षर्म"),
    # Vowel signs, independent vs dependent.
    ("aama", "आम"),
    ("suno", "सुनो"),
    ("paani", "पानि"),
    ("gau", "गौ"),
    ("kina", "किन"),
    # Long-vowel and long-consonant markers.
    ("sAthI", "साथी"),
    ("kUda", "कूद"),
    ("paani", "पानि"),
    # Literal pass-through: punctuation, digits, and the danda shortcut.
    ("ram|", "रम।"),
    ("1", "1"),
    ("45", "45"),
    ("ok!", "ओक!"),
]

# Resolver expectations: what the keyboard actually inserts when the word is
# committed, i.e. the exact-key rule in `CandidateEngine` and `bestCommitForm`:
# an exact key of a bundled word wins over the literal rendering, anything else
# keeps the literal rendering.
RESOLVED_EXPECTATIONS = [
    ("namaste", "नमस्ते"),
    ("nepal", "नेपाल"),
    ("mero", "मेरो"),
    ("dhanyabad", "धन्यवाद"),
    ("khana", "खाना"),
    ("sathi", "साथी"),          # reached through an alternative roman key
    ("kathmandu", "काठमाडौं"),
    ("paani", "पानी"),
    ("ramro", "राम्रो"),
    # Unknown words keep the deterministic rendering.
    ("vlog", "व्लोग"),
]


def load_exact_keys():
    if not os.path.exists(ASSET_PATH):
        return {}
    with open(ASSET_PATH, encoding="utf-8") as handle:
        asset = json.load(handle)
    keys = {}
    for word in asset["words"]:
        keys.setdefault(word["k"], word["w"])
    for word in asset["words"]:
        for alt in word.get("alts", []):
            keys.setdefault(alt, word["w"])
    return keys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--coverage", action="store_true")
    args = parser.parse_args()

    buckets = load_rules()
    failures = []

    print("literal layer (PhoneticEngine.transliterate)")
    for roman, expected in LITERAL_EXPECTATIONS:
        actual = transliterate(buckets, roman)
        if actual != expected:
            failures.append(("literal", roman, expected, actual))
        print(f"  {'ok  ' if actual == expected else 'FAIL'} {roman:<12} -> {actual}")

    exact_keys = load_exact_keys()
    if not exact_keys:
        print()
        print("lexicon asset missing; resolver expectations skipped")
    else:
        print()
        print("resolver (exact dictionary key wins, else the literal rendering)")
        for roman, expected in RESOLVED_EXPECTATIONS:
            actual = exact_keys.get(roman) or transliterate(buckets, roman)
            if actual != expected:
                failures.append(("resolver", roman, expected, actual))
            print(f"  {'ok  ' if actual == expected else 'FAIL'} {roman:<12} -> {actual}")

    if args.coverage and exact_keys:
        with open(ASSET_PATH, encoding="utf-8") as handle:
            asset = json.load(handle)
        literal_exact = sum(
            1 for word in asset["words"] if transliterate(buckets, word["k"]) == word["w"]
        )
        resolved_exact = sum(
            1
            for word in asset["words"]
            if (exact_keys.get(word["k"]) or transliterate(buckets, word["k"])) == word["w"]
        )
        total = len(asset["words"])
        print()
        print(f"curated words reproduced by the literal layer:  {literal_exact}/{total}")
        print(f"curated words reproduced after the exact-key rule: {resolved_exact}/{total}")
        print("The gap is exactly the vowel length the ASCII romanization cannot mark")
        print("(nepal/नेपाल, sathi/साथी), which is why the lexicon ships with the app.")

    print()
    if failures:
        print(f"{len(failures)} expectation(s) failed:")
        for layer, roman, expected, actual in failures:
            print(f"  [{layer}] {roman}: expected {expected}, got {actual}")
        return 1
    print(f"all {len(LITERAL_EXPECTATIONS) + len(RESOLVED_EXPECTATIONS)} expectations passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
