#!/usr/bin/env bash
# Refresh the bundled TinyLM models from NikitHamal/transliterationlm (read-only upstream, web app = docs/).
# ONNX is NOT shipped: tools/pack_tlm.py converts the int8 ONNX files into tiny *.tlmw weight files
# for the pure-Kotlin core (tlm/TlmNet.kt), which removes ~60 MB of onnxruntime native libraries from the APK.
# Needs: pip install numpy onnx.   Usage: tools/sync_tlm.sh /path/to/transliterationlm
set -euo pipefail
SRC=${1:-/tmp/tlm_ref}
M="$SRC/docs/model"
DST=app/src/main/assets/tlm
for f in config.json vocab.json lex_ne.tsv direct_ne.tsv ctx_vocab.txt subword_lm.tsv; do cp "$M/$f" "$DST/$f"; done
python3 - "$M" "$DST" <<'PY'
import importlib.util, sys
spec = importlib.util.spec_from_file_location("pk", "tools/pack_tlm.py"); pk = importlib.util.module_from_spec(spec); spec.loader.exec_module(pk)
M, D = sys.argv[1], sys.argv[2]
pk.pack_seq2seq(f"{M}/encoder.int8.onnx", f"{M}/decoder.int8.onnx", f"{D}/s2s.tlmw")
pk.pack_ctx(f"{M}/ctx.int8.onnx", f"{D}/ctx.tlmw")
import json
for sub in json.load(open(f"{M}/config.json")).get("ensemble", []):
    pk.pack_seq2seq(f"{M}/{sub}/encoder.int8.onnx", f"{M}/{sub}/decoder.int8.onnx", f"{D}/{sub}.tlmw")
    open(f"{D}/{sub}_vocab.json", "w").write(open(f"{M}/{sub}/vocab.json").read())
    open(f"{D}/{sub}_config.json", "w").write(open(f"{M}/{sub}/config.json").read())
PY
echo "Assets synced from $(git -C "$SRC" rev-parse --short HEAD)."
