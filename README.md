# Nepali Keyboard

An offline Nepali (Devanagari) keyboard for Android, built with Kotlin and
Jetpack Compose. Romanized typing is the primary input path: type `namaste` and
get नमस्ते, `dhanyabad` → धन्यवाद, `nepal` → नेपाल, `mero` → मेरो.

Everything runs on the device. The app declares **no permissions at all** — no
internet, no contacts, no storage — so the keyboard is structurally incapable of
sending what you type anywhere, and there is no account, no sync and no
telemetry.

## Features

**Three input modes**

1. **Romanized Nepali** (default) — deterministic phonetic transliteration with
   vowel signs, conjuncts (`kshya` → क्ष्य, `tra` → त्र, `gya` → ज्ञ), schwa
   deletion, and an ambiguous-mapping resolver. What you type is shown live, and
   the suggestion strip offers the literal roman input, the transliteration, and
   dictionary/statistical alternatives.
2. **Devanagari direct** — a native layout with vowels, consonants, matras, the
   halant, chandrabindu, anusvara, visarga, nukta and Devanagari numerals, with a
   long-press extras page.
3. **English QWERTY** — shift/caps lock, symbol pages, and per-editor layouts
   (email, URI, number, phone).

**Keyboard behaviour**

* Grapheme-cluster-safe Backspace: matras, halant, chandrabindu, anusvara, nukta
  and emoji sequences are never split.
* Leftward swipe from Backspace deletes whole words, progressively.
* Space-bar horizontal drag moves the caret character by character.
* Long-press for alternate characters, matras and nukta forms.
* Auto-capitalization, optional auto-correct, adjustable keyboard height,
  one-handed mode pinned left or right with a side switch.
* Haptics through `HapticFeedbackConstants` (no `VIBRATE` permission) and
  keypress audio, both configurable.
* Searchable emoji picker with categories and recents, and an in-keyboard
  clipboard history with single-tap paste, pinning and clear-all.

**Privacy**

* Password and no-personalization fields get literal input only: no composing
  text, no suggestions, no learning, no clipboard capture, no emoji recents.
* Clipboard history is observed only while the keyboard is visible and never
  while a sensitive field has focus.
* Personal-word learning is capped (4 000 words, 3 000 bigram heads) and stored
  locally in DataStore; the settings app can show and clear it.

## Install

1. Download `nepali-keyboard-<short-sha>.apk` from the Actions run.
2. Side-load it (`adb install -r nepali-keyboard-<sha>.apk`).
3. Open **Nepali Keyboard** and follow the onboarding: enable the keyboard, then
   select it as the current input method.
4. Type anywhere: the first chip in the suggestion strip is the romanized input,
   second is the transliteration.

Works from Android 8.0 (API 26) up; targets API 35.

## Build

```
git clone <this repository>
cd nepali-keyboard
./gradlew assembleRelease          # JDK 17+; CI uses JDK 21
```

The output is one universal APK in `app/build/outputs/apk/release/`.

Release signing uses `keystore/release.keystore` with credentials written inline
in `app/build.gradle.kts`. That is intentional: it is a committed demo key so CI
builds succeed without repository secrets, and it keeps side-loaded builds
upgradeable. **Do not ship a store release with it** — replace it with your own
key and move the credentials out of the build script.

## Verification

```
python3 tools/verify_transliteration.py --coverage
```

This is the repository's executable check: it reads the rule tables out of
`engine/PhoneticRules.kt`, re-implements the `PhoneticEngine` loop, and asserts
both layers - the pure literal transliteration and the exact-dictionary-key rule
that the space bar commits. `--coverage` additionally reports how many of the
bundled words each layer reproduces (currently 249/680 literal, 680/680 after the
exact-key rule). Compilation, lint and the release APK are verified by
`.github/workflows/build-release.yml`.

## Repository layout

```
app/src/main/java/np/com/nepalikeyboard/
  ime/          input method service, Compose owner bridge, controller, composition
  keyboard/     key models, layouts, key grid, gesture machine, host, panels
  engine/       phonetic rules/engine, compact radix trie, lexicon, bigram model
  data/         DataStore repositories and serializable models
  settings/     settings activity, onboarding, sandbox, screens
  ui/theme/     Material 3 Expressive tokens, typography, colour schemes
app/src/main/assets/lexicon/nepali_lexicon.json   generated dictionary
tools/generate_lexicon.py                          dictionary generator + validator
```

`AGENTS.md` documents the invariants (composing text, graphemes,
`InputConnection` discipline, main-thread allocation rules) and how to extend the
transliteration rules or regenerate the lexicon.

## Dictionary

The bundled lexicon carries ~680 curated words with relative frequencies and ~220
curated bigrams, ranked by

```
score = log(frequency + 1) * 1000 + bigram bonus + personal boost + exact-key bonus
```

To regenerate it after editing the curated tables:

```
python3 tools/generate_lexicon.py
```

The generator validates roman-key uniqueness, duplicate spellings, bigram
membership and asset size, and refuses to write an asset that the loader would
silently degrade.

## License

Application code is provided as-is for use and modification. Bundled typefaces
keep their own licences: Poppins (SIL Open Font License 1.1) and Noto Sans
Devanagari (SIL Open Font License 1.1); both are distributed in `res/font/`.
