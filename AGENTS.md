# AGENTS.md — Nepali Keyboard

Machine-readable orientation for an AI agent (or a new human) working in this
repository. Read this before touching code. It records the invariants that are
not obvious from any single file, and the reasoning behind decisions that look
arbitrary until you know the constraint that forced them.

---

## 1. What this project is

A production-grade Android IME (input method editor) for Nepali, written in
Kotlin with a Jetpack Compose UI. It ships three input modes, a fully offline
lexicon, in-app auto-update from GitHub Releases, and zero network access for
the keyboard service itself.

| Fact | Value |
|---|---|
| Application ID | `com.nikit.nepalikeyboard` |
| Package root | `app/src/main/java/com/nikit/nepalikeyboard/` |
| Namespace | `com.nikit.nepalikeyboard` |
| `minSdk` | 26 |
| `targetSdk` / `compileSdk` | 35 |
| Kotlin | 2.1.0 |
| AGP | 8.7.3 |
| Gradle | 8.11.1 (wrapper, committed) |
| Java toolchain | JVM 17 bytecode; CI runs JDK 21 |
| Compose | BOM `2024.12.01`, Material 3 |
| Build files | Gradle Kotlin DSL + Version Catalog |
| Source files | 35 Kotlin files, ~17 k lines |

**There is exactly one build artifact:** a single universal release APK. No
flavours, no ABI splits, no bundles. See § 9.

---

## 2. Environment constraints — read this first

### There is no local Android toolchain

The development environment for this repository has **no Android SDK, no
Gradle daemon, and no ability to compile**. Do not attempt:

```bash
./gradlew build            # will fail: no SDK
./gradlew test             # will fail: no SDK
./gradlew assembleRelease  # will fail: no SDK
```

Do not claim to have run, built, or verified anything that you did not actually
execute. If you write "the project compiles", the only acceptable evidence is a
green run of `.github/workflows/build-release.yml`.

### What you *can* and *should* run locally

Two purpose-built Python checkers live in `tools/`. They are fast (under a
second), need no dependencies, and catch the error classes that would otherwise
only surface as a cryptic Gradle failure in CI thirty seconds and a 200 MB
dependency download later.

```bash
python tools/kotlin_lint.py        # structure: delimiters, packages, scaffold markers
python tools/kotlin_refcheck.py    # references: undeclared names, dangling imports
```

**Both must pass before you commit.** They are also the first two steps of the
CI workflow, so a local pass predicts a CI pass for everything they cover.

#### `tools/kotlin_lint.py` — what it catches

* Unbalanced `{}`, `()`, `[]` — including inside Kotlin string templates
  (`"${...}"`), which is where a naive brace counter desynchronises.
* A `package` declaration that does not match the file's directory path.
* Forbidden scaffold markers: `TODO`, `FIXME`, `implement later`,
  `placeholder`, `not implemented`, `XXX`. The project ships no placeholders,
  and the linter is what enforces that.

> **Known hazard when editing the linter:** the `${...}` handling must
> decrement its depth counter regardless of what else is on the delimiter
> stack. An earlier version guarded the decrement with `and not stack`, which
> is wrong: inside `Column(modifier = Modifier.semantics { ... "${x}" ... })`
> the interpolation closes while `Column(`'s `(` is still legitimately open, so
> the guard never fired and the lexer desynchronised for the rest of the file.
> It produced a convincing false positive on `SuggestionStrip.kt`. If you touch
> that branch, re-run the linter over the whole tree and confirm the count of
> scanned files is unchanged.

#### `tools/kotlin_refcheck.py` — what it catches

Three passes:

1. Extract every declaration (`fun`, `val`, `var`, `class`, `object`, `enum`
   entries) after stripping comments and string literals.
2. Scan every reference and report names that are neither declared nor on the
   framework allow-list.
3. **Resolve every `com.nikit.nepalikeyboard.*` import** against the declaration
   set. `R` and `BuildConfig` are exempt as generated.

Pass 3 is the valuable one. It caught a real dangling import
(`ime.KeyboardSettings`, a class that never existed) that would otherwise have
been the first thing CI reported.

**The allow-list is not a dumping ground.** When the checker reports a name,
decide which of these it is:

* A genuine bug (a typo, a deleted method, a wrong package) → **fix the code**.
* A real framework or stdlib name → add it to `ALLOW`, grouped with the others
  it is related to, in the same alphabetical-ish block.
* A local lambda parameter or destructured name → add it to the second `ALLOW`
  block, which exists for exactly that.

Do not add a name to `ALLOW` to silence a report you have not understood.

---

## 3. Repository layout

```
.
├── .github/workflows/build-release.yml   CI: the only compiler (§ 9)
├── AGENTS.md                             this file
├── build.gradle.kts                      root build (plugin aliases only)
├── settings.gradle.kts                   repositories, module list
├── gradle.properties                     JVM args, feature flags
├── gradle/libs.versions.toml             version catalog — all versions live here
├── gradlew, gradlew.bat                  committed wrapper scripts
├── gradle/wrapper/                       committed wrapper jar + properties
├── keystore/                             committed PUBLIC demo keystore (§ 10)
├── tools/
│   ├── kotlin_lint.py                    structural checker
│   └── kotlin_refcheck.py                reference + import checker
└── app/
    ├── build.gradle.kts                  module config, signing, R8 rules
    ├── proguard-rules.pro                keep rules (§ 8)
    └── src/main/
        ├── AndroidManifest.xml           IME declaration, privacy contract
        ├── assets/dict/ne_lexicon.json   bundled dictionary (§ 6)
        ├── res/                          strings (en, ne), themes, fonts
        └── java/com/nikit/nepalikeyboard/
            ├── NepaliKeyboardApp.kt      Application; scope + speculative load
            ├── ime/                      IME service, ViewModel, state, persistence
            ├── lexicon/                  radix trie, ranking, asset loading
            ├── translit/                 Romanised → Devanagari engine
            ├── unicode/                  grapheme segmentation, Devanagari rules
            ├── ui/                       Compose keyboard, panels, theme
            └── settings/                 settings, sandbox, onboarding
```

### Module boundaries

```
unicode/     ← no dependencies on anything else in the project
translit/    ← unicode
lexicon/     ← unicode (grapheme counting), model/
ui/theme/    ← nothing project-local
ui/          ← ime/ (types only), ui/theme/
ime/         ← everything above
settings/    ← ime/ (types + KeyboardHost), lexicon/, ui/theme/
```

**Rule:** `unicode/`, `translit/`, and `lexicon/` must never import from `ui/`,
`ime/`, or `settings/`. They are the pure layer; keeping them pure is what makes
the transliteration engine reason-able in isolation. The checkers will not catch
a violation here — it is on you.

`ui/` may import *types* from `ime/` (e.g. `KeyboardUiState`, `InputMode`) but
must not import `NepaliImeService`. The UI talks to the service exclusively
through the `ServiceActions` interface (§ 4).

---

## 4. Architecture

### The three lifecycles, and why they are separate

An IME has three lifecycles that people conflate:

| Lifecycle | Owner | Begins | Ends |
|---|---|---|---|
| **Service** | `NepaliImeService` | process start | process death |
| **View** | `KeyboardLifecycleOwner` | first `onCreateInputView` | `onDestroy` |
| **Editor** | `InputConnectionController` | `onStartInput` | `onFinishInput` |

They are genuinely independent. The service outlives many views; a view
outlives many editors. **Do not collapse them.**

### Hosting Compose without an `Activity`

An `InputMethodService` is a `Service`, so it implements none of
`LifecycleOwner`, `ViewModelStoreOwner`, or `SavedStateRegistryOwner`. Calling
`setContent { }` without them throws
`IllegalStateException: Composed into a view that does not have a
ViewTreeLifecycleOwner`.

`KeyboardLifecycleOwner` (`ime/KeyboardLifecycleOwner.kt`) is the fix: a
hand-rolled triple-owner that mirrors what `ComponentActivity` does internally.
`InstallKeyboardViewTreeOwners(owner) { ... }` installs the three view-tree tags
and **removes them on dispose** — a view whose tags outlive their owner crashes
the next time anything resolves against it, because the retrieved registry is
already `DESTROYED`.

The IME's visibility is mapped so that hiding the keyboard does **not** destroy
the owner:

```
service created          → ON_CREATE             → CREATED
keyboard visible         → ON_START + ON_RESUME  → RESUMED
keyboard hidden          → ON_PAUSE              → STARTED
service destroyed        → ON_STOP + ON_DESTROY  → DESTROYED
```

This matters: the keyboard is hidden and shown on *every field focus change*.
Destroying on hide would rebuild the lexicon query and restart suggestion
pipeline on each one.

### MVI flow

```
                    ┌──────────────────────────────────────┐
   key press ──────▶│  KeyboardViewModel                   │
                    │                                      │
   editor focus ───▶│  • romanBuffer / devanagariBuffer    │──▶ StateFlow<KeyboardUiState>
   IME callback     │  • Mutex guarding composer mutations │──▶ StateFlow<List<Suggestion>>
                    │  • Suggestions, clipboard, emoji     │──▶ StateFlow<List<ClipItem>>
                    └───────────────┬──────────────────────┘──▶ SharedFlow<KeyboardEvent>
                                    │
                                    ▼
                    InputConnectionController  (the ONLY writer)
                                    │
                                    ▼
                             InputConnection
```

Rules:

* **The UI never touches an `InputConnection`.** It calls ViewModel methods and
  reads immutable snapshots. This boundary is what keeps Binder calls on one
  auditable path.
* **`uiState` and `suggestions` are separate flows.** The strip changes on every
  keystroke; the key grid does not. Merging them recomposes the whole keyboard
  per character.
* **`events` is a `SharedFlow` with `replay = 0` and a dropping buffer.** If the
  UI is not collecting (keyboard hidden), events are dropped rather than burst
  on reappearance.
* **All composer mutations are serialised by a `Mutex`.** `onUpdateSelection`
  and the keystroke handler can run in the same frame; without the lock the
  composing region can be set from an already-invalidated buffer.

### `ServiceActions` — why it is an interface

`ServiceActions` (`ime/NepaliImeService.kt`) has two implementations:
`ImeServiceActions` (forwards to the running service) and `NoOpServiceActions`
(for the settings sandbox and previews).

It is an interface rather than a concrete class holding a nullable service
because the alternative puts `?.` on twenty lines of UI code and makes
"did the sandbox forget this action?" invisible. With the interface, a new
action is a compile error in both implementations until it is handled.

---

## 5. The composing-text, Unicode, and `InputConnection` contract

This is the part of the codebase where a bug corrupts user data. Read it
carefully.

### Composing protocol

Romanised input is inherently a composing operation: type `namast`, see नमस्त;
type `e`, see नमस्ते.

1. **While composing:**
   `ic.setComposingText(devanagari, 1)` — the whole Romanised buffer is replaced
   in the field by its Devanagari rendering, marked as composing so the app
   underlines it and knows it is not final.
2. **On commit** (space, punctuation, suggestion tap, Enter):
   `setComposingText(final, 1)` then `finishComposingText()`, or
   `commitText(final, 1)` which finishes implicitly.
3. **On abandon** (mode switch, focus loss, backspace past start):
   `finishComposingText()` with whatever is currently shown, so the app is
   never left holding a dangling composing region. Committing is preferred to
   discarding when the user has typed something real — `onFinishInputView`
   commits, it does not drop.

`CURSOR_AT_END = 1` always. **Never pass `0` or a negative value** as
`newCursorPosition`: those place the caret *inside* the text just written, so
the next keystroke inserts mid-syllable.

We keep a shadow copy (`InputConnectionController.composingText`) because
reading it back is both slower and unreliable — some apps report an empty
`getComposingText` while a region is active, and some WebView-based editors drop
composing spans entirely.

### The caret-index identity

There is no API to query the absolute caret position. Every cursor operation
relies on this identity:

> `getTextBeforeCursor(n, 0).length` **equals the absolute caret index**,
> provided at least `n` characters precede the caret.

For a cursor drag that is always true; for the start of a field it is not, which
is why `moveCursorByClusters` needs the `isCursorAtStart()` probe. Do not build
new cursor logic that assumes the identity unconditionally.

### Unicode safety — two distinct hazards

**Surrogate pairs.** A character above U+FFFF is two UTF-16 code units.
Deleting "one character" must move by two units, or you emit a lone surrogate
that renders as U+FFFD and corrupts the buffer.

**Devanagari aksharas.** एक is one grapheme cluster but *three* code points
(ए, क, ्). Deleting one "character" must remove the whole cluster, not leave a
floating virama attached to nothing.

Both are solved the same way: **never do arithmetic on character counts.** Every
deletion walks the buffer with `GraphemeClusterSegmenter`, which implements the
UAX #29 extended-grapheme-cluster rules *plus* the Devanagari virama-joins-
forward override that UAX #29 alone does not give you.

**Do not** use `String.length`, `Char.isLetter`, `String.dropLast(1)`, or
`text[text.length - 1]` on any buffer that can contain Devanagari or emoji. If
you need "one back", it is `GraphemeClusterSegmenter.previousBoundary(...)`. No
exceptions.

### `EditorInfo` handling

`InputConnectionController.attach(info, connection)` parses the `EditorInfo`
**once** and derives every flag the hot paths need, so no keystroke reconsults a
bitmask. It also resets per-editor state — switching from a chat field into a
password field must not leak the previous field's composing buffer.

Password detection covers four cases, not one:

| Case | Condition |
|---|---|
| Plain password | text class + `TYPE_TEXT_VARIATION_PASSWORD` |
| Visible password | text class + `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` |
| Numeric PIN | number class + `TYPE_NUMBER_VARIATION_PASSWORD` |
| WebView password | text class + variation `0xE0` (stable in the platform, not a public constant) |

Plus: **any** field with `TYPE_TEXT_FLAG_NO_SUGGESTIONS` is treated as a
password field. Some banking apps use that flag instead of a password variation,
and "no suggestions" is a clear signal the content is secret.

**In a password field: zero learning, zero suggestions, zero retention.** No
learned-word counter is incremented, no suggestion strip is populated, nothing
is written to history. This is checked at the top of every learner, not at the
call site — see `KeyboardViewModel`'s KDoc.

### Selection changes

`onUpdateSelection` → `KeyboardViewModel.onSelectionUpdated` →
`InputConnectionController.selectionMovedOutsideComposing`. If the caret leaves
the composing region and we keep composing, **the next keystroke replaces text
at the wrong location**. That is the single most destructive bug an IME can
have. This guard is load-bearing; do not weaken it.

---

## 6. The transliteration and lexicon engines

### Romanised → Devanagari

`translit/RomanizedEngine.kt` with rules in `translit/TransliterationRules.kt`.

Canonical examples that must keep working:

| Input | Output |
|---|---|
| `namaste` | नमस्ते |
| `dhanyabad` | धन्यवाद |
| `nepal` | नेपाल |
| `mero` | मेरो |
| `kshya` | क्ष्य |
| `tra` | त्र |
| `gya` | ज्ञ |

The engine handles conjuncts, schwa deletion, and the matra/anusvara/
chandrabindu/visarga/nukta set.

#### Adding a transliteration rule

1. Add the mapping to `TransliterationRules.kt`, in the table its
   longest-match ordering belongs to. **Order matters:** the matcher is
   greedy-longest-first, so `kshya` must be reachable before `ks`.
2. Add a case to the examples above if it is a canonical one.
3. Run both checkers.

Do not add rules for inputs shorter than two characters that overlap a common
vowel — a one-character rule fires before the user has finished typing a
cluster and makes the engine feel like it is fighting them.

### The lexicon

`lexicon/` — a compressed radix trie (`RadixTrie.kt`) with edge splitting, plus
bigram frequency scoring (`CandidateRanker.kt`), loaded from the bundled asset
by `LexiconRepository.kt` (a process-level singleton, so rotation costs
nothing).

* **All candidate generation is async**, on `Dispatchers.Default`, with
  `collectLatest` so a superseded query is cancelled rather than racing.
* The full asset is parsed once. There is no per-keystroke disk access.

### Adding entries to the lexicon asset

`app/src/main/assets/dict/ne_lexicon.json`. The shape is:

```json
{
  "metadata": { ... },
  "words": [
    { "d": "नमस्ते", "r": "namaste", "f": 1.0, "a": ["namastey", "namasthe"], "c": "n" }
  ],
  "bigrams": [ ... ]
}
```

| Key | Meaning |
|---|---|
| `d` | Devanagari form — what gets committed |
| `r` | primary Romanised form — what the user types |
| `f` | frequency weight, used for ranking; keep it in a sane range |
| `a` | accepted alternative Romanised spellings |
| `c` | category tag |

Current contents: 164 words, 79 bigrams.

**Rules for editing this file:**

1. Keep the JSON valid and UTF-8. A malformed asset is a crash on first
   suggestion, not a degraded mode — `LexiconRepository` catches load failures
   and falls back to transliteration only, but do not rely on that to cover a
   typo you could have caught.
2. Every `d` value must be a valid Devanagari string — check matras and
   conjuncts render without vertical clipping in the bundled Noto Sans
   Devanagari (§ 7).
3. Never add an entry whose `r` duplicates another entry's `r`. The trie will
   hold both and the strip will show two identical-looking candidates.
4. The asset is deserialized with `kotlinx.serialization`. If you change the
   shape, update `lexicon/model/LexiconModels.kt` and the ProGuard rules in § 8
   **in the same commit**.

---

## 7. UI, theme, and typography

* Material 3 Expressive tokens, rounded key geometry, tonal elevation.
* Dynamic colour (Material You) on Android 12+, with curated fallbacks.
* Dark / Light / **AMOLED** modes. AMOLED is true black, not "very dark grey" —
  it exists to let OLED panels switch pixels off.
* `ui/theme/Theme.kt` holds both the Material scheme and `KeyboardColors`
  (key/modifier/accent/surface/text/border/ripple). `KeyboardTheme.colors` is
  the accessor, mirroring `MaterialTheme.colorScheme`.
* **Dynamic colour tints the app chrome, not the key geometry.** Deriving a
  keyboard's key/modifier/accent triad from a wallpaper scheme produces
  unusable results (pastel accent on pastel key). `KeyboardColors` stays on the
  project palette in every mode.

### Fonts

`res/font/` contains both families, bundled — **no downloadable fonts**, no
Google Fonts provider, no network dependency:

* `poppins_{regular,medium,semibold,bold,extrabold,black}.ttf` — Latin
* `noto_sans_devanagari.ttf` — Devanagari

`FontFamily` definitions are explicit in `Theme.kt`. **Noto Sans Devanagari is
listed first** in the fallback chain so Latin falls through to Poppins while
Devanagari shapes through Noto.

When adding or changing any text that contains Devanagari, verify:

* matras (े ै ो ौ) are not vertically clipped
* conjuncts (क्ष त्र ज्ञ) render as ligatures, not as separated glyphs
* the halant (्) does not sit on the baseline as a visible artifact
* numerals ०१२३४५६७८९ have the same line height as Latin digits in mixed runs

Clipping is a `lineHeight`/`includeFontPadding` problem, not a font problem.
Fix it in the `TextStyle`, not by swapping fonts.

### Localisation

`values/strings.xml` (English) and `values-ne/strings.xml` (Nepali).
`resourceConfigurations += listOf("en", "ne")` in `app/build.gradle.kts`.

Key labels (`key_shift`, `key_backspace`, …) are marked `translatable="false"`
and carry their glyph directly. Do not translate a glyph.

**Every user-visible string goes in `strings.xml`.** No hard-coded literals in
composables. The only exceptions are the keyboard's own key glyphs, which are
already resources.

---

## 8. Performance rules

The target is 120 FPS. These are not suggestions.

* **Zero allocations on keypress and pointer dispatch.** No `listOf(...)`,
  no string interpolation, no boxing, no lambda capture in a key's press path.
* **`@Immutable` / `@Stable` on every model the UI renders**, so Compose can
  skip recomposition instead of comparing fields.
* **`derivedStateOf`** for values computed from other state, so a read does not
  itself become a subscription.
* **`staticCompositionLocalOf`** for values that never change within a
  composition (`KeyboardTheme.colors`), so reads do not subscribe.
* **Read state as late as possible.** `Modifier.drawBehind { }` reading state is
  far cheaper than a composable reading it, because it invalidates draw rather
  than composition.
* **Never call `getTextBeforeCursor` per keystroke in a way that lets results
  pile up.** It is a synchronous IPC that can exceed a frame budget.
* **No `LazyColumn` inside the keyboard's key grid.** The grid is a fixed,
  small, known-size structure; lazy layout buys nothing and costs a measurement
  pass.
* **Suspend, do not block.** A `runBlocking` anywhere on the main thread in this
  project is a bug.

### R8 / ProGuard

`app/proguard-rules.pro` must keep:

* the `InputMethodService` subclass and its manifest-declared entry points
* `kotlinx.serialization` generated serializers for the lexicon DTOs
* enum `valueOf`/`values` where reflection touches them

`isShrinkResources = true` and `isMinifyEnabled = true` are on for release.
**A release build is the only one that exercises the keep rules** — a debug
build passing tells you nothing about whether the release APK runs.

---

## 9. CI

`.github/workflows/build-release.yml`. This is the project's only compiler.

**Triggers:** push to `main`, pull request to `main`, `workflow_dispatch`.

**Runner:** `ubuntu-latest`, JDK 21 (Temurin), `timeout-minutes: 45`.

**Steps, in order:**

1. `actions/checkout@v4` — `fetch-depth: 1`
2. `actions/setup-python@v5` — Python 3.13
3. `python tools/kotlin_lint.py` — **fails fast, before the JDK install**
4. `python tools/kotlin_refcheck.py`
5. `actions/setup-java@v4` — Temurin 21
6. `gradle/actions/setup-gradle@v4` — `validate-wrappers: true`
7. `./gradlew assembleRelease --no-daemon --stacktrace`
8. Rename to `nepali-keyboard-v<VERSION>-<SHORT_SHA>.apk` (`versionName` from
   `app/build.gradle.kts`, `git rev-parse --short HEAD`)
9. Verify the APK
10. `actions/upload-artifact@v4` — `if-no-files-found: error`

### Why the source checks run before the JDK

They are pure Python, take under a second, and catch the two error classes that
would otherwise appear as a confusing compiler message after a 200 MB
dependency download. A typo costs ten seconds instead of two minutes.

### Why `--no-daemon`

The runner is single-use. A daemon would be started, used once, and then
either killed by runner cleanup or left holding a JVM. The startup cost saved is
paid once and is small next to the download; what `--no-daemon` buys is that the
build's memory settings are the ones in `gradle.properties`, not whatever a
stale daemon was started with.

### Why no `clean`

The checkout is fresh. `clean` on a cold cache only invalidates the
configuration cache for no benefit.

### Why the artifact name uses version and short SHA

The Gradle output is always `app-release.apk`. Two builds from different commits
would be indistinguishable by name — which is the situation a "which APK is
this?" bug report is made of. The `name-v{version}-{hash}` shape matches the
convention used by sibling projects.

### What the verification step asserts

| Assertion | Failure mode it catches |
|---|---|
| File exists | Silent rename/copy failure |
| Size ≥ 1 MB | Assets dropped — the two fonts alone exceed this |
| Valid zip | Truncated or corrupted output |
| Contains `classes.dex` | Resource-only archive; nothing to run |

Signature verification is deliberately **not** here: `apksigner` needs the
Android SDK build-tools, a large download for an assertion that R8 cannot pass
without — a release build fails outright if `signingConfig` is missing. The
check would be redundant.

### Artifact

One artifact named `nepali-keyboard-v<VERSION>-<SHORT_SHA>`, containing one APK,
`nepali-keyboard-v<VERSION>-<SHORT_SHA>.apk`. Retention 30 days. `if-no-files-found: error`
turns "the build produced nothing" into a failed step rather than a silent
success with an empty download.

**Do not add a second artifact path.** Exactly one output is a deliberate
property of this repository.

---

## 10. Release signing

`keystore/release.keystore` is a **deliberately public demo key**, committed to
the repository with its credentials in plain text at
`keystore/release.keystore.properties`:

```properties
storePassword=nepalikey
keyAlias=nepali-keyboard
keyPassword=nepalikey
```

**Why:** it lets a remote CI build produce a *signed* release APK with zero
external secrets. Nobody needs to configure a GitHub Secret, and the build works
in a fork on the first push.

**Its limits — state these, do not paper over them:**

* The private key is public. **Anybody can forge an update signed with it.**
* It is not hardware-backed.
* It **must never** be used to sign a build distributed on Google Play or any
  other store.

`.gitignore` explicitly does **not** ignore `keystore/release.keystore`, with a
comment pointing at this section. Do not add it to `.gitignore`.

---

## 11. Manifest and privacy invariants

`app/src/main/AndroidManifest.xml` declares **zero permissions except
`VIBRATE`**, and **must never declare `android.permission.INTERNET`**.

The absence of `INTERNET` means the Android platform itself makes it impossible
for this process to open a socket. That is a stronger guarantee than any amount
of code review, and it is the foundation of the privacy claims in the settings
screen and the Play listing.

If a feature appears to need network access, **it does not belong in this app.**
The entire lexicon and the entire transliteration engine are bundled and run
on-device. There is no telemetry, no crash upload, no advertising identifier,
no remote dictionary fetch.

Also in the manifest:

* `<service android:permission="android.permission.BIND_INPUT_METHOD">` with the
  `android.view.InputMethod` intent filter and the `android.view.im` meta-data
  pointing at `@xml/ime_subtypes`.
* `SettingsActivity` is exported (the system IME switcher launches it via an
  explicit component intent) and is the launcher.
* `OnboardingActivity` and `TypingSandboxActivity` are **not** exported.
* `ClipboardCaptureInitProvider` is a no-op `ContentProvider` with an empty
  authority; its only job is to run before `Application.onCreate` so a
  clipboard listener can be registered as early as possible.

---

## 12. Definition of done

Before you consider a change complete:

- [ ] `python tools/kotlin_lint.py` — no findings
- [ ] `python tools/kotlin_refcheck.py` — no findings
- [ ] No `// TODO`, `// FIXME`, `// implement later`, or truncated code blocks
- [ ] No new `INTERNET` permission, no new permission at all without a reason
      recorded in the manifest
- [ ] Every user-visible string is in `strings.xml` (both `en` and `ne`)
- [ ] Any Devanagari added renders without clipping in both line heights
- [ ] No character-count arithmetic on a Devanagari- or emoji-capable buffer
- [ ] `unicode/`, `translit/`, `lexicon/` still import nothing from `ui/`,
      `ime/`, or `settings/`
- [ ] If the lexicon asset shape changed: `LexiconModels.kt` and
      `proguard-rules.pro` changed in the same commit
- [ ] If a transliteration rule was added: the canonical examples in § 6 still
      hold
- [ ] You have not claimed to have built, run, or tested anything you did not
      actually execute

---

## 13. Things that look wrong but are not

A short list, so nobody "fixes" them.

| Looks like | Actually |
|---|---|
| `NoOpServiceActions` with empty method bodies | Intentional. The sandbox hosts the real keyboard UI in an `Activity` with no service behind it. |
| `CURSOR_AT_END = 1` hard-coded everywhere | The only correct value. See § 5. |
| Composing state kept in a field *and* a `StateFlow` | The field is the hot-path truth; the flow is for rendering. Copying one into the other per keystroke would allocate. |
| `KeyboardPreferencesDefaults.KEYBOARD_HEIGHT_DP` duplicating a settings default | Breaks a `settings` ↔ `ime` package cycle. Documented at the declaration. |
| `KeyboardLifecycleOwner` instead of `ComponentActivity` | There is no activity. See § 4. |
| `isCrunchPngs = false` | The lexicon and fonts are already compact; re-compressing them costs build time and changes bytes for no gain. |
| `abortOnError = false` in `lint` | Lint is advisory here; the checkers and the compiler are the gates. Flipping this on is a decision, not a cleanup. |
| Two separate `Application.onCreate` preload launches | One for the lexicon, one for settings priming. Both are speculative and neither is awaited. |

---

## 14. Quick reference

```bash
# Check the sources (the only thing that runs locally)
python tools/kotlin_lint.py
python tools/kotlin_refcheck.py

# Build (CI ONLY — no local Android SDK)
./gradlew assembleRelease --no-daemon --stacktrace

# Count the project
find app/src/main/java -name '*.kt' | wc -l
```

| I want to… | Go to |
|---|---|
| Add a transliteration rule | `translit/TransliterationRules.kt`, then § 6 |
| Add dictionary words | `app/src/main/assets/dict/ne_lexicon.json`, then § 6 |
| Add a key to the keyboard | `ime/KeyboardKey.kt` + `ui/KeyboardScreen.kt` |
| Add a setting | `settings/SettingsRepository.kt` + `ui/KeyboardScreen.kt` (state) |
| Change the theme | `ui/theme/Theme.kt` |
| Add an emoji | `ui/EmojiCatalog.kt` |
| Change the build | `app/build.gradle.kts` + `gradle/libs.versions.toml` |
| Change what CI does | `.github/workflows/build-release.yml`, then § 9 |
