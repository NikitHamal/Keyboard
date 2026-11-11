#!/usr/bin/env python3
"""Pack the TinyLM int8 ONNX models into one tiny dependency-free weight file per model (``*.tlmw``).

Why: onnxruntime-android costs ~15 MB per ABI. The models are small transformers (1.9 M + 0.5 M params),
so the app ships its own ~200-line pure-Kotlin inference core (``tlm/TlmNet.kt``) and reads these files instead.
Weights stay int8 (symmetric, per-tensor scale), activations are float, so quality is >= ORT dynamic-int8.

Usage:
    python3 tools/pack_tlm.py <dir with encoder/decoder/ctx .int8.onnx> <out dir> [--check]

File format (little endian):
    magic "TLW1" | u32 n_tensors | repeat { u16 name_len, name utf8, u8 kind, u8 ndim, u32 dims[ndim], payload }
    kind 0: float32 payload
    kind 1: int8 payload, preceded by float32 scale (per tensor)           value = q * scale
    kind 2: int8 payload, preceded by float32 scale[dims[0]] (per row)     value = q * scale[row]
Linear weights are stored transposed ([out, in]) so the Kotlin inner loop is contiguous.
"""
import struct
import sys
from collections import defaultdict

import numpy as np
import onnx
from onnx import numpy_helper


def load(path):
    m = onnx.load(path)
    init = {i.name: numpy_helper.to_array(i) for i in m.graph.initializer}
    return m, init


def deq(init, base):
    """Dequantize `<base>_quantized` with `<base>_scale/_zero_point` (DequantizeLinear, per tensor)."""
    q = init[base + "_quantized"].astype(np.float32)
    zp = init[base + "_zero_point"].astype(np.float32)
    return (q - zp) * init[base + "_scale"].astype(np.float32)


def linear_weights(m, init):
    """MatMulInteger weight -> (bias name, int8 [out,in], scale)."""
    cons = defaultdict(list)
    for n in m.graph.node:
        for i in n.input:
            cons[i].append(n)
    out = {}
    for n in m.graph.node:
        if n.op_type != "MatMulInteger":
            continue
        wname = n.input[1]
        base = wname[: -len("_quantized")]
        assert int(init[base + "_zero_point"]) == 0, "expected symmetric int8 weights"
        W = init[wname].astype(np.int8)
        scale = float(init[base + "_scale"])
        frontier, bias = [n.output[0]], None
        for _ in range(6):
            nxt = []
            for t in frontier:
                for c in cons[t]:
                    if c.op_type == "Add" and any(i in init and i.endswith(("bias", "out_bias")) for i in c.input):
                        bias = [i for i in c.input if i in init][0]
                    elif c.op_type in ("Cast", "Mul"):
                        nxt.append(c.output[0])
            if bias:
                break
            frontier = nxt
        assert bias, f"no bias found for {wname}"
        out[bias] = (np.ascontiguousarray(W.T), scale)
    return out


class Writer:
    def __init__(self):
        self.items = []

    def f32(self, name, a):
        self.items.append((name, 0, np.asarray(a, dtype=np.float32), None))

    def i8(self, name, q, scale):
        self.items.append((name, 1, np.asarray(q, dtype=np.int8), np.float32(scale)))

    def i8row(self, name, q, scales):
        self.items.append((name, 2, np.asarray(q, dtype=np.int8), np.asarray(scales, dtype=np.float32)))

    def save(self, path):
        b = bytearray(b"TLW1" + struct.pack("<I", len(self.items)))
        for name, kind, a, sc in self.items:
            nm = name.encode()
            b += struct.pack("<H", len(nm)) + nm + struct.pack("<BB", kind, a.ndim) + struct.pack(f"<{a.ndim}I", *a.shape)
            if kind == 1:
                b += struct.pack("<f", float(sc))
            elif kind == 2:
                b += sc.astype("<f4").tobytes()
            b += a.astype({0: "<f4", 1: "i1", 2: "i1"}[kind]).tobytes()
        open(path, "wb").write(bytes(b))
        return len(b)


def pack_seq2seq(enc_path, dec_path, out):
    w = Writer()
    for tag, p in (("enc", enc_path), ("dec", dec_path)):
        m, init = load(p)
        lin = linear_weights(m, init)
        for bias, (W, s) in lin.items():
            nm = bias[2:] if bias.startswith("m.") else bias
            nm = nm[: -len(".bias")] if nm.endswith(".bias") else "out"
            w.i8(nm + ".weight", W, s)
            w.f32(nm + ".bias", init[bias])
        for k, v in init.items():
            if k.startswith("m.") and v.dtype == np.float32 and (".n" in k or k.startswith(("m.ne.", "m.nd."))) and "bias" not in k.split(".")[-2:-1]:
                w.f32(k[2:], v)
            elif k.startswith("m.") and k.endswith("out_bias"):
                pass
        for e in ("se", "sp") if tag == "enc" else ("te", "tp"):
            w.f32(e + ".weight", deq(init, f"m.{e}.weight"))
        if tag == "dec":
            w.f32("out_bias", init["m.out_bias"])
        # layer norms (weights + biases) – collected explicitly so nothing is missed
        for k, v in init.items():
            if k.startswith("m.") and v.dtype == np.float32 and v.ndim == 1:
                nm = k[2:]
                parts = nm.split(".")
                if len(parts) >= 2 and (parts[-2] in ("n1", "n2", "n3", "ne", "nd", "ln1", "ln2", "ln") or parts[0] in ("ne", "nd")):
                    if not any(it[0] == nm for it in w.items):
                        w.f32(nm, v)
    return w.save(out)


def pack_ctx(path, out):
    m, init = load(path)
    w = Writer()
    lin = linear_weights(m, init)
    for bias, (W, s) in lin.items():
        nm = bias[2:-len(".bias")]
        w.i8(nm + ".weight", W, s)
        w.f32(nm + ".bias", init[bias])
    for k, v in init.items():
        if k.startswith("m.") and v.dtype == np.float32 and (k.endswith("pos") or ".ln" in k or k.startswith("m.ln.")):
            w.f32(k[2:], v)
    w.i8row("emb", init["qE"], deq(init, "qS").reshape(-1))
    w.f32("cbias", deq(init, "m.bias"))
    return w.save(out)


# ---------------------------------------------------------------- numpy reference (mirrors Kotlin TlmNet) ----------
def load_tlmw(path):
    b = open(path, "rb").read()
    assert b[:4] == b"TLW1"
    n = struct.unpack_from("<I", b, 4)[0]
    o, T = 8, {}
    for _ in range(n):
        (ln,) = struct.unpack_from("<H", b, o); o += 2
        name = b[o:o + ln].decode(); o += ln
        kind, nd = struct.unpack_from("<BB", b, o); o += 2
        dims = struct.unpack_from(f"<{nd}I", b, o); o += 4 * nd
        cnt = int(np.prod(dims))
        if kind == 0:
            T[name] = np.frombuffer(b, "<f4", cnt, o).reshape(dims).copy(); o += 4 * cnt
        elif kind == 1:
            s = struct.unpack_from("<f", b, o)[0]; o += 4
            T[name] = np.frombuffer(b, "i1", cnt, o).reshape(dims).astype(np.float32) * s; o += cnt
        else:
            s = np.frombuffer(b, "<f4", dims[0], o).copy(); o += 4 * dims[0]
            T[name] = np.frombuffer(b, "i1", cnt, o).reshape(dims).astype(np.float32) * s[:, None]; o += cnt
    return T


def ln(x, T, p, eps=1e-5):
    mu = x.mean(-1, keepdims=True); var = ((x - mu) ** 2).mean(-1, keepdims=True)
    return (x - mu) / np.sqrt(var + eps) * T[p + ".weight"] + T[p + ".bias"]


def lin(x, T, p):
    return x @ T[p + ".weight"].T + T[p + ".bias"]


def gelu(x):
    from math import sqrt
    from scipy.special import erf  # noqa
    return 0.5 * x * (1 + erf(x / sqrt(2)))


def _erf(x):
    # Abramowitz-Stegun 7.1.26 would be too coarse; use numpy vectorised math.erf
    import math
    return np.vectorize(math.erf)(x)


def gelu_np(x):
    return 0.5 * x * (1.0 + _erf(x / np.sqrt(2.0)).astype(np.float32))


def mha(T, p, xq, xkv, bias, h):
    q, k, v = lin(xq, T, p + ".q"), lin(xkv, T, p + ".k"), lin(xkv, T, p + ".v")
    L, D = q.shape; S = k.shape[0]; dk = D // h
    q = q.reshape(L, h, dk).transpose(1, 0, 2); k = k.reshape(S, h, dk).transpose(1, 0, 2); v = v.reshape(S, h, dk).transpose(1, 0, 2)
    a = q @ k.transpose(0, 2, 1) / np.sqrt(dk) + bias
    a = np.exp(a - a.max(-1, keepdims=True)); a /= a.sum(-1, keepdims=True)
    return lin((a @ v).transpose(1, 0, 2).reshape(L, D), T, p + ".o")


def ref_encode(T, src, d, h, nl):
    S = len(src); bias = (np.array(src) == 0)[None, :] * -1e4
    x = T["se.weight"][src] * np.sqrt(d) + T["sp.weight"][:S]
    for l in range(nl):
        p = f"enc.{l}"
        y = ln(x, T, p + ".n1"); x = x + mha(T, p + ".att", y, y, bias, h)
        x = x + lin(gelu_np(lin(ln(x, T, p + ".n2"), T, p + ".ff.a")), T, p + ".ff.b")
    return ln(x, T, "ne")


def ref_decode(T, tgt, mem, src, d, h, nl):
    L = len(tgt); x = T["te.weight"][tgt] * np.sqrt(d) + T["tp.weight"][:L]
    causal = np.triu(np.full((L, L), -1e4), 1)[None]; cb = (np.array(src) == 0)[None, None, :] * -1e4
    for l in range(nl):
        p = f"dec.{l}"
        y = ln(x, T, p + ".n1"); x = x + mha(T, p + ".sa", y, y, causal, h)
        x = x + mha(T, p + ".ca", ln(x, T, p + ".n2"), mem, cb, h)
        x = x + lin(gelu_np(lin(ln(x, T, p + ".n3"), T, p + ".ff.a")), T, p + ".ff.b")
    lg = ln(x, T, "nd") @ T["out.weight"].T + T["out.bias"] if False else ln(x, T, "nd") @ T["out.weight"].T + T["out_bias"]
    lg = lg - lg.max(-1, keepdims=True)
    return lg - np.log(np.exp(lg).sum(-1, keepdims=True))


def ref_ctx(T, win, cand, d=96, h=4, nl=3):
    x = T["emb"][win] + T["pos"]
    mask = (np.array(win) == 0)[None, None, :] * -1e9
    for l in range(nl):
        p = f"blocks.{l}"
        y = ln(x, T, p + ".ln1"); qkv = lin(y, T, p + ".qkv"); q, k, v = np.split(qkv, 3, -1)
        T_, D = q.shape; dk = D // h
        sp = lambda t: t.reshape(T_, h, dk).transpose(1, 0, 2)
        a = sp(q) @ sp(k).transpose(0, 2, 1) / np.sqrt(dk) + mask
        a = np.exp(a - a.max(-1, keepdims=True)); a /= a.sum(-1, keepdims=True)
        x = x + lin((a @ sp(v)).transpose(1, 0, 2).reshape(T_, D), T, p + ".o")
        x = x + lin(gelu_np(lin(ln(x, T, p + ".ln2"), T, p + ".f1")), T, p + ".f2")
    hh = lin(ln(x[5:6], T, "ln"), T, "proj")[0]
    return (T["emb"][cand] * hh).sum(-1) + T["cbias"][cand]


if __name__ == "__main__":
    src_dir, out_dir = sys.argv[1], sys.argv[2]
    import os
    os.makedirs(out_dir, exist_ok=True)
    n1 = pack_seq2seq(f"{src_dir}/encoder.int8.onnx", f"{src_dir}/decoder.int8.onnx", f"{out_dir}/s2s.tlmw")
    n2 = pack_ctx(f"{src_dir}/ctx.int8.onnx", f"{out_dir}/ctx.tlmw")
    print("s2s.tlmw", n1, "ctx.tlmw", n2)
    if "--check" in sys.argv:
        import json
        import onnxruntime as ort
        cfg = json.load(open(f"{src_dir}/config.json")); vocab = json.load(open(f"{src_dir}/vocab.json"))
        c = cfg["cfg"]; d, h, ne, nd = c["d"], c["h"], c["enc"], c["dec"]
        T = load_tlmw(f"{out_dir}/s2s.tlmw"); C = load_tlmw(f"{out_dir}/ctx.tlmw")
        se = ort.InferenceSession(f"{src_dir}/encoder.int8.onnx"); sd = ort.InferenceSession(f"{src_dir}/decoder.int8.onnx")
        s2i = {ch: i for i, ch in enumerate(vocab["src"])}; t2i = {ch: i for i, ch in enumerate(vocab["tgt"])}
        worst, agree, tot = 0, 0, 0
        for w, t in [("namaste", "नमस्ते"), ("dhanyabad", "धन्यवाद"), ("kasto", "कस्तो"), ("chha", "छ"), ("ramro", "राम्रो"), ("xaina", "छैन")]:
            src = [s2i["<ne>"]] + [s2i[ch] for ch in w]
            tgt = [1] + [t2i[ch] for ch in t]
            mem = se.run(None, {"src": np.array([src], dtype=np.int64)})[0]
            ref = sd.run(None, {"mem": mem, "src": np.array([src], dtype=np.int64), "tgt": np.array([tgt], dtype=np.int64)})[0][0]
            mine = ref_decode(T, tgt, ref_encode(T, src, d, h, ne), src, d, h, nd)
            worst = max(worst, np.abs(ref - mine).max()); agree += int((ref.argmax(-1) == mine.argmax(-1)).sum()); tot += len(tgt)
        print("s2s max |dlogp| vs ORT:", round(float(worst), 3), "argmax agreement", agree, "/", tot)
        so = ort.InferenceSession(f"{src_dir}/ctx.int8.onnx")
        rng = np.random.default_rng(0); wc = 0; ag = 0
        for _ in range(20):
            win = rng.integers(3, 3000, 11); win[5] = 2; cand = rng.integers(3, 3000, 5)
            a = so.run(None, {"ctx": win[None].astype(np.int64), "cand": cand[None].astype(np.int64)})[0][0]
            b = ref_ctx(C, win, cand); wc = max(wc, np.abs(a - b).max()); ag += int(a.argmax() == b.argmax())
        print("ctx max |dlogit| vs ORT:", round(float(wc), 3), "argmax agree", ag, "/20")
