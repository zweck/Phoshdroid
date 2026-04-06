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

// Termux terminal emulator library
include(":terminal-emulator")
project(":terminal-emulator").projectDir = file("termux-app/terminal-emulator")

// Termux terminal view widget (required by termux-shared)
include(":terminal-view")
project(":terminal-view").projectDir = file("termux-app/terminal-view")

// Termux shared utilities
include(":termux-shared")
project(":termux-shared").projectDir = file("termux-app/termux-shared")

// Termux:X11 shell-loader stub (compile-only dep of termux-x11-app)
include(":shell-loader:stub")
project(":shell-loader:stub").projectDir = file("termux-x11/shell-loader/stub")

// Termux:X11 Wayland compositor
include(":termux-x11-app")
project(":termux-x11-app").projectDir = file("termux-x11/app")
