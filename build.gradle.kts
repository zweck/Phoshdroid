plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Make jitpack available to all subprojects (required by termux-shared → hiddenapibypass)
allprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

// Force all Android submodules to compile against SDK 35 so Termux's older
// Groovy build.gradle files (which read compileSdkVersion from project.properties)
// are still overridden to a consistent, up-to-date SDK.
subprojects {
    afterEvaluate {
        extensions.findByType<com.android.build.gradle.BaseExtension>()?.apply {
            compileSdkVersion(35)
        }
    }
}
