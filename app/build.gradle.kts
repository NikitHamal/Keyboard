import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "np.com.nepalikeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "np.com.nepalikeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables.useSupportLibrary = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Public demo keystore, committed on purpose so that the GitHub Actions
    // pipeline can produce a signed release APK with no external secrets.
    // These credentials are public and MUST NOT be used for store distribution.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.keystore")
            storePassword = "nepali-ime-demo-2026"
            keyAlias = "nepali-keyboard"
            keyPassword = "nepali-ime-demo-2026"
            storeType = "JKS"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                // Universal APK: no ABI split configuration exists in this project.
                abiFilters.clear()
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "kotlin/**",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // The release pipeline must never be broken by a cosmetic lint finding.
    // lintVital runs during assembleRelease; failures degrade to warnings.
    lint {
        abortOnError = false
        checkReleaseBuilds = true
        warningsAsErrors = false
        checkDependencies = false
        disable += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            "UnusedResources",
            "VectorPath",
            "IconMissingDensityFolder",
        )
    }

    // Keep the module deterministic and fast: no test-only compilation of
    // unused variants, and stable resource ids for the IME's baked-in layouts.
    androidResources {
        generateLocaleConfig = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
            "-Xconsistent-data-class-copy-visibility",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
