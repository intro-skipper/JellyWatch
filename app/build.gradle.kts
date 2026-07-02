import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
val signingKeyAlias = System.getenv("KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
val hasSigningInfo = !keystorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    rootProject.file("signing/release.keystore").exists()

android {
    namespace = "com.jellywatch.client"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jellywatch.client"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
    }

    signingConfigs {
        create("release") {
            if (hasSigningInfo) {
                storeFile = rootProject.file("signing/release.keystore")
                storePassword = keystorePassword
                keyAlias = signingKeyAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            if (hasSigningInfo) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (hasSigningInfo) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
