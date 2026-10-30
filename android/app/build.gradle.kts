plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: CI passes these from GitHub secrets. Without them the
// bundled key is used so every build (local or CI) can update the last one.
val keystoreFile = System.getenv("AR_KEYSTORE_FILE") ?: "ar-remote.jks"
val keystorePass = System.getenv("AR_KEYSTORE_PASSWORD") ?: "arremote"
val keyAliasName = System.getenv("AR_KEY_ALIAS") ?: "arremote"
val keyPass = System.getenv("AR_KEY_PASSWORD") ?: "arremote"

val appVersionName = System.getenv("AR_VERSION_NAME") ?: "2.6.0"
// Android refuses an update whose versionCode is lower than the installed one
// ("version downgrade"), so the code comes from the version itself:
// 2.3.3 -> 20303xxx, where xxx is the CI run number (so rebuilds of the same
// version still go up). A higher version always gets a higher code, whatever
// the run number.
val appVersionCode: Int = run {
    val run = (System.getenv("AR_VERSION_CODE") ?: "0").toIntOrNull() ?: 0
    val parts = appVersionName.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
    parts[0] * 10_000_000 + parts[1] * 100_000 + parts[2] * 1_000 + run % 1_000
}

android {
    namespace = "com.arsmarthome.remote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arsmarthome.remote"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreFile)
            storePassword = keystorePass
            keyAlias = keyAliasName
            keyPassword = keyPass
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
    }
}
