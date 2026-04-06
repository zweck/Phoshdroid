pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Phoshdroid"

include(":app")

// Termux submodule modules — wired in Task 2 after submodules are added
// include(":terminal-emulator")
// project(":terminal-emulator").projectDir = file("termux-app/terminal-emulator")
// include(":termux-shared")
// project(":termux-shared").projectDir = file("termux-app/termux-shared")
// include(":termux-x11-app")
// project(":termux-x11-app").projectDir = file("termux-x11/app")
