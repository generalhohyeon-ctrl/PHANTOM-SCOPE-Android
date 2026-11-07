plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.phantomscope.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phantomscope.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 4
        versionName = "1.7.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
}
