# TinyLM JVM check

Compiles the pure-Kotlin TinyLM engine (`tlm/TlmEngine.kt`, `tlm/TlmNet.kt`, `tlm/TlmLoader.kt` - no Android, no ONNX)
on a plain JVM and runs it against the bundled assets, so the port can be verified without a device or an APK build.
It prints IME-style incremental output next to bulk `transliterate()` output and the average per-keystroke latency.

```
# needs JDK 17+, kotlinc 2.1 and org.json:json-20231013.jar
kotlinc -cp json-20231013.jar app/src/main/java/np/com/nepalikeyboard/tlm/Tlm*.kt tools/tlm_jvm_check/Harness.kt -d out
java -Dfile.encoding=UTF-8 -cp "out:<kotlin-stdlib.jar>:json-20231013.jar" HarnessKt
```

Parity: the output is byte-identical to the previous onnxruntime-based build on the 13-sentence smoke set.
Weights are packed from the upstream int8 ONNX files with `tools/pack_tlm.py` (`--check` compares against onnxruntime).
