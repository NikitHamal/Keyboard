#!/usr/bin/env bash
# Refresh the bundled TinyLM models from NikitHamal/transliterationlm (read-only upstream).
# ONNX is NOT shipped: tools/pack_tlm.py converts the int8 ONNX files into tiny *.tlmw weight files
# for the pure-Kotlin core (tlm/TlmNet.kt), which removes ~60 MB of onnxruntime native libraries from the APK.
set -euo pipefail
SRC=${1:-/tmp/tlm}
DST=app/src/main/assets/tlm
for f in config.json vocab.json lex_ne.tsv direct_ne.tsv ctx_vocab.txt; do cp "$SRC/artifacts/$f" "$DST/$f"; done
python3 tools/pack_tlm.py "$SRC/artifacts" "$DST" --check
echo "Assets synced from $(git -C "$SRC" rev-parse --short HEAD)."
