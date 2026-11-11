# TinyLM JVM check

Compiles `tlm/TlmEngine.kt` on a plain JVM (no Android) and runs the engine against the bundled assets, so the Kotlin
port can be verified without a device: it prints the top chips, Padayog joins, and compares incremental IME-style
typing against bulk `transliterate()`.

```
# needs JDK 17+, kotlinc 2.1, onnxruntime-1.20.0.jar and org.json:json jars on the classpath
kotlinc -cp onnxruntime-1.20.0.jar:json-20231013.jar app/src/main/java/np/com/nepalikeyboard/tlm/TlmEngine.kt \
        tools/tlm_jvm_check/Harness.kt -d out
java -Dfile.encoding=UTF-8 -cp "out:<kotlin-stdlib.jar>:onnxruntime-1.20.0.jar:json-20231013.jar" HarnessKt
```
Measured on a 2-vCPU server: model + lexicon load 1.5 s, ~10 ms per keystroke (steady state, includes stage-2).
