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

val appVersionName = System.getenv("AR_VERSION_NAME") ?: "2.3.2"
val appVersionCode = (System.getenv("AR_VERSION_CODE") ?: "1").toInt()

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
