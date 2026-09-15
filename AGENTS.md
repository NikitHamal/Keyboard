# AGENTS.md - Nepali Keyboard Project Guidance

## Architecture & System Overview
This project is a production-grade, highly optimized Android Input Method Editor (IME) written in Kotlin and Jetpack Compose.

### IME Service Plumbing & Lifecycle
- `NepaliImeService` extends `InputMethodService` and manually attaches `ViewTreeLifecycleOwner`, `ViewTreeSavedStateRegistryOwner`, and `ViewTreeViewModelStoreOwner` to the root `ComposeView` container to prevent Compose lifecycle crashes in service contexts.
- `ImeInputController` encapsulates all interactions with `InputConnection`, managing active composing text spans (`setComposingText`, `finishComposingText`), IME actions, cursor repositioning, and grapheme-aware deletion.

### Unicode & Devanagari Invariants
- All backspace and deletion logic utilizes `GraphemeUtils` powered by `java.text.BreakIterator` to respect Devanagari combining characters (matras, halant, chandrabindu, anusvara) and UTF-16 surrogate pairs.
- Text selection and cursor movements on spacebar swipe strictly operate on full grapheme cluster boundaries.

### Transliteration & Lexicon Engine
- `PhoneticEngine` converts Romanized Latin input into Devanagari in real time.
- `LexiconEngine` provides offline candidate generation using a Radix Tree / Compact Trie populated from `assets/nepali_lexicon.json` paired with bi-gram frequency ranking.
- All candidate queries run asynchronously on `Dispatchers.Default` without blocking keypress events or UI frame rendering.

### Performance & Composition Invariants
- Hot-path keypress listeners avoid object allocations.
- UI data classes are annotated with `@Immutable` / `@Stable`.
- Dynamic UI state maps use `derivedStateOf` and state hoists to prevent unnecessary recomposition storms and maintain 120 FPS UI response.

### CI Build & Verification
- GitHub Actions workflow `.github/workflows/build-release.yml` compiles exactly one release APK via `./gradlew assembleRelease --no-daemon`.
- The output APK is renamed to `nepali-keyboard-<SHORT_SHA>.apk` and uploaded via `actions/upload-artifact@v4`.
- Demo keystore is committed at `keystore/release.keystore` with plain-text credentials in `app/build.gradle.kts` for reproducible remote builds.
