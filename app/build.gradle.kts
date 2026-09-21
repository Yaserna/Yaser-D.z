plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.privatemsg.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.privatemsg.app"
        minSdk = 24
        targetSdk = 34
        // Auto-bump per CI build so every release is a distinct, newer version
        // (the phone always sees it as an update; the number shows in Settings).
        val ciRun = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
        versionCode = 11 + ciRun
        versionName = "1.2.9"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    signingConfigs {
        create("shared") {
            storeFile = file("keystore.jks")
            storePassword = "android"
            keyAlias = "shared"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.biometric:biometric:1.1.0")
}
