import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---------------------------------------------------------------------------
// Demo signing configuration.
//
// `keystore/release.keystore` is a deliberately public, checked-in demo key.
// Its credentials are intentionally in plain text so that a remote CI build
// can produce a *signed* release APK with zero external secrets.
//
// This key is RESERVED FOR DEMONSTRATION ONLY. It must never be used to sign
// a build distributed on Google Play or any store:
//   * the private key is public, so anybody can forge an update
//   * the key is not hardware-backed
// See AGENTS.md -> "Release signing" before changing this block.
// ---------------------------------------------------------------------------
val demoKeystoreFile = rootProject.file("keystore/release.keystore")
val demoKeystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore/release.keystore.properties")
    if (propsFile.exists()) {
        propsFile.inputStream().use { load(it) }
    }
}

val demoStorePassword: String = demoKeystoreProps.getProperty("storePassword", "nepalikey")
val demoKeyAlias: String = demoKeystoreProps.getProperty("keyAlias", "nepali-keyboard")
val demoKeyPassword: String = demoKeystoreProps.getProperty("keyPassword", "nepalikey")

android {
    namespace = "com.nikit.nepalikeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nikit.nepalikeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Single universal APK (all ABIs, all densities). The project ships no
        // native code, so this is purely about keeping exactly one artifact.
        resourceConfigurations += listOf("en", "ne")
    }

    signingConfigs {
        create("release") {
            storeFile = demoKeystoreFile
            storePassword = demoStorePassword
            keyAlias = demoKeyAlias
            keyPassword = demoKeyPassword

            // v1 is required for API 26 compatibility margins; v2/v3 are the
            // modern schemes. enableV3 enables key rotation metadata.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")

            // The lexicon + font assets are already compact; keep them intact.
            isCrunchPngs = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time / java.util.stream desugaring for API 26.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Aggressive but safe: keep the transliteration hot path monomorphic.
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlin.ExperimentalStdlibApi"
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Ship only the language resources we actually declare.
    androidResources {
        generateLocaleConfig = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json"
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)

    // Lifecycle — required to host a Compose tree inside an InputMethodService.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose — versions come from the BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.runtime)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
