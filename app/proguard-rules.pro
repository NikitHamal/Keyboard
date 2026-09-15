# =============================================================================
# Nepali Keyboard — R8 / ProGuard rules
# =============================================================================

# -----------------------------------------------------------------------------
# 1. IME SERVICE ENTRIES
# -----------------------------------------------------------------------------
# Android instantiates the InputMethodService by reflection from the manifest
# <service android:name="..."> declaration. If R8 renames the class or strips
# its public no-arg constructor, the IME silently disappears from the picker.
-keep class com.nikit.nepalikeyboard.ime.NepaliImeService { *; }
-keep class * extends android.inputmethodservice.InputMethodService { *; }

# Keep every subclass of the framework entry points that Android reflectively
# loads (Application, ContentProvider, BroadcastReceiver, Service, Activity).
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.Activity
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# -----------------------------------------------------------------------------
# 2. SERIALIZED LEXICON MODELS
# -----------------------------------------------------------------------------
# kotlinx.serialization resolves serializers reflectively for @Serializable
# classes. R8 must not rename the synthetic $$serializer or the Companion,
# and must not strip the constructor/members used by generated codecs.
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.nikit.nepalikeyboard.**$$serializer { *; }
-keepclassmembers class com.nikit.nepalikeyboard.** {
    *** Companion;
}
-keepclasseswithmembers class com.nikit.nepalikeyboard.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Explicitly pin the lexicon asset DTOs. These are the exact classes that are
# decoded from `assets/dict/*.json`; renaming them breaks deserialization at
# runtime in a way that only shows up after enabling minification.
-keep class com.nikit.nepalikeyboard.lexicon.model.LexiconAsset { *; }
-keep class com.nikit.nepalikeyboard.lexicon.model.LexiconEntry { *; }
-keep class com.nikit.nepalikeyboard.lexicon.model.BigramEntry { *; }
-keep class com.nikit.nepalikeyboard.lexicon.model.LexiconMetadata { *; }

# The generic Json { } serializer lookup uses the fully-qualified class name.
-keepnames class com.nikit.nepalikeyboard.lexicon.model.**

# -----------------------------------------------------------------------------
# 3. KOTLIN METADATA & COROUTINES
# -----------------------------------------------------------------------------
# Kotlin reflection / metadata needs these to resolve suspend functions and
# @Composable targets correctly.
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Lazy {
    <fields>;
}
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# Keep lambda / singleton INSTANCE fields that Kotlin objects rely on.
-keepclassmembers class ** {
    public static final ** INSTANCE;
}

# -----------------------------------------------------------------------------
# 4. COMPOSE
# -----------------------------------------------------------------------------
# Compose runtime performs reflective lookups on @Composable function groups
# and on the Composer's change tracking. Stripping these causes silent
# composition glitches rather than build failures.
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }
-keepclassmembers class androidx.compose.runtime.** {
    <methods>;
}
-keep class androidx.compose.ui.platform.** { *; }
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# ViewTree*Owner setters are looked up by name through extension functions.
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { public static void set*(...); }
-keep class androidx.lifecycle.ViewTreeViewModelStoreOwner { public static void set*(...); }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { public static void set*(...); }

# -----------------------------------------------------------------------------
# 5. VIEW BINDING / BINARY-XML INFLATION
# -----------------------------------------------------------------------------
-keep public class * extends android.view.View {
    public <init>(...);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# -----------------------------------------------------------------------------
# 6. ENUM / PARCELABLE / GENERIC HYGIENE
# -----------------------------------------------------------------------------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class **.R$* {
    public static <fields>;
}

# -----------------------------------------------------------------------------
# 7. OPTIMISATION TUNING FOR THE KEYPRESS HOT PATH
# -----------------------------------------------------------------------------
# Keep the phonetic tables as real objects rather than constant-folding them
# into enormous <clinit> blobs (which blows the 64K per-method dex limit).
-keepclassmembers class com.nikit.nepalikeyboard.translit.** {
    <fields>;
}
-dontnote kotlinx.serialization.**
-dontwarn org.jetbrains.annotations.**
