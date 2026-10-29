import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/*
 * Shared by the phone and watch apps: same version and same key (the Wear OS Data Layer requires it).
 * Version: appVersion in gradle.properties (the release workflow passes -PappVersion from the tag),
 * 1.2.3 -> versionCode 10203. Release key: environment variables on CI, keystore.properties locally
 * (both outside git); without either, release builds use the debug key.
 */
val appVersion: String = providers.gradleProperty("appVersion").get()
extra["appVersionName"] = appVersion
extra["appVersionCode"] = appVersion.split(".").map(String::toInt).let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }

val localKey = Properties().apply { file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load) }
extra["releaseStoreFile"] = System.getenv("G7_KEYSTORE_FILE") ?: localKey.getProperty("storeFile")
extra["releaseStorePassword"] = System.getenv("G7_KEYSTORE_PASSWORD") ?: localKey.getProperty("storePassword")
extra["releaseKeyAlias"] = System.getenv("G7_KEY_ALIAS") ?: localKey.getProperty("keyAlias")
extra["releaseKeyPassword"] = System.getenv("G7_KEYSTORE_PASSWORD") ?: localKey.getProperty("keyPassword")
