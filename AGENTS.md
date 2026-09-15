# AGENTS.md

Operating manual for this repository. Everything below is a rule you can check in
code, not a preference.

---

## 1. What this app is

A single-module Android app (`:app`) in Kotlin + Jetpack Compose that ships two
things from one process:

| Surface | Package | Entry point |
| --- | --- | --- |
| Input method (keyboard) | `np.com.nepalikeyboard.ime`, `...keyboard` | `NepaliImeService` (declared in `AndroidManifest.xml`) |
| Settings application | `np.com.nepalikeyboard.settings` | `SettingsActivity` (launcher) |

Supporting packages: `data` (DataStore repositories + models), `engine`
(transliteration and the offline statistical model), `util`, `ui.theme`.

Hard constraints that define the product:

* **No network permission.** `AndroidManifest.xml` declares no `uses-permission`
  elements and no `uses-feature` requirements beyond the IME binding. Any change
  that adds a permission requires a design decision, not a commit.
* **No downloadable fonts.** Everything is bundled in `res/font/` and referenced
  by explicit Compose `FontFamily` definitions in `ui/theme/Type.kt`. The Material
  dynamic-color download path and `GoogleFont.Provider` are never used.
* **Offline lexicon.** The dictionary is a JSON asset parsed once per process.
  There is no cache to invalidate and no service to call.

---

## 2. Architecture and MVI boundaries

```
              pointer dispatch (main thread)
                         │
   KeyboardSurface ──► ImeKeySink / KeyboardActionSink
                         │
                  KeyboardController  ──►  ImeUiState (immutable, single StateFlow)
                         │                        │
                  CompositionController           ▼
                         │                   KeyboardHost (Compose)
                         ▼
                    InputConnection
```

* **Model** — `ImeUiState` (`ime/ImeContract.kt`), `SettingsSnapshot`,
  `ClipboardEntry`, `PersonalWord`, `Candidate`. All are `@Immutable` data
  classes / value classes. Models never hold a `Context`, a `View` or a lambda
  that captures one.
* **View/Intent** — `KeyboardSurface` + `KeyboardHost` + the panels. They render
  `ImeUiState` and report user intent through `KeyboardActionSink`. There is no
  other writer of UI state.
* **Reducer** — `KeyboardController` is the only place that mutates the model.
  Every mutation path ends in `publish()`, which builds one snapshot and emits it
  only if it differs from the current one (`if (next != current) _state.value = next`).
  Feedback loops are therefore structurally impossible: a recomposition cannot
  cause a state emission.

### File responsibilities

| File | Owns |
| --- | --- |
| `ime/NepaliImeService.kt` | `InputMethodService`, window lifecycle, `EditorInfo` digestion, hardware keys, Compose owner wiring |
| `ime/ComposeOwnerBridge.kt` | `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner` / `ViewModelStoreOwner` for the IME window (an IME is **not** an `Activity`, so Compose cannot find them otherwise) |
| `ime/KeyboardController.kt` | The reducer: keys, panels, candidates, settings, learning |
| `ime/CompositionController.kt` | The **only** code that reads or writes `InputConnection` |
| `ime/EditorModel.kt` | `EditorInfo` → `EditorDescriptor` (kind, action, flags, privacy) |
| `ime/ImeRuntime.kt` | Process-wide singletons: settings/clipboard/emoji/learning repositories and the lazily parsed lexicon |
| `keyboard/KeyboardSurface.kt` | Key grid, measurement, pointer state machine |
| `keyboard/KeyboardHost.kt` | Vertical composition: strip → toolbar → panel or keys |
| `keyboard/KeyDef.kt`, `Layouts.kt` | Key models, layout catalog, long-press tables |
| `engine/*` | Transliteration, radix-trie lexicon, bigram model, candidate ranking |
| `data/*` | DataStore-backed settings, learning, clipboard history, emoji recents |

---

## 3. Composing-text invariants

The romanized keyboard keeps an uncommitted region ("composing text") alive while
a word is being typed. Break any rule below and words get corrupted.

1. **One owner.** `CompositionController` performs every `setComposingText`,
   `finishComposingText`, `setSelection`, `getTextBeforeCursor` and
   `commitText`. Nothing else in the codebase may touch `InputConnection`.
2. **The editor never sees a half-word.** Inserting or deleting a keystroke
   re-renders the *whole* composed word with `setComposingText(wholeWord, 1)`.
   Editing two characters is never two commits.
3. **Commit geometry is fixed.** `commitComposition()` reads the word that is
   currently on screen (`previewTransliteration`), finishes the composition and
   commits exactly that string. What the user sees is what lands in the document.
4. **Switching scripts, panels, editors or input views commits or drops first.**
   `setMode`, `onInputViewHidden`, `editorChanged` and `editorFinished` all funnel
   through `composer.reset()` / `committing` helpers. A composition is never
   migrated to another field.
5. **External invalidation wins.** `onUpdateSelection` reports the app's own
   composing region. If that region no longer contains our buffer, the buffer is
   dropped (`CompositionController.reconcileSelection`) so a later Backspace
   cannot delete characters the keyboard did not insert.
6. **Backspace inside a composition edits the buffer, not the text.**
   `backspaceRoman()` deletes a roman key and re-renders the word.
7. **Sensitive fields are literal.** For `EditorInfo.TYPE_TEXT_VARIATION_PASSWORD`,
   `..._VISIBLE_PASSWORD`, `..._WEB_PASSWORD`, `..._PASSWORD` and
   `IME_FLAG_NO_PERSONALIZED_LEARNING`, `EditorDescriptor.sensitive` is true, and
   that single flag switches off: composing text, suggestions, auto-correct,
   learning, clipboard capture and emoji recents. Do not add a second code path.

---

## 4. Grapheme-cluster safety

Devanagari has no 1:1 character/grapheme mapping: a matra, halant, chandrabindu,
anusvara or nukta attaches to the preceding consonant, and emoji can be surrogate
pairs with ZWJ sequences. Rules:

* Deletion always goes through `engine/unicode/Graphemes.kt`
  (`prevBoundary`, `nextBoundary`, `prevWordBoundary`), never `length - 1`.
* `CompositionController.deleteCluster` reads a window with
  `getTextBeforeCursor`, computes the *cluster* length, and deletes that many
  characters with `deleteSurroundingText` (with a composing-text-aware fallback).
* Word deletion (`deleteWords`) extends `prevWordBoundary` until whitespace, so a
  whole word disappears in one gesture.
* Caret movement via the space bar uses `setSelection` on absolute offsets only;
  it never re-reads or re-writes the composed word.

If you add a delete or move path, route it through `Graphemes` and add a case to
`CompositionController`, not to a UI file.

---

## 5. Main-thread allocation and recomposition rules

The keystroke path runs on the main thread inside pointer dispatch. It must stay
allocation-free and must not force recomposition of anything that did not change.

* Pointer dispatch (`KeyboardSurface.keyboardGestures`) keeps all state in
  locals; it never builds an `Offset`, `Rect` or list, and indexes
  `event.changes` with a counted `for` loop instead of `firstOrNull { }`.
* `KeyDef`, `KeyboardLayout`, `FeedbackConfig`, `ImeUiState` and the panel models
  are `@Immutable`/`@Stable` and are built once per (layout, shift, settings)
  change by `LayoutCatalog.resolve`, which memoises.
* Long-lived state is hoisted: the gesture holder, the `KeyboardGeometry`
  instance, `KeyboardFeedback` and the suggestion/panel models are created with
  `remember`/`derivedStateOf`, never inside a lambda that runs per frame.
* `CandidateEngine` runs on `Dispatchers.Default` and delivers results on the
  main thread through a conflated channel. The keystroke path only
  `trySend`s; it never joins, never blocks and never allocates a list.
* Anything coroutine-based that the keystroke path can trigger (`DataStore`
  writes, learning flushes, clipboard observation) is dispatched from the
  controller's scope, never inline.
* New Compose state must be derivable from `ImeUiState`. If you need a value that
  changes faster than a keypress, it belongs in the pointer layer as a plain
  `MutableState`, not in the model.

Budget: a keypress should add no more than one small object (the new
`ImeUiState` snapshot) and one `StateFlow` comparison.

---

## 6. Extending the transliteration rules

`engine/PhoneticRules.kt` is a table-driven longest-match parser; the mapping is
data, the parser is code. `tools/verify_transliteration.py` parses those tables
out of the Kotlin source, re-implements the loop from `PhoneticEngine.kt`, and
runs expectations against the result - it is the only executable check in this
repository, and it must stay green:

```
python3 tools/verify_transliteration.py --coverage
```

Rules that must not be broken:

1. **Longest match wins**, and on a tie the assimilation rule (ANUSVARA) is tried
   before the plain consonant. That ordering is what turns `n` before a stop into
   anusvara (`sangh` → संघ, `chandra` → चंद्र) while leaving `nepal` as नेपल from
   the literal layer.
2. **A halant only ever goes between two consonants.** Word-final consonants keep
   their inherent vowel, which is why the literal layer produces नेपल and not
   नेपल्.
3. **The literal layer is lexicon-independent and pure.** It must keep working
   before the asset finishes parsing. It cannot recover vowel length the ASCII
   key dropped (`nepal` → नेपल), and it is not supposed to; that gap is the
   lexicon's job.
4. **The exact-key rule closes the gap.** `CandidateEngine.handleRoman` pins
   `Lexicon.entryForRoman(buffer)` as the transliteration candidate, and
   `KeyboardController.bestCommitForm` commits that same word, so what the strip
   shows and what the space bar inserts are always the same string. With
   `--coverage` the script reports how many curated words the literal layer
   reproduces on its own and how many are reproduced after the exact-key rule;
   the second number must stay at 100%.
5. **Capitals are meaningful** (ITRANS-style `T` `D` `N` `S` for the retroflex and
   sibilant series). Auto-capitalization feeds lowercase into the buffer so a
   sentence that starts with "Namaste" still renders नमस्ते.

## 7. Updating the bundled lexicon

`app/src/main/assets/lexicon/nepali_lexicon.json` is generated, never hand-edited.

```
python3 tools/generate_lexicon.py            # writes the asset, prints counts
python3 tools/generate_lexicon.py --check    # validates, writes nothing
```

* `WORDS` entries are `devanagari|roman|frequency[|alt1,alt2]`; frequencies are
  relative (curated bands, 90 000 for function words down to a few hundred for
  rare nouns). They are *relative*, not corpus counts: only the ratios matter to
  the ranker.
* `BIGRAMS` entries are `first|second|frequency`; both words must exist in
  `WORDS`.
* Validation is part of the generator: duplicate Devanagari spellings, roman keys
  used by two different words, bigram words missing from `WORDS`, and
  multi-word roman keys (which `Lexicon.build` would silently drop) are all
  hard errors.
* Keys must match `[a-z']` and be at most `Lexicon.MAX_KEY_LENGTH` (16)
  characters. Anything else is dropped at load time with a log line.
* After regenerating, bump `version` in the emitted JSON (the generator does it
  from the file's git hash) and keep the asset under ~1 MB: it is parsed on a
  background thread at process start and must stay a few milliseconds of work.
* Frequencies feed `CandidateEngine`: score = `log(f + 1) * 1000` + bigram bonus
  + personal boost + exact-key bonus. Change the bands, not the formula, unless
  you also re-check the ranking examples in `tools/generate_lexicon.py`'s sample
  output.

---

## 8. CI parameters

`.github/workflows/build-release.yml`:

| Setting | Value |
| --- | --- |
| Triggers | `push` to `main`, `pull_request`, `workflow_dispatch` |
| Runner | `ubuntu-latest`, `timeout-minutes: 45` |
| JDK | Temurin 21 |
| Gradle | `gradle/actions/setup-gradle@v4` (dependency + build cache, wrapper validation) |
| Build | `./gradlew assembleRelease --no-daemon --stacktrace` |
| Signing | committed demo keystore, plaintext credentials inline in `app/build.gradle.kts` — no repository secrets |
| Artifact | exactly one universal APK, copied to `nepali-keyboard-<short-sha>.apk` |
| Verification | `apksigner verify --print-certs` + `sha256sum`, both logged |
| Upload | `actions/upload-artifact@v4`, retention 30 days |

Local reproduction is identical because the keystore is in the repository:

```
./gradlew assembleRelease --no-daemon
ls app/build/outputs/apk/release/
```

The keystore is public on purpose (it is a demo key for side-loading builds). It
must never be used for a store release; issuing a real key means adding
`keystore.properties` and reading it with a fallback to the committed values.

---

## 9. Definition of done

A change is complete when all of the following hold:

1. `./gradlew assembleRelease --no-daemon` succeeds in CI (no other verification
   exists; there is no local SDK in the development container).
2. `lint` reports no new errors (`abortOnError = false` keeps warnings visible
   without blocking, but errors are reviewed by hand).
3. No new permission, no new dependency that opens a socket, no `// TODO`, no
   placeholder string resources, no empty `catch` blocks.
4. The transparency log stays honest: the privacy screen in the settings app
   (`settings/PrivacyScreen.kt` and its `privacy_*` strings) still describes the
   app that exists, and no new permission has appeared in the manifest.
5. Every user-visible string is in `res/values/strings.xml`.
