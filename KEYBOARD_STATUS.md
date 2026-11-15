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


## Session: realtime typing pass (latency + upstream sync)
Upstream `transliterationlm` @ 106e9a0: assets (`lex_ne.tsv`, `direct_ne.tsv`, config, vocab, ctx vocab) were already identical;
the engine rules were not. Ported into `tlm/TlmEngine.kt`: sorry/सरी, उन्छु->उँछु, काठमाडौँ direct fix, पानी/पनि and बुबा
clause rules, छु/ला bias, short-nasal penalty, ब/व guard, extra honorific verbs, ctx skip for k/baba/paani.

Measured with the JVM harness (`tools/tlm_jvm_check/LatencyBench.kt`, 2 vCPU x86, warm): per-keystroke rank went from
~19.6 ms avg (77 ms max) to ~7 ms avg / 13 ms p90 with byte-identical output on 1200 roman inputs x 5 contexts
(`Parity.kt`: diff of old vs new engine = 0 lines).

Engine (`tlm/TlmNet.kt`): 4-accumulator matvec, table GELU, allocation-free decoder step with per-thread scratch,
candidate scoring split across up to 3 helper threads (own KV cache each), allocation-free beam search, context scorer skips
padding rows, cancellable beam (`TlmStale`), deep request reuses a shallow result when no beam was needed, Keys without
per-word Regex compilation (lexicon load ~2x faster), common-word warm-up on a background thread (`TlmLoader.warmup`).

Android pipeline:
- `CandidateEngine`: dedicated display-priority worker (`util/WorkerThreads`), results posted to main with a Handler (the
  worker never waits for the UI), beam search aborts when a newer key arrives, `awaitPublished()` for Space.
- Space never runs the model on the main thread any more (it used to block on the engine lock): it takes the worker's result
  for the current word (<=45 ms wait) and otherwise commits exactly what is on screen.
- `CompositionController` keeps a mirror of the text left of the caret, so `getTextBeforeCursor` (blocking binder call) is no
  longer made on word starts, shift refresh, backspace or prediction. Invalidated on focus change, caret jump, Enter action.
- Backspace inside a word re-shows the model spelling already computed for that prefix on the same frame.
- One UI state publish per key instead of two; suggestion strip hidden no longer disables model upgrade of the composing word.
- Settings Home shows rolling keystroke latency (`util/TlmStats`).
- New launcher icon: crimson flat field, cream keycap with real Noto Sans Devanagari na outline in front of a saffron keycap with
  Outfit "a" (glyphs cut out with even-odd fill, themed monochrome layer uses the same geometry).

## Next ideas
- On-device check of p95 latency (Home -> Engine card). If still high on low-end devices lower `TlmTuning.maxHelperThreads`.
- Baseline profile for the inference kernels.


## Session: realtime pass 3 (upstream check, ctx cache, non-blocking exact Space, baseline profile, new icon)
Upstream `transliterationlm` @ 794d9d1: re-packed `encoder/decoder/ctx.int8.onnx` with `tools/pack_tlm.py` and compared byte-for-byte with
`assets/tlm/{s2s,ctx}.tlmw` (identical), text assets identical, engine rules already ported -> nothing to sync.

Latency/precision changes (all behaviour-preserving):
- `KtCtx` caches the transformer output per context window (`hCache`, 96 entries). The window only depends on the words LEFT of the caret,
  so every extra letter of the same word skips the whole 2-layer pass and only does one dot product per candidate (identical scores).
- Space no longer blocks the main thread for up to 45 ms and no longer commits a pre-beam answer for unseen words. `CandidateEngine` tags each
  result `isFinal` (no beam pending). If the final result is not ready, Space is parked (`pendingSpaceGeneration`), the composing word stays on
  screen, and the space commit happens the instant the final result arrives (or after 160 ms, or when any other input comes - `flushPendingSpace()`).
- Warm-up thread no longer inverts priorities: it ran at BACKGROUND priority while holding the engine lock, so a keystroke could wait on a
  throttled thread. It now runs at default priority and yields while the user is typing (`TlmTuning.userIsTyping`).
- Hand-written baseline profile (`app/src/main/baseline-prof.txt`, wildcard rules for tlm/engine/ime/keyboard/util/data) + explicit
  `androidx.profileinstaller` so the kernels are AOT-compiled on sideloaded installs instead of running interpreted.
- New launcher icon: flat deep-teal field, cream squircle keycap with the Devanagari ka cut out, saffron badge with Roman A cut out
  (real glyph outlines, boolean-difference geometry, no fill-rule dependence, inside the 66dp safe zone, themed monochrome layer).


## Session: web-engine parity (session 42 ensemble), literal chip first, realtime tiers, new icon
Upstream `transliterationlm` @ 253a6b5 (web app = `docs/tlm.js`) had moved far past our port. Now mirrored in `tlm/TlmEngine.kt` + `tlm/TlmEnsemble.kt`:
- **Ensemble** `main + m2 + a4kd` (`config.json` -> `ensemble`): `EnsembleNeural` = web `Transliterator._score/_beam` (Dual-Core: base = main+m2 mean,
  specialist = a4kd (lower-case vocab), capped veto, single-word common protection, beam = union of member beams re-scored).
  `m2.tlmw` / `a4kd.tlmw` packed with `tools/pack_tlm.py` (`tools/sync_tlm.sh` now repacks them), vocab/config copied as `<name>_vocab.json` / `<name>_config.json`.
- **Layer 1/3 rules from the web engine**: informal-typing view (`normRoman`, `altRoman`, `alt_pen`), FSM filter for beam words, `dfloor` for curated entries,
  s35 shield (`sh_tau/sh_att/sh_beam/sh_lock`), `cap_common`, new `canon` rules (lagirahechha, खायेर->खाएर), new `agree` rules (म/मा, पारा, खान, पानी, 1s/1p/2 agreement),
  `सक्नु` joining, `हुनुभयो`/`पर्छ` joining, `सँगै`/`तिर` exceptions, `!` definitive entries in `direct_ne.tsv`.
- **Stage 1.5 stem-bigram LM** (`CompactLM`, `subword_lm.tsv`): Viterbi over consecutive roman words; on the keyboard the already committed word left of the caret seeds the chain.
- Assets synced: `config.json` (new hybrid params), `lex_ne.tsv`, `direct_ne.tsv`, `ctx.tlmw` (re-packed, differed), `subword_lm.tsv`.
- Intentional deviation: `canon` no longer rewrites काठमाडौँ (web does not either).

Strip order: the **letters as typed are now the FIRST chip** (Gboard order), the TinyLM pick is the highlighted chip right behind it, alternatives follow.
The composing text and Space still use the model pick (first non-literal chip; Latin for English/keep words).

Realtime design (two tiers, nothing blocks the UI):
1. keystroke -> `CandidateEngine` worker runs the **fast tier** (main model only, same ~7 ms path as before) and publishes immediately;
2. then the **deep tier** (full ensemble + LM) runs on the same worker, aborts the moment a newer key arrives (`TlmStale` between members), and upgrades the strip/composing word in place.
   If the ensemble answer is already cached the fast request returns it directly. Space parks (<=160 ms) until the deep answer exists, as before.
3. Loading is staged: main model first (typing works), then `TlmLoader.attachExtras` attaches ensemble + LM in the background. Settings -> Engine tile shows Warming up / Ensemble.
Home screen latency line now also reports ensemble p50/p95.

New launcher icon (flat, no gradients/shadows): crimson tonal field, cream keycap (face + lip) with a hand-drawn stroke Devanagari ka, saffron keycap with Roman a,
all inside the 66dp safe zone; themed monochrome layer is outline/stroke only.

Not compiled locally (no JDK/kotlinc in the sandbox, CI only). CI run 43714642665 @ 25cc5cc: green, APK `nepali-keyboard-25cc5cc.apk` (15.0 MB) published as artifact. Not yet measured on a device.

## Next ideas
- Byakaran (grammar) chip in the strip (the joins/agree results are used already, no separate chip yet).
- On-device p95 check of the ensemble tier; if high lower `TlmTuning.maxHelperThreads` or score members in parallel.
