plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ehcheng.orbit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ehcheng.orbit"
        minSdk = 26
        targetSdk = 35
        versionCode = 22
        versionName = "1.0.2"
    }

    signingConfigs {
        val storePass = System.getenv("ORBIT_STORE_PASSWORD")
        if (storePass != null) {
            create("release") {
                storeFile = file("../orbit-release.jks")
                storePassword = storePass
                keyAlias = "orbit"
                keyPassword = storePass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = try { signingConfigs.getByName("release") } catch (_: Exception) { null }
            signingConfig = releaseSigning
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
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
}
