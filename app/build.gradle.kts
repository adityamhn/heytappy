plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// API keys come from an untracked .env at the repo root and are compiled into
// BuildConfig — there is no key-entry UI in the app.
val env: Map<String, String> = rootProject.file(".env").let { file ->
    require(file.exists()) {
        "Missing ${file.absolutePath} — create it with ANTHROPIC_API_KEY " +
            "and DEEPGRAM_API_KEY before building."
    }
    file.readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val key = line.substringBefore('=').trim()
            val value = line.substringAfter('=').trim().removeSurrounding("\"")
            key to value
        }
}

val anthropicApiKey = env["ANTHROPIC_API_KEY"].orEmpty()
val deepgramApiKey = env["DEEPGRAM_API_KEY"].orEmpty()

require(anthropicApiKey.isNotBlank()) { ".env is missing ANTHROPIC_API_KEY" }
require(deepgramApiKey.isNotBlank()) { ".env is missing DEEPGRAM_API_KEY" }

android {
    namespace = "com.agentchat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.agentchat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        buildConfigField("String", "DEEPGRAM_API_KEY", "\"$deepgramApiKey\"")
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/tappy-release.jks")
            storePassword = "tappy-release-2026"
            keyAlias = "tappy"
            keyPassword = "tappy-release-2026"
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate. Keys still live as strings in the DEX, but
            // class/method names and dead code are stripped.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.ui.tooling)
}
