/**
 * AeonRuntime — bootstrap-x86
 * Copyright (c) 2026 AeonCoreX-Lab / cybernahid-dev.
 *
 * Ships the official Termux bootstrap zip for "i686" as an Android asset
 * (aeonruntime/bootstrap-i686.zip), Termux-style: :core extracts it into the
 * prefix on first run, no network needed on the device. See
 * build-logic/bootstrap-fetch BootstrapFetchPlugin.
 */
import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("aeonruntime.bootstrap-module")
    id("com.aeoncorex.aeonruntime.bootstrap-fetch")
    alias(libs.plugins.maven.publish)
}

bootstrapFetch {
    bootstrapArch.set("i686")
    bootstrapTag.set("bootstrap-2026.05.24-r1+apt.android-7")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            variant           = "release",
            sourcesJar        = false,
            publishJavadocJar = false,
        )
    )
    publishToMavenCentral(SonatypeHost.S01)
    signAllPublications()

    coordinates(
        groupId    = project.property("LIB_GROUP_ID").toString(),
        artifactId = "aeonruntime-bootstrap-x86",
        version    = project.property("LIB_VERSION").toString(),
    )

    pom {
        name.set("AeonRuntime Bootstrap (x86)")
        description.set("Termux bootstrap binaries for x86, packaged for use with AeonRuntime")
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
        scm {
            url.set(project.property("LIB_URL").toString())
        }
    }
}
