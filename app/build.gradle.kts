plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.federico.g7monitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.federico.g7monitor"
        minSdk = 31          // Android 12+: modern Bluetooth permissions, no location needed
        targetSdk = 36
        versionCode = rootProject.extra["appVersionCode"] as Int
        versionName = rootProject.extra["appVersionName"] as String
    }

    signingConfigs {
        // Release key from the root build (CI secrets or keystore.properties), when available.
        (rootProject.extra["releaseStoreFile"] as String?)?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = rootProject.extra["releaseStorePassword"] as String
                keyAlias = rootProject.extra["releaseKeyAlias"] as String
                keyPassword = rootProject.extra["releaseKeyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phone and watch must share the key, or the two cannot talk to each other.
            // Without a release key (fresh checkout) the debug key keeps the build installable.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    // Compiles with whatever JDK runs Gradle (21 recommended); no separate JDK 17 needed.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // Only the few icons used end up in the release APK (R8 removes the rest)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    // QR scanner UI provided by Google Play services: no camera permission needed
    implementation(libs.play.services.code.scanner)
    // Wear OS Data Layer: sends the latest reading to the watch complications
    implementation(libs.play.services.wearable)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
