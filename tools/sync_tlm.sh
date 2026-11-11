#!/usr/bin/env bash
# Refresh the bundled TinyLM models + Kotlin engine from NikitHamal/transliterationlm (read-only upstream).
set -euo pipefail
SRC=${1:-/tmp/tlm}
DST=app/src/main/assets/tlm
for f in config.json vocab.json lex_ne.tsv direct_ne.tsv encoder.int8.onnx decoder.int8.onnx ctx.int8.onnx ctx_vocab.txt; do cp "$SRC/artifacts/$f" "$DST/$f"; done
echo "Assets synced from $(git -C "$SRC" rev-parse --short HEAD). The Kotlin engine (tlm/TlmEngine.kt) carries local additions (suggest/completions); re-apply them when porting upstream changes."
