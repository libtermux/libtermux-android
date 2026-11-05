import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
    // AGP 9.0+ has built-in Kotlin — kotlin.android must NOT be applied here.
}

android {
    namespace = "com.aeoncorex.aeonruntime.shizuku"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
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
    api(project(":core"))
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}

// ── Maven Publishing ──────────────────────────────────────────────────────────
// (Added during the AeonRuntime migration; mirrors :os / :bootstrap-* config.)
mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant           = "release",
            sourcesJar        = true,
            publishJavadocJar = false,
        )
    )

    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = "aeonruntime-shizuku",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("AeonRuntime Shizuku Module")
        description.set("Optional Shizuku-based elevated command execution for AeonRuntime")
        url.set(project.property("LIB_URL").toString())
        licenses {
            license {
                name.set(project.property("LIB_LICENSE_NAME").toString())
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        developers {
            developer {
                id.set(project.property("LIB_DEVELOPER_ID").toString())
                name.set(project.property("LIB_DEVELOPER_NAME").toString())
            }
        }
        scm { url.set(project.property("LIB_URL").toString()) }
    }
}
