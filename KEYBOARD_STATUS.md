# KEYBOARD_STATUS.md - NikitHamal/Keyboard

Single status file for the Android keyboard. Extend in place.

## Architecture (unchanged, see AGENTS.md)
Kotlin + Compose IME (`NepaliImeService`) + settings app. TinyLM inference is pure Kotlin
(`tlm/TlmNet.kt`, weights packed as `*.tlmw` by `tools/pack_tlm.py`), no onnxruntime, no network permission.
Assets track `NikitHamal/transliterationlm` artifacts (read-only upstream; model training is another agent's job).

## Done earlier (do not redo)
- IME opens: Compose lifecycle / ViewModelStore / SavedStateRegistry owners on the IME DecorView and ComposeView,
  `performAttach()` + `performRestore(null)` before RESUMED, fixed-height input frame, init-order NPE fix.
- TinyLM + lexicon load off the main thread (`ImeRuntime.ensureTlm/ensureLexicon`), LRU cache in `TlmEngine`,
  conflated candidate worker with generation tags.
- `CrashLog` global handler + Diagnostics screen (crash log viewer, test sandbox) in the settings app.

## This session
- BUG FIXED: words were typed twice (`Helloहेल्लो meroमेरो`). Cause: `commitComposition` called
  `finishComposingText()` and then `commitText()`, so the Latin composing text stayed in the field and the
  Devanagari word was appended. Now a single in-place `commitText`. Backspacing the last roman letter also clears
  the composing region instead of leaving one character behind.
- Composing region now shows Devanagari (rule-based preview instantly, upgraded in place to the TinyLM pick when the
  model answers, `CompositionController.applyBest`). Strip shows alternatives. Space/chip commit replaces it.
- Gboard-exact symbol pages: `?123` -> digits row + punctuation, `=\<` flips to page 2 (currency/maths/brackets,
  previously unreachable), `ABC` returns. Labels no longer truncate (`Sym...`).
- Fonts lighter: key caps Regular, strip Regular/Medium, settings SemiBold->Medium; Noto Sans Devanagari is now a
  single variable font (Light..Bold) replacing three static files.
- Slimmer chips: strip candidates are small 12dp tonal rectangles, chevron 32dp, toolbar buttons 30dp, strip 44dp,
  key gap 4dp / radius 9dp, zero shadows (preview bubble shadow removed).
- Settings: choice groups wrap (FlowRow) instead of truncating "System d..."/"AMOLED ...", palette picker is a 2x2
  grid of compact tiles, devanagari sample uses the Devanagari font.
- New launcher icon: flat teal tonal field, cream keycap with Devanagari ka + saffron "A" badge, themed monochrome layer.
- `direct_ne.tsv` synced from transliterationlm (slang table grew 431 -> more rows).

## Next ideas
- Byakaran (grammar) chip in the strip from the sentence layer.
- Per-key long-press hints for the Devanagari layout; trackpad scrub haptics tuning.
