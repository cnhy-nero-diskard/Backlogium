import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Read Steam credentials from local.properties (git-ignored). Falls back to empty
// strings so the project always builds; the app surfaces a "Steam not configured"
// state at runtime when either value is blank.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val steamApiKey: String = localProperties.getProperty("steam.apiKey", "").trim()
val steamId: String = localProperties.getProperty("steam.steamId", "").trim()

val buildVersionName: String = providers.gradleProperty("versionName").orNull ?: "0.0.0-dev"
val suppliedVersionCode: String? = providers.gradleProperty("versionCode").orNull
val buildVersionCode: Int = suppliedVersionCode?.toIntOrNull()
    ?: if (suppliedVersionCode == null) {
        1
    } else {
        error("versionCode must be an integer, but was '$suppliedVersionCode'")
    }

// Release signing: env vars take precedence (CI), falling back to local.properties
// (git-ignored) for local release builds. Left unconfigured, `release` builds stay
// unsigned and will fail to install on-device — see keystore/README.md.
val releaseStoreFile: String = (System.getenv("RELEASE_KEYSTORE_PATH")
    ?: localProperties.getProperty("release.storeFile", "")).trim()
val releaseStorePassword: String = (System.getenv("RELEASE_KEYSTORE_PASSWORD")
    ?: localProperties.getProperty("release.storePassword", "")).trim()
val releaseKeyAlias: String = (System.getenv("RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("release.keyAlias", "")).trim()
val releaseKeyPassword: String = (System.getenv("RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("release.keyPassword", "")).trim()
val hasReleaseSigningConfig = releaseStoreFile.isNotBlank() &&
    releaseStorePassword.isNotBlank() &&
    releaseKeyAlias.isNotBlank() &&
    releaseKeyPassword.isNotBlank()

android {
    namespace = "com.example.backlogium"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.backlogium"
        minSdk = 33
        targetSdk = 36
        versionCode = buildVersionCode
        versionName = buildVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Distinct installed identity so debug installs side by side with the signed
            // release app (separate data, permissions, notifications, and WorkManager
            // state). The code namespace stays `com.example.backlogium`.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "STEAM_API_KEY", "\"$steamApiKey\"")
            buildConfigField("String", "STEAM_ID", "\"$steamId\"")
        }
        release {
            buildConfigField("String", "STEAM_API_KEY", "\"\"")
            buildConfigField("String", "STEAM_ID", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }
    lint {
        baseline = file("lint.baseline")
    }
}

dependencies {
    implementation(project(":gamification"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // ProcessLifecycleOwner — the app-foreground presence re-check (fix-live-status-detection)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Image loading
    implementation(libs.coil.compose)

    // Visual identity (restyle-visual-identity)
    implementation(libs.lottie.compose)
    implementation(libs.compose.icons.tabler)

    // Freeform logging facade (add-sync-diagnostics)
    implementation(libs.timber)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
