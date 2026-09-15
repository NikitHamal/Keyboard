# ---------------------------------------------------------------------------
# Nepali Keyboard - R8 / ProGuard rules
#
# The two things R8 must never remove:
#   1. The InputMethodService subclasses and their lifecycle entry points
#      (instantiated reflectively by the system).
#   2. The @Serializable lexicon / learning / clipboard models, whose generated
#      serializers are looked up reflectively by the kotlinx.serialization
#      runtime. Stripping them silently corrupts offline dictionaries.
# ---------------------------------------------------------------------------

# ---- IME entry points -------------------------------------------------------
-keep class np.com.nepalikeyboard.ime.NepaliImeService { *; }
-keep public class * extends android.inputmethodservice.InputMethodService {
    public <init>(...);
    protected void on*();
    public void on*();
}
-keep public class * extends android.app.Service {
    public <init>(...);
}
-keep public class * extends android.app.Activity {
    public <init>(...);
}

# Views inflated/found by name and the Compose host container.
-keep class np.com.nepalikeyboard.ime.ComposeOwnerBridge { *; }

# ---- kotlinx.serialization --------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault, EnclosingMethod
-keep,includedescriptorclasses class np.com.nepalikeyboard.**$$serializer { *; }
-keepclassmembers class np.com.nepalikeyboard.** {
    *** Companion;
}
-keepclasseswithmembers class np.com.nepalikeyboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class np.com.nepalikeyboard.** { *; }

# Serialization runtime reflection helpers.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---- Lexicon & engine -------------------------------------------------------
# The trie, lexicon loader and phonetic tables are pure Kotlin; keep their
# public surface so the async candidate engine cannot be inlined into a
# stripped-out stub.
-keep class np.com.nepalikeyboard.engine.** { *; }
-keepclassmembers class np.com.nepalikeyboard.engine.** {
    public *;
    *** Companion;
}

# ---- Compose ----------------------------------------------------------------
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ---- Coroutines -------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Misc -------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
