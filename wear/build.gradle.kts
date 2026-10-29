plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "it.federico.g7monitor.wear"
    compileSdk = 36

    defaultConfig {
        // Same application id (and signing key) as the phone app: required by the Wear OS Data Layer.
        applicationId = "it.federico.g7monitor"
        minSdk = 30          // Wear OS 3+ (Galaxy Watch 4 and later)
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.wear.compose.material)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.complications.data.source)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.android)
}
