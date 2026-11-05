plugins {
    alias(libs.plugins.android.application)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
}

android {
    namespace = "com.aeoncorex.aeonruntime.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aeoncorex.aeonruntime.sample"
        minSdk        = 26
        // Termux-style: exec() of files extracted into app-private storage is
        // only allowed for targetSdk <= 28 (W^X restriction from API 29).
        targetSdk     = 28
        versionCode   = 1
        versionName   = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        // Intentional: see targetSdk comment above.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal-view"))
    // Embeds the Termux bootstrap zip as an asset (extracted on first run).
    // Real apps should add every ABI they ship for (bootstrap-arm,
    // bootstrap-x86_64, bootstrap-x86 alongside this).
    implementation(project(":bootstrap-arm64"))
    
    // UI Dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)                         // ✅ Now defined
    implementation(libs.androidx.constraintlayout)        // ✅ Now defined
    
    // Lifecycle & Coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)   // ✅ Now defined
    implementation(libs.androidx.lifecycle.viewmodel.ktx) // ✅ Now defined
    implementation(libs.androidx.activity.ktx)            // ✅ Now defined
    implementation(libs.kotlinx.coroutines.android)
}
