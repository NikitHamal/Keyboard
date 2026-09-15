# ProGuard / R8 Rules for Nepali Keyboard Application

# Preserve IME Service and Components
-keep public class com.nepali.keyboard.NepaliImeService { *; }
-keep public class com.nepali.keyboard.MainActivity { *; }

# Preserve ViewTree Owners to prevent lifecycle crashes in Compose IME
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.lifecycle.ViewTreeViewModelStoreOwner { *; }
-keep class androidx.savedstate.ViewTreeSavedStateRegistryOwner { *; }

# Keep serialized lexicon models and data classes
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.nepali.keyboard.engine.** { *; }

# Preserve Jetpack Compose runtime annotations and keep immutable wrappers
-keepclassmembers class * {
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
}
