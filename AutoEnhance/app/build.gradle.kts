plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nogy.autoenhance"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nogy.autoenhance"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "4.0"
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
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}
