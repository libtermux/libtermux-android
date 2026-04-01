plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.libtermux.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.libtermux.sample"
        minSdk        = 26
        targetSdk     = 34
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":terminal-view"))
    
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
