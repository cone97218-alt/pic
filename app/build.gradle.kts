plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stvault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stvault.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 97
        versionName = "1.13.1"

        buildConfigField("String", "GITHUB_REPO", "\"cone97218-alt/pic\"")
    }

    signingConfigs {
        create("vault") {
            storeFile = file("vault-signing.jks")
            storePassword = "stvault2026"
            keyAlias = "stvault"
            keyPassword = "stvault2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("vault")
        }
        release {
            signingConfig = signingConfigs.getByName("vault")
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
