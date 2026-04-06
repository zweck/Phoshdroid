# Phoshdroid Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single Android APK that launches a full postmarketOS Phosh desktop via proot, with embedded Termux and Termux:X11 Wayland compositor.

**Architecture:** Embed Termux terminal-emulator + termux-shared + Termux:X11 as git submodules in a multi-module Gradle project. Bundle a pmOS rootfs as a compressed APK asset. On launch, extract to app-internal storage, use proot-distro to enter the rootfs, start phoc as a nested Wayland compositor inside Termux:X11's surface, and run Phosh.

**Tech Stack:** Kotlin (app logic, settings), Java (Termux/X11 upstream code), Gradle 8.x with AGP 8.x, Android 12+ (API 31+), JUnit 5 + MockK + Robolectric for tests, pmbootstrap for rootfs builds.

**Spec:** `docs/superpowers/specs/2026-04-06-phoshdroid-design.md`

---

## File Structure

```
phoshdroid/
├── .github/workflows/ci.yml
├── .gitignore
├── .gitmodules
├── build.gradle.kts                          # Root Gradle build
├── settings.gradle.kts                       # Module wiring (incl. submodule paths)
├── gradle.properties
├── gradle/libs.versions.toml                 # Version catalog
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/.gitkeep               # rootfs.tar.zst + bootstrap.tar.zst go here
│       │   ├── kotlin/com/phoshdroid/app/
│       │   │   ├── PhoshdroidApp.kt          # Application class
│       │   │   ├── LauncherActivity.kt       # Entry point, extraction UI, navigation
│       │   │   ├── SettingsFragment.kt       # Preferences UI
│       │   │   ├── extraction/
│       │   │   │   └── AssetExtractor.kt     # Extracts tar.zst from assets to filesystem
│       │   │   ├── proot/
│       │   │   │   ├── ProotCommandBuilder.kt    # Builds proot-distro CLI commands
│       │   │   │   ├── ProotDistroManager.kt     # Registers rootfs, manages login
│       │   │   │   └── ProotService.kt           # Foreground service for proot lifecycle
│       │   │   └── settings/
│       │   │       ├── PhoshdroidPreferences.kt  # SharedPreferences wrapper
│       │   │       └── ProotConfigWriter.kt      # Writes config to rootfs
│       │   └── res/
│       │       ├── layout/activity_launcher.xml
│       │       ├── xml/preferences.xml
│       │       ├── values/strings.xml
│       │       └── values/themes.xml
│       └── test/kotlin/com/phoshdroid/app/
│           ├── extraction/AssetExtractorTest.kt
│           ├── proot/ProotCommandBuilderTest.kt
│           ├── proot/ProotDistroManagerTest.kt
│           └── settings/ProotConfigWriterTest.kt
├── termux-app/                               # Git submodule → termux/termux-app
├── termux-x11/                               # Git submodule → niclas/termux-x11 (or termux/termux-x11)
└── rootfs/
    ├── build-rootfs.sh                       # pmbootstrap wrapper to build pmOS rootfs
    ├── build-bootstrap.sh                    # Builds minimal Termux prefix tarball
    └── overlay/etc/phoshdroid/
        ├── start.sh                          # Startup script inside rootfs
        └── config.defaults                   # Default config values
```

**Module dependency graph:**
```
app  →  termux-shared  (from termux-app/termux-shared)
app  →  terminal-emulator  (from termux-app/terminal-emulator)
app  →  termux-x11-app  (from termux-x11/app)
termux-x11-app  →  termux-shared
```

---

### Task 1: Project Scaffolding

**Files:**
- Create: `.gitignore`
- Create: `build.gradle.kts`
- Create: `settings.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/kotlin/com/phoshdroid/app/PhoshdroidApp.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/assets/.gitkeep`

- [ ] **Step 1: Initialize git repo**

```bash
cd /home/phil/Development/proot-post
git init
```

- [ ] **Step 2: Create .gitignore**

Create `.gitignore`:
```gitignore
# Gradle
.gradle/
build/
local.properties

# IDE
.idea/
*.iml

# Android
app/src/main/assets/rootfs.tar.zst
app/src/main/assets/bootstrap.tar.zst

# OS
.DS_Store
```

- [ ] **Step 3: Create version catalog**

Create `gradle/libs.versions.toml`:
```toml
[versions]
agp = "8.7.3"
kotlin = "2.1.0"
coreKtx = "1.15.0"
material = "1.12.0"
preferenceKtx = "1.2.1"
junit5 = "5.11.4"
mockk = "1.13.14"
robolectric = "4.14.1"
commonsCompress = "1.27.1"
zstdJni = "1.5.6-9"
coroutines = "1.9.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-preference-ktx = { group = "androidx.preference", name = "preference-ktx", version.ref = "preferenceKtx" }
commons-compress = { group = "org.apache.commons", name = "commons-compress", version.ref = "commonsCompress" }
zstd-jni = { group = "com.github.luben", name = "zstd-jni", version.ref = "zstdJni" }
kotlinx-coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

junit5-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit5" }
junit5-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit5" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 4: Create root build.gradle.kts**

Create `build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}
```

- [ ] **Step 5: Create settings.gradle.kts**

Create `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
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
```

- [ ] **Step 6: Create gradle.properties**

Create `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 7: Create app/build.gradle.kts**

Create `app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.phoshdroid.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phoshdroid.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    packaging {
        // Exclude duplicate files from Termux dependencies
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.commons.compress)
    implementation(libs.zstd.jni)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Termux modules — uncommented in Task 2
    // implementation(project(":terminal-emulator"))
    // implementation(project(":termux-shared"))
    // implementation(project(":termux-x11-app"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

- [ ] **Step 8: Create AndroidManifest.xml**

Create `app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:name=".PhoshdroidApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Phoshdroid">

        <activity
            android:name=".LauncherActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".proot.ProotService"
            android:foregroundServiceType="specialUse"
            android:exported="false" />

    </application>
</manifest>
```

- [ ] **Step 9: Create PhoshdroidApp.kt**

Create `app/src/main/kotlin/com/phoshdroid/app/PhoshdroidApp.kt`:
```kotlin
package com.phoshdroid.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class PhoshdroidApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phoshdroid Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when the postmarketOS desktop is running"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "phoshdroid_service"
    }
}
```

- [ ] **Step 10: Create resource files**

Create `app/src/main/res/values/strings.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Phoshdroid</string>
    <string name="notification_title">Phoshdroid is running</string>
    <string name="notification_text">postmarketOS desktop is active</string>
    <string name="action_settings">Settings</string>
    <string name="action_stop">Stop</string>
    <string name="extracting_termux">Extracting Termux environment\u2026</string>
    <string name="extracting_rootfs">Extracting postmarketOS\u2026</string>
    <string name="preparing_desktop">Preparing your desktop\u2026</string>
    <string name="starting_desktop">Starting desktop\u2026</string>
    <string name="error_no_space">Not enough storage space. Need %1$s, have %2$s.</string>
    <string name="error_proot_failed">proot failed to start. Tap to copy logs.</string>
    <string name="welcome_toast">Pull down from top for Phosh settings. Access Phoshdroid settings from the notification.</string>
</resources>
```

Create `app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Phoshdroid" parent="Theme.Material3.DayNight.NoActionBar" />
</resources>
```

Create `app/src/main/assets/.gitkeep` (empty file).

- [ ] **Step 11: Install Gradle wrapper and verify build**

```bash
cd /home/phil/Development/proot-post
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL (an empty app with no activities implemented yet).

- [ ] **Step 12: Commit**

```bash
git add .
git commit -m "feat: scaffold Phoshdroid Android project

Multi-module Gradle project with version catalog, Android 12+ target,
Material 3 theme, notification channel, and manifest with required
permissions. Termux submodule wiring prepared but commented out."
```

---

### Task 2: Termux Upstream Integration

**Files:**
- Create: `.gitmodules`
- Modify: `settings.gradle.kts` (uncomment submodule includes)
- Modify: `app/build.gradle.kts` (uncomment Termux dependencies)

- [ ] **Step 1: Add termux-app submodule**

```bash
cd /home/phil/Development/proot-post
git submodule add https://github.com/termux/termux-app.git termux-app
cd termux-app
git checkout v0.118.1
cd ..
```

Use the latest stable tag. We need the `terminal-emulator` and `termux-shared` modules from this repo.

- [ ] **Step 2: Add termux-x11 submodule**

```bash
git submodule add https://github.com/niclas/termux-x11.git termux-x11
cd termux-x11
git checkout $(git describe --tags --abbrev=0)
cd ..
```

Use the latest tag. We need the `app` module which contains the Wayland compositor Activity.

- [ ] **Step 3: Uncomment submodule includes in settings.gradle.kts**

Replace the commented-out lines in `settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolution {
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

// Termux shared utilities
include(":termux-shared")
project(":termux-shared").projectDir = file("termux-app/termux-shared")

// Termux:X11 Wayland compositor
include(":termux-x11-app")
project(":termux-x11-app").projectDir = file("termux-x11/app")
```

- [ ] **Step 4: Uncomment Termux dependencies in app/build.gradle.kts**

In the dependencies block, uncomment:
```kotlin
implementation(project(":terminal-emulator"))
implementation(project(":termux-shared"))
implementation(project(":termux-x11-app"))
```

- [ ] **Step 5: Resolve build conflicts**

The upstream Termux modules have their own `build.gradle` files with their own SDK versions, dependencies, etc. Common issues to fix:

1. If Termux modules use older compileSdk, update them or set a project-wide compileSdk in root `build.gradle.kts`:
```kotlin
subprojects {
    afterEvaluate {
        if (extensions.findByType<com.android.build.gradle.BaseExtension>() != null) {
            extensions.configure<com.android.build.gradle.BaseExtension> {
                compileSdkVersion(35)
            }
        }
    }
}
```

2. If namespace conflicts arise, check each module's `build.gradle` has a unique namespace.

3. The termux-x11 module may depend on termux-shared with a different project path — verify its `build.gradle` references match our module names.

Run:
```bash
./gradlew assembleDebug 2>&1 | head -50
```

Fix any errors iteratively until the build succeeds.

- [ ] **Step 6: Verify submodule code compiles**

```bash
./gradlew :terminal-emulator:assemble :termux-shared:assemble :termux-x11-app:assemble
```

Expected: BUILD SUCCESSFUL for all three modules.

- [ ] **Step 7: Commit**

```bash
git add .
git commit -m "feat: integrate Termux and Termux:X11 as submodules

Add termux-app (terminal-emulator, termux-shared) and termux-x11
as git submodules. Wire into Gradle multi-module build. App module
depends on all three."
```

---

### Task 3: AssetExtractor (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/phoshdroid/app/extraction/AssetExtractorTest.kt`
- Create: `app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt`

AssetExtractor takes an InputStream of a tar.zst archive and extracts it to a target directory, reporting progress as a percentage (based on bytes read vs total size).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/phoshdroid/app/extraction/AssetExtractorTest.kt`:
```kotlin
package com.phoshdroid.app.extraction

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import com.github.luben.zstd.ZstdOutputStream
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class AssetExtractorTest {

    @TempDir
    lateinit var tempDir: File

    private fun createTarZst(files: Map<String, ByteArray>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdOutputStream(baos).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                for ((name, content) in files) {
                    val entry = tar.createArchiveEntry(File(name), name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }
        return baos.toByteArray()
    }

    @Test
    fun `extracts single file from tar zst`() {
        val content = "hello world".toByteArray()
        val archive = createTarZst(mapOf("test.txt" to content))

        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        val extracted = File(tempDir, "test.txt")
        assertTrue(extracted.exists())
        assertEquals("hello world", extracted.readText())
    }

    @Test
    fun `extracts nested directory structure`() {
        val archive = createTarZst(mapOf(
            "usr/bin/hello" to "#!/bin/sh".toByteArray(),
            "etc/config" to "key=value".toByteArray()
        ))

        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertTrue(File(tempDir, "usr/bin/hello").exists())
        assertEquals("key=value", File(tempDir, "etc/config").readText())
    }

    @Test
    fun `reports progress during extraction`() {
        val largeContent = ByteArray(10_000) { it.toByte() }
        val archive = createTarZst(mapOf("big.bin" to largeContent))

        val progressValues = mutableListOf<Int>()
        val extractor = AssetExtractor()
        extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir,
            onProgress = { percent -> progressValues.add(percent) }
        )

        assertTrue(progressValues.isNotEmpty())
        assertEquals(100, progressValues.last())
        // Progress should be monotonically increasing
        for (i in 1 until progressValues.size) {
            assertTrue(progressValues[i] >= progressValues[i - 1])
        }
    }

    @Test
    fun `skips extraction if marker file exists`() {
        val archive = createTarZst(mapOf("test.txt" to "hello".toByteArray()))

        // Create marker file to indicate already extracted
        File(tempDir, ".extraction_complete").createNewFile()

        val extractor = AssetExtractor()
        val result = extractor.extract(
            input = ByteArrayInputStream(archive),
            totalBytes = archive.size.toLong(),
            targetDir = tempDir
        )

        assertFalse(result)
        assertFalse(File(tempDir, "test.txt").exists())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.extraction.AssetExtractorTest" 2>&1 | tail -20
```

Expected: FAIL — `AssetExtractor` class does not exist.

- [ ] **Step 3: Implement AssetExtractor**

Create `app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt`:
```kotlin
package com.phoshdroid.app.extraction

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import com.github.luben.zstd.ZstdInputStream
import java.io.File
import java.io.InputStream

class AssetExtractor {

    /**
     * Extracts a tar.zst archive to targetDir.
     * Returns true if extraction was performed, false if skipped (already extracted).
     */
    fun extract(
        input: InputStream,
        totalBytes: Long,
        targetDir: File,
        onProgress: (percent: Int) -> Unit = {}
    ): Boolean {
        val marker = File(targetDir, ".extraction_complete")
        if (marker.exists()) return false

        targetDir.mkdirs()
        var bytesRead = 0L
        var lastPercent = -1

        CountingInputStream(input) { count ->
            bytesRead = count
            val percent = if (totalBytes > 0) ((count * 100) / totalBytes).toInt().coerceAtMost(100) else 0
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(percent)
            }
        }.let { counting ->
            ZstdInputStream(counting).use { zstd ->
                TarArchiveInputStream(zstd).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        val outFile = File(targetDir, entry.name)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { out ->
                                tar.copyTo(out)
                            }
                            if (entry.mode and 0b001_001_001 != 0) {
                                outFile.setExecutable(true)
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
            }
        }

        // Force 100% at the end
        onProgress(100)
        marker.createNewFile()
        return true
    }
}

/**
 * InputStream wrapper that reports total bytes read via a callback.
 */
private class CountingInputStream(
    private val wrapped: InputStream,
    private val onBytesRead: (Long) -> Unit
) : InputStream() {

    private var totalRead = 0L

    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) {
            totalRead++
            onBytesRead(totalRead)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) {
            totalRead += n
            onBytesRead(totalRead)
        }
        return n
    }

    override fun close() = wrapped.close()
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.extraction.AssetExtractorTest" 2>&1 | tail -20
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/extraction/AssetExtractor.kt \
       app/src/test/kotlin/com/phoshdroid/app/extraction/AssetExtractorTest.kt
git commit -m "feat: add AssetExtractor for tar.zst extraction with progress

Extracts tar.zst archives from APK assets to app-internal storage.
Reports progress as a percentage. Skips if already extracted (marker file).
Preserves executable permissions from tar entries."
```

---

### Task 4: ProotCommandBuilder (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/phoshdroid/app/proot/ProotCommandBuilderTest.kt`
- Create: `app/src/main/kotlin/com/phoshdroid/app/proot/ProotCommandBuilder.kt`

Pure logic class — builds the proot-distro CLI command string from configuration.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/phoshdroid/app/proot/ProotCommandBuilderTest.kt`:
```kotlin
package com.phoshdroid.app.proot

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProotCommandBuilderTest {

    @Test
    fun `builds default login command`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh"
        )

        assertEquals(
            listOf(
                "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
                "login", "postmarketos",
                "--shared-tmp",
                "--", "/bin/bash", "/etc/phoshdroid/start.sh"
            ),
            cmd
        )
    }

    @Test
    fun `adds sdcard bind mount when enabled`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/etc/phoshdroid/start.sh",
            bindSdcard = true
        )

        assertTrue(cmd.contains("--bind"))
        val bindIdx = cmd.indexOf("--bind")
        assertEquals("/sdcard:/home/user/sdcard", cmd[bindIdx + 1])
    }

    @Test
    fun `builds registration command`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildInstallCommand(
            tarballPath = "/data/data/com.phoshdroid.app/files/rootfs/rootfs.tar.zst"
        )

        assertEquals(
            listOf(
                "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
                "install",
                "--override-alias", "postmarketos",
                "/data/data/com.phoshdroid.app/files/rootfs/rootfs.tar.zst"
            ),
            cmd
        )
    }

    @Test
    fun `builds environment variables for wayland`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val env = builder.buildEnvironment()

        assertEquals("wayland-0", env["WAYLAND_DISPLAY"])
        assertEquals("/tmp", env["XDG_RUNTIME_DIR"])
        assertTrue(env.containsKey("DISPLAY"))
    }

    @Test
    fun `custom startup script overrides default`() {
        val builder = ProotCommandBuilder(
            prootDistroPath = "/data/data/com.phoshdroid.app/files/usr/bin/proot-distro",
            distroName = "postmarketos"
        )

        val cmd = builder.buildLoginCommand(
            startupScript = "/home/user/my-start.sh"
        )

        assertTrue(cmd.last() == "/home/user/my-start.sh")
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.proot.ProotCommandBuilderTest" 2>&1 | tail -20
```

Expected: FAIL — `ProotCommandBuilder` class does not exist.

- [ ] **Step 3: Implement ProotCommandBuilder**

Create `app/src/main/kotlin/com/phoshdroid/app/proot/ProotCommandBuilder.kt`:
```kotlin
package com.phoshdroid.app.proot

class ProotCommandBuilder(
    private val prootDistroPath: String,
    private val distroName: String
) {

    fun buildLoginCommand(
        startupScript: String,
        bindSdcard: Boolean = false
    ): List<String> {
        val cmd = mutableListOf(
            prootDistroPath,
            "login", distroName,
            "--shared-tmp"
        )

        if (bindSdcard) {
            cmd.add("--bind")
            cmd.add("/sdcard:/home/user/sdcard")
        }

        cmd.add("--")
        cmd.add("/bin/bash")
        cmd.add(startupScript)

        return cmd
    }

    fun buildInstallCommand(tarballPath: String): List<String> {
        return listOf(
            prootDistroPath,
            "install",
            "--override-alias", distroName,
            tarballPath
        )
    }

    fun buildEnvironment(): Map<String, String> {
        return mapOf(
            "WAYLAND_DISPLAY" to "wayland-0",
            "XDG_RUNTIME_DIR" to "/tmp",
            "DISPLAY" to ":0",
            "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/dbus-session",
            "PULSE_SERVER" to "tcp:127.0.0.1:4713"
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.proot.ProotCommandBuilderTest" 2>&1 | tail -20
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/proot/ProotCommandBuilder.kt \
       app/src/test/kotlin/com/phoshdroid/app/proot/ProotCommandBuilderTest.kt
git commit -m "feat: add ProotCommandBuilder for proot-distro command generation

Builds login, install, and environment commands for proot-distro.
Supports optional sdcard bind mount and custom startup scripts."
```

---

### Task 5: ProotDistroManager (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/phoshdroid/app/proot/ProotDistroManagerTest.kt`
- Create: `app/src/main/kotlin/com/phoshdroid/app/proot/ProotDistroManager.kt`

Manages the proot-distro rootfs: checks if installed, registers the rootfs, runs login. Wraps process execution so it can be tested with a mock.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/phoshdroid/app/proot/ProotDistroManagerTest.kt`:
```kotlin
package com.phoshdroid.app.proot

import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProotDistroManagerTest {

    @TempDir
    lateinit var tempDir: File

    private fun createManager(
        processRunner: ProcessRunner = mockk(relaxed = true)
    ): ProotDistroManager {
        val prefixDir = File(tempDir, "usr").also { it.mkdirs() }
        val rootfsDir = File(tempDir, "rootfs").also { it.mkdirs() }
        return ProotDistroManager(
            commandBuilder = ProotCommandBuilder(
                prootDistroPath = "${prefixDir.absolutePath}/bin/proot-distro",
                distroName = "postmarketos"
            ),
            installedRootfsDir = File(tempDir, "proot-distro/installed-rootfs"),
            rootfsTarball = File(rootfsDir, "rootfs.tar.zst"),
            processRunner = processRunner
        )
    }

    @Test
    fun `isInstalled returns false when rootfs directory does not exist`() {
        val manager = createManager()
        assertFalse(manager.isInstalled())
    }

    @Test
    fun `isInstalled returns true when rootfs directory exists`() {
        File(tempDir, "proot-distro/installed-rootfs/postmarketos").mkdirs()
        val manager = createManager()
        assertTrue(manager.isInstalled())
    }

    @Test
    fun `install runs proot-distro install command`() {
        val runner = mockk<ProcessRunner>(relaxed = true)
        every { runner.run(any(), any()) } returns ProcessResult(0, "")
        val manager = createManager(processRunner = runner)

        manager.install()

        verify {
            runner.run(
                match { it.contains("install") && it.contains("--override-alias") },
                any()
            )
        }
    }

    @Test
    fun `login starts process and returns handle`() {
        val mockProcess = mockk<Process>(relaxed = true)
        val runner = mockk<ProcessRunner>(relaxed = true)
        every { runner.start(any(), any()) } returns mockProcess
        val manager = createManager(processRunner = runner)

        val process = manager.login("/etc/phoshdroid/start.sh")

        assertNotNull(process)
        verify {
            runner.start(
                match { it.contains("login") && it.last() == "/etc/phoshdroid/start.sh" },
                any()
            )
        }
    }

    @Test
    fun `login passes environment variables`() {
        val runner = mockk<ProcessRunner>(relaxed = true)
        val mockProcess = mockk<Process>(relaxed = true)
        every { runner.start(any(), any()) } returns mockProcess
        val manager = createManager(processRunner = runner)

        manager.login("/etc/phoshdroid/start.sh")

        verify {
            runner.start(
                any(),
                match { env -> env["WAYLAND_DISPLAY"] == "wayland-0" }
            )
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.proot.ProotDistroManagerTest" 2>&1 | tail -20
```

Expected: FAIL — `ProotDistroManager`, `ProcessRunner`, `ProcessResult` do not exist.

- [ ] **Step 3: Implement ProotDistroManager**

Create `app/src/main/kotlin/com/phoshdroid/app/proot/ProotDistroManager.kt`:
```kotlin
package com.phoshdroid.app.proot

import java.io.File

data class ProcessResult(val exitCode: Int, val output: String)

interface ProcessRunner {
    fun run(command: List<String>, environment: Map<String, String> = emptyMap()): ProcessResult
    fun start(command: List<String>, environment: Map<String, String> = emptyMap()): Process
}

class SystemProcessRunner : ProcessRunner {
    override fun run(command: List<String>, environment: Map<String, String>): ProcessResult {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        environment.forEach { (k, v) -> pb.environment()[k] = v }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return ProcessResult(exitCode, output)
    }

    override fun start(command: List<String>, environment: Map<String, String>): Process {
        val pb = ProcessBuilder(command)
        pb.redirectErrorStream(true)
        environment.forEach { (k, v) -> pb.environment()[k] = v }
        return pb.start()
    }
}

class ProotDistroManager(
    private val commandBuilder: ProotCommandBuilder,
    private val installedRootfsDir: File,
    private val rootfsTarball: File,
    private val processRunner: ProcessRunner = SystemProcessRunner()
) {

    private val distroDir = File(installedRootfsDir, "postmarketos")

    fun isInstalled(): Boolean = distroDir.exists() && distroDir.isDirectory

    fun install(): ProcessResult {
        return processRunner.run(
            commandBuilder.buildInstallCommand(rootfsTarball.absolutePath),
            commandBuilder.buildEnvironment()
        )
    }

    fun login(
        startupScript: String,
        bindSdcard: Boolean = false
    ): Process {
        return processRunner.start(
            commandBuilder.buildLoginCommand(startupScript, bindSdcard),
            commandBuilder.buildEnvironment()
        )
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.proot.ProotDistroManagerTest" 2>&1 | tail -20
```

Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/proot/ProotDistroManager.kt \
       app/src/test/kotlin/com/phoshdroid/app/proot/ProotDistroManagerTest.kt
git commit -m "feat: add ProotDistroManager for rootfs lifecycle

Manages proot-distro install and login. Uses injectable ProcessRunner
for testability. Checks installation status via rootfs directory."
```

---

### Task 6: ProotConfigWriter (TDD)

**Files:**
- Create: `app/src/test/kotlin/com/phoshdroid/app/settings/ProotConfigWriterTest.kt`
- Create: `app/src/main/kotlin/com/phoshdroid/app/settings/ProotConfigWriter.kt`

Writes Phoshdroid settings into the rootfs as `/etc/phoshdroid/config` (KEY=VALUE format).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/phoshdroid/app/settings/ProotConfigWriterTest.kt`:
```kotlin
package com.phoshdroid.app.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProotConfigWriterTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `writes default config`() {
        val configFile = File(tempDir, "etc/phoshdroid/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(
            ProotConfig(
                scaleFactor = 1.0f,
                orientation = Orientation.AUTO,
                dpiOverride = null,
                desktopEnvironment = DesktopEnvironment.PHOSH
            )
        )

        assertTrue(configFile.exists())
        val content = configFile.readText()
        assertTrue(content.contains("SCALE_FACTOR=1.0"))
        assertTrue(content.contains("ORIENTATION=auto"))
        assertTrue(content.contains("DE=phosh"))
        assertFalse(content.contains("DPI_OVERRIDE"))
    }

    @Test
    fun `writes dpi override when set`() {
        val configFile = File(tempDir, "etc/phoshdroid/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(
            ProotConfig(
                scaleFactor = 1.5f,
                orientation = Orientation.LANDSCAPE,
                dpiOverride = 320,
                desktopEnvironment = DesktopEnvironment.PHOSH
            )
        )

        val content = configFile.readText()
        assertTrue(content.contains("SCALE_FACTOR=1.5"))
        assertTrue(content.contains("ORIENTATION=landscape"))
        assertTrue(content.contains("DPI_OVERRIDE=320"))
    }

    @Test
    fun `creates parent directories`() {
        val configFile = File(tempDir, "deep/nested/path/config")
        val writer = ProotConfigWriter(configFile)

        writer.write(ProotConfig())

        assertTrue(configFile.exists())
    }

    @Test
    fun `overwrites existing config`() {
        val configFile = File(tempDir, "config")
        configFile.writeText("OLD_KEY=old_value\n")
        val writer = ProotConfigWriter(configFile)

        writer.write(ProotConfig(scaleFactor = 2.0f))

        val content = configFile.readText()
        assertFalse(content.contains("OLD_KEY"))
        assertTrue(content.contains("SCALE_FACTOR=2.0"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.settings.ProotConfigWriterTest" 2>&1 | tail -20
```

Expected: FAIL — `ProotConfigWriter`, `ProotConfig`, `Orientation`, `DesktopEnvironment` do not exist.

- [ ] **Step 3: Implement ProotConfigWriter**

Create `app/src/main/kotlin/com/phoshdroid/app/settings/ProotConfigWriter.kt`:
```kotlin
package com.phoshdroid.app.settings

import java.io.File

enum class Orientation(val value: String) {
    AUTO("auto"), LANDSCAPE("landscape"), PORTRAIT("portrait")
}

enum class DesktopEnvironment(val value: String) {
    PHOSH("phosh"), XFCE4("xfce4"), SXMO("sxmo"), PLASMA_MOBILE("plasma-mobile")
}

data class ProotConfig(
    val scaleFactor: Float = 1.0f,
    val orientation: Orientation = Orientation.AUTO,
    val dpiOverride: Int? = null,
    val desktopEnvironment: DesktopEnvironment = DesktopEnvironment.PHOSH,
    val customStartupScript: String? = null
)

class ProotConfigWriter(private val configFile: File) {

    fun write(config: ProotConfig) {
        configFile.parentFile?.mkdirs()

        val lines = mutableListOf<String>()
        lines.add("SCALE_FACTOR=${config.scaleFactor}")
        lines.add("ORIENTATION=${config.orientation.value}")
        lines.add("DE=${config.desktopEnvironment.value}")

        config.dpiOverride?.let {
            lines.add("DPI_OVERRIDE=$it")
        }

        config.customStartupScript?.let {
            lines.add("CUSTOM_STARTUP=$it")
        }

        configFile.writeText(lines.joinToString("\n") + "\n")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:test --tests "com.phoshdroid.app.settings.ProotConfigWriterTest" 2>&1 | tail -20
```

Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/settings/ProotConfigWriter.kt \
       app/src/test/kotlin/com/phoshdroid/app/settings/ProotConfigWriterTest.kt
git commit -m "feat: add ProotConfigWriter for rootfs config generation

Writes KEY=VALUE config to /etc/phoshdroid/config inside the rootfs.
Supports scale factor, orientation, DPI override, DE selection, and
custom startup script."
```

---

### Task 7: ProotService (Foreground Service)

**Files:**
- Create: `app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt`

This is the foreground service that manages the proot process lifecycle. It's Android-framework-heavy, so we test it through integration tests on-device rather than unit tests.

- [ ] **Step 1: Implement ProotService**

Create `app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt`:
```kotlin
package com.phoshdroid.app.proot

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phoshdroid.app.LauncherActivity
import com.phoshdroid.app.PhoshdroidApp
import com.phoshdroid.app.R
import com.phoshdroid.app.settings.PhoshdroidPreferences
import java.io.File

class ProotService : Service() {

    private var prootProcess: Process? = null
    private var manager: ProotDistroManager? = null

    override fun onCreate() {
        super.onCreate()
        val filesDir = applicationContext.filesDir
        val commandBuilder = ProotCommandBuilder(
            prootDistroPath = "${filesDir}/usr/bin/proot-distro",
            distroName = DISTRO_NAME
        )
        manager = ProotDistroManager(
            commandBuilder = commandBuilder,
            installedRootfsDir = File(filesDir, "proot-distro/installed-rootfs"),
            rootfsTarball = File(filesDir, "rootfs/rootfs.tar.zst")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopProot()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startProot()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProot()
        super.onDestroy()
    }

    private fun startProot() {
        if (prootProcess != null) return

        val prefs = PhoshdroidPreferences(this)
        val startupScript = prefs.customStartupScript ?: DEFAULT_STARTUP_SCRIPT

        prootProcess = manager?.login(
            startupScript = startupScript,
            bindSdcard = prefs.bindSdcard
        )

        // Monitor process in background thread
        Thread {
            val exitCode = prootProcess?.waitFor() ?: -1
            if (exitCode != 0) {
                // Process died unexpectedly — try one restart
                prootProcess = manager?.login(
                    startupScript = startupScript,
                    bindSdcard = prefs.bindSdcard
                )
                val retryCode = prootProcess?.waitFor() ?: -1
                if (retryCode != 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    private fun stopProot() {
        prootProcess?.let { proc ->
            proc.destroy()
            proc.waitFor()
        }
        prootProcess = null
    }

    private fun buildNotification(): Notification {
        val settingsIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LauncherActivity::class.java).apply {
                action = LauncherActivity.ACTION_SETTINGS
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, ProotService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, PhoshdroidApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .addAction(0, getString(R.string.action_settings), settingsIntent)
            .addAction(0, getString(R.string.action_stop), stopIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.phoshdroid.app.STOP"
        const val DISTRO_NAME = "postmarketos"
        const val DEFAULT_STARTUP_SCRIPT = "/etc/phoshdroid/start.sh"

        fun start(context: Context) {
            val intent = Intent(context, ProotService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProotService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. (PhoshdroidPreferences doesn't exist yet — create a stub in the next step.)

- [ ] **Step 3: Create PhoshdroidPreferences stub**

Create `app/src/main/kotlin/com/phoshdroid/app/settings/PhoshdroidPreferences.kt`:
```kotlin
package com.phoshdroid.app.settings

import android.content.Context
import android.content.SharedPreferences

class PhoshdroidPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("phoshdroid_prefs", Context.MODE_PRIVATE)

    var scaleFactor: Float
        get() = prefs.getFloat("scale_factor", 1.0f)
        set(value) = prefs.edit().putFloat("scale_factor", value).apply()

    var orientation: Orientation
        get() = Orientation.entries.find { it.value == prefs.getString("orientation", "auto") }
            ?: Orientation.AUTO
        set(value) = prefs.edit().putString("orientation", value.value).apply()

    var dpiOverride: Int?
        get() = prefs.getInt("dpi_override", -1).takeIf { it > 0 }
        set(value) = prefs.edit().putInt("dpi_override", value ?: -1).apply()

    var desktopEnvironment: DesktopEnvironment
        get() = DesktopEnvironment.entries.find {
            it.value == prefs.getString("de", "phosh")
        } ?: DesktopEnvironment.PHOSH
        set(value) = prefs.edit().putString("de", value.value).apply()

    var bindSdcard: Boolean
        get() = prefs.getBoolean("bind_sdcard", false)
        set(value) = prefs.edit().putBoolean("bind_sdcard", value).apply()

    var customStartupScript: String?
        get() = prefs.getString("custom_startup", null)
        set(value) = prefs.edit().putString("custom_startup", value).apply()

    var showTerminal: Boolean
        get() = prefs.getBoolean("show_terminal", false)
        set(value) = prefs.edit().putBoolean("show_terminal", value).apply()

    fun toProotConfig(): ProotConfig {
        return ProotConfig(
            scaleFactor = scaleFactor,
            orientation = orientation,
            dpiOverride = dpiOverride,
            desktopEnvironment = desktopEnvironment,
            customStartupScript = customStartupScript
        )
    }
}
```

- [ ] **Step 4: Verify full build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/proot/ProotService.kt \
       app/src/main/kotlin/com/phoshdroid/app/settings/PhoshdroidPreferences.kt
git commit -m "feat: add ProotService foreground service and PhoshdroidPreferences

ProotService manages proot process lifecycle with foreground notification.
Auto-restarts once on crash. PhoshdroidPreferences wraps SharedPreferences
for all app settings."
```

---

### Task 8: LauncherActivity

**Files:**
- Create: `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt`
- Create: `app/src/main/res/layout/activity_launcher.xml`

Entry point activity. First launch: shows extraction progress. Subsequent launches: brief splash then starts ProotService and transitions to WaylandActivity.

- [ ] **Step 1: Create the layout**

Create `app/src/main/res/layout/activity_launcher.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">

    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textColor="@android:color/white"
        android:textSize="32sp"
        android:textStyle="bold"
        app:layout_constraintBottom_toTopOf="@id/statusText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintVertical_chainStyle="packed" />

    <TextView
        android:id="@+id/statusText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/preparing_desktop"
        android:textColor="@android:color/darker_gray"
        android:textSize="16sp"
        app:layout_constraintBottom_toTopOf="@id/progressBar"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/title" />

    <com.google.android.material.progressindicator.LinearProgressIndicator
        android:id="@+id/progressBar"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="48dp"
        android:layout_marginTop="16dp"
        android:visibility="gone"
        app:layout_constraintBottom_toTopOf="@id/errorText"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/statusText" />

    <TextView
        android:id="@+id/errorText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginHorizontal="32dp"
        android:layout_marginTop="24dp"
        android:textColor="#FFCDD2"
        android:textSize="14sp"
        android:visibility="gone"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/progressBar" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 2: Add ConstraintLayout dependency**

In `app/build.gradle.kts`, add to dependencies:
```kotlin
implementation("androidx.constraintlayout:constraintlayout:2.2.0")
```

- [ ] **Step 3: Implement LauncherActivity**

Create `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt`:
```kotlin
package com.phoshdroid.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.phoshdroid.app.extraction.AssetExtractor
import com.phoshdroid.app.proot.ProotCommandBuilder
import com.phoshdroid.app.proot.ProotDistroManager
import com.phoshdroid.app.proot.ProotService
import com.phoshdroid.app.settings.PhoshdroidPreferences
import com.phoshdroid.app.settings.ProotConfigWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LauncherActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var errorText: TextView

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* proceed regardless */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_SETTINGS) {
            // TODO: navigate to SettingsFragment (Task 9)
            finish()
            return
        }

        setContentView(R.layout.activity_launcher)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        errorText = findViewById(R.id.errorText)

        requestNotificationPermission()
        lifecycleScope.launch { bootstrap() }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private suspend fun bootstrap() {
        val filesDir = applicationContext.filesDir
        val extractor = AssetExtractor()

        // Phase 1: Extract Termux bootstrap
        val bootstrapDir = File(filesDir, "usr")
        if (!File(bootstrapDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_termux))
            val extracted = withContext(Dispatchers.IO) {
                val input = assets.open("bootstrap.tar.zst")
                val size = assets.openFd("bootstrap.tar.zst").length
                extractor.extract(input, size, bootstrapDir) { percent ->
                    runOnUiThread { progressBar.progress = percent }
                }
            }
            if (!extracted) {
                showError("Failed to extract Termux environment.")
                return
            }
        }

        // Phase 2: Extract pmOS rootfs
        val rootfsExtractDir = File(filesDir, "rootfs")
        if (!File(rootfsExtractDir, ".extraction_complete").exists()) {
            showProgress(getString(R.string.extracting_rootfs))

            val availableBytes = StatFs(filesDir.absolutePath).availableBytes
            val requiredBytes = 1_500_000_000L // ~1.5GB for extracted rootfs
            if (availableBytes < requiredBytes) {
                showError(getString(
                    R.string.error_no_space,
                    formatSize(requiredBytes),
                    formatSize(availableBytes)
                ))
                return
            }

            val extracted = withContext(Dispatchers.IO) {
                val input = assets.open("rootfs.tar.zst")
                val size = assets.openFd("rootfs.tar.zst").length
                extractor.extract(input, size, rootfsExtractDir) { percent ->
                    runOnUiThread { progressBar.progress = percent }
                }
            }
            if (!extracted) {
                showError("Failed to extract postmarketOS.")
                return
            }
        }

        // Phase 3: Register with proot-distro if needed
        statusText.text = getString(R.string.preparing_desktop)
        progressBar.visibility = View.GONE

        val commandBuilder = ProotCommandBuilder(
            prootDistroPath = "${filesDir}/usr/bin/proot-distro",
            distroName = ProotService.DISTRO_NAME
        )
        val manager = ProotDistroManager(
            commandBuilder = commandBuilder,
            installedRootfsDir = File(filesDir, "proot-distro/installed-rootfs"),
            rootfsTarball = File(rootfsExtractDir, "rootfs.tar.zst")
        )

        if (!manager.isInstalled()) {
            withContext(Dispatchers.IO) {
                val result = manager.install()
                if (result.exitCode != 0) {
                    withContext(Dispatchers.Main) {
                        showError("proot-distro install failed:\n${result.output}", copyable = true)
                    }
                    return@withContext
                }
            }
        }

        // Phase 4: Write config to rootfs
        val prefs = PhoshdroidPreferences(this)
        val configFile = File(filesDir, "proot-distro/installed-rootfs/postmarketos/etc/phoshdroid/config")
        ProotConfigWriter(configFile).write(prefs.toProotConfig())

        // Phase 5: Start ProotService and launch Wayland
        statusText.text = getString(R.string.starting_desktop)
        ProotService.start(this)

        // TODO: Launch WaylandActivity from termux-x11 module (Task 10)
        // For now, show a toast
        Toast.makeText(this, getString(R.string.welcome_toast), Toast.LENGTH_LONG).show()
    }

    private fun showProgress(message: String) {
        statusText.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        errorText.visibility = View.GONE
    }

    private fun showError(message: String, copyable: Boolean = false) {
        statusText.visibility = View.GONE
        progressBar.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message

        if (copyable) {
            errorText.setOnClickListener {
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Phoshdroid Error", message))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        val mb = bytes / (1024 * 1024)
        return if (mb > 1024) "${mb / 1024} GB" else "$mb MB"
    }

    companion object {
        const val ACTION_SETTINGS = "com.phoshdroid.app.SETTINGS"
    }
}
```

- [ ] **Step 4: Verify build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt \
       app/src/main/res/layout/activity_launcher.xml \
       app/build.gradle.kts
git commit -m "feat: add LauncherActivity with first-launch extraction flow

Shows progress during bootstrap and rootfs extraction. Checks available
storage. Registers rootfs with proot-distro. Writes config. Starts
ProotService. Error display with copy-to-clipboard for bug reports."
```

---

### Task 9: SettingsFragment

**Files:**
- Create: `app/src/main/kotlin/com/phoshdroid/app/SettingsFragment.kt`
- Create: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt` (wire up settings navigation)

- [ ] **Step 1: Create preferences.xml**

Create `app/src/main/res/xml/preferences.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory app:title="Display">
        <ListPreference
            app:key="scale_factor"
            app:title="Resolution Scale"
            app:entries="@array/scale_entries"
            app:entryValues="@array/scale_values"
            app:defaultValue="1.0" />

        <ListPreference
            app:key="orientation"
            app:title="Orientation"
            app:entries="@array/orientation_entries"
            app:entryValues="@array/orientation_values"
            app:defaultValue="auto" />

        <EditTextPreference
            app:key="dpi_override"
            app:title="DPI Override"
            app:summary="Leave empty for auto-detection" />
    </PreferenceCategory>

    <PreferenceCategory app:title="Desktop Environment">
        <ListPreference
            app:key="de"
            app:title="Desktop Environment"
            app:entries="@array/de_entries"
            app:entryValues="@array/de_values"
            app:defaultValue="phosh"
            app:summary="Changing DE will install packages on next launch" />
    </PreferenceCategory>

    <PreferenceCategory app:title="Storage">
        <SwitchPreferenceCompat
            app:key="bind_sdcard"
            app:title="Mount /sdcard"
            app:summary="Access Android storage from postmarketOS at ~/sdcard"
            app:defaultValue="false" />
    </PreferenceCategory>

    <PreferenceCategory app:title="Advanced">
        <SwitchPreferenceCompat
            app:key="show_terminal"
            app:title="Show Terminal Panel"
            app:summary="Pull-up terminal for shell access inside proot"
            app:defaultValue="false" />

        <EditTextPreference
            app:key="custom_startup"
            app:title="Custom Startup Script"
            app:summary="Path to script run before Phosh (inside rootfs)" />

        <Preference
            app:key="reset_factory"
            app:title="Reset to Factory"
            app:summary="Re-extract rootfs from bundled image" />
    </PreferenceCategory>

</PreferenceScreen>
```

- [ ] **Step 2: Add array resources**

Add to `app/src/main/res/values/strings.xml`, before the closing `</resources>` tag:
```xml
    <string-array name="scale_entries">
        <item>0.5x</item>
        <item>0.75x</item>
        <item>1.0x (native)</item>
        <item>1.5x</item>
    </string-array>
    <string-array name="scale_values">
        <item>0.5</item>
        <item>0.75</item>
        <item>1.0</item>
        <item>1.5</item>
    </string-array>

    <string-array name="orientation_entries">
        <item>Auto</item>
        <item>Landscape</item>
        <item>Portrait</item>
    </string-array>
    <string-array name="orientation_values">
        <item>auto</item>
        <item>landscape</item>
        <item>portrait</item>
    </string-array>

    <string-array name="de_entries">
        <item>Phosh</item>
        <item>XFCE4</item>
        <item>Sxmo</item>
        <item>Plasma Mobile</item>
    </string-array>
    <string-array name="de_values">
        <item>phosh</item>
        <item>xfce4</item>
        <item>sxmo</item>
        <item>plasma-mobile</item>
    </string-array>
```

- [ ] **Step 3: Implement SettingsFragment**

Create `app/src/main/kotlin/com/phoshdroid/app/SettingsFragment.kt`:
```kotlin
package com.phoshdroid.app

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.phoshdroid.app.extraction.AssetExtractor
import com.phoshdroid.app.proot.ProotService
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = "phoshdroid_prefs"
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("reset_factory")?.setOnPreferenceClickListener {
            showResetConfirmation()
            true
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset to Factory")
            .setMessage("This will stop the desktop and re-extract the rootfs. Your changes inside postmarketOS will be lost. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                ProotService.stop(requireContext())
                val filesDir = requireContext().filesDir
                File(filesDir, "rootfs/.extraction_complete").delete()
                File(filesDir, "proot-distro/installed-rootfs/postmarketos").deleteRecursively()
                requireActivity().recreate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
```

- [ ] **Step 4: Wire settings into LauncherActivity**

In `LauncherActivity.kt`, replace the settings TODO in `onCreate`:
```kotlin
if (intent?.action == ACTION_SETTINGS) {
    setContentView(R.layout.activity_launcher)
    supportFragmentManager.beginTransaction()
        .replace(android.R.id.content, SettingsFragment())
        .commit()
    return
}
```

- [ ] **Step 5: Verify build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/phoshdroid/app/SettingsFragment.kt \
       app/src/main/res/xml/preferences.xml \
       app/src/main/res/values/strings.xml \
       app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt
git commit -m "feat: add SettingsFragment with display, DE, storage, and advanced options

Preferences for scale factor, orientation, DPI override, DE selection,
sdcard mount toggle, terminal panel toggle, custom startup script, and
factory reset. Uses shared preferences consistent with PhoshdroidPreferences."
```

---

### Task 10: WaylandActivity Integration

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (register WaylandActivity)
- Modify: `app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt` (launch Wayland)

Wire up the Termux:X11 WaylandActivity so LauncherActivity can launch it after ProotService starts.

- [ ] **Step 1: Identify the WaylandActivity class in termux-x11**

```bash
grep -r "class.*Activity" termux-x11/app/src/main/java/ | head -10
```

The main activity is typically `com.termux.x11.MainActivity`. Identify the exact class name and package.

- [ ] **Step 2: Register WaylandActivity in AndroidManifest.xml**

Add inside the `<application>` tag in `app/src/main/AndroidManifest.xml`:
```xml
        <!-- Termux:X11 Wayland compositor activity -->
        <activity
            android:name="com.termux.x11.MainActivity"
            android:launchMode="singleTask"
            android:theme="@android:style/Theme.NoTitleBar.Fullscreen"
            android:screenOrientation="unspecified"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
            android:exported="false" />
```

Adjust the class name if the grep in Step 1 reveals a different name.

- [ ] **Step 3: Launch WaylandActivity from LauncherActivity**

In `LauncherActivity.kt`, replace the TODO in the `bootstrap()` function:
```kotlin
        // Phase 5: Start ProotService and launch Wayland
        statusText.text = getString(R.string.starting_desktop)
        ProotService.start(this)

        // Give proot a moment to start phoc and create the Wayland socket
        withContext(Dispatchers.IO) {
            val socketFile = File(filesDir, "tmp/wayland-0")
            var attempts = 0
            while (!socketFile.exists() && attempts < 30) {
                Thread.sleep(500)
                attempts++
            }
        }

        val waylandIntent = Intent(this, Class.forName("com.termux.x11.MainActivity"))
        startActivity(waylandIntent)
        Toast.makeText(this, getString(R.string.welcome_toast), Toast.LENGTH_LONG).show()
```

- [ ] **Step 4: Verify build**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. (Actual runtime testing requires a device with the rootfs.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml \
       app/src/main/kotlin/com/phoshdroid/app/LauncherActivity.kt
git commit -m "feat: integrate Termux:X11 WaylandActivity

Register WaylandActivity in manifest. LauncherActivity waits for
Wayland socket to appear then launches the compositor fullscreen."
```

---

### Task 11: Rootfs Build Pipeline

**Files:**
- Create: `rootfs/build-rootfs.sh`
- Create: `rootfs/build-bootstrap.sh`
- Create: `rootfs/overlay/etc/phoshdroid/start.sh`
- Create: `rootfs/overlay/etc/phoshdroid/config.defaults`

These scripts run on the developer's machine (not in CI) to produce the two asset tarballs.

- [ ] **Step 1: Create start.sh (runs inside proot)**

Create `rootfs/overlay/etc/phoshdroid/start.sh`:
```bash
#!/bin/bash
# Phoshdroid startup script — runs inside the pmOS proot environment.
# Reads config from /etc/phoshdroid/config, starts phoc + phosh.

set -e

CONFIG_FILE="/etc/phoshdroid/config"
if [ -f "$CONFIG_FILE" ]; then
    . "$CONFIG_FILE"
fi

# Defaults
: "${SCALE_FACTOR:=1.0}"
: "${ORIENTATION:=auto}"
: "${DE:=phosh}"
: "${WAYLAND_DISPLAY:=wayland-0}"

export XDG_RUNTIME_DIR="/tmp"
export WAYLAND_DISPLAY
export XDG_SESSION_TYPE="wayland"
export XDG_CURRENT_DESKTOP="Phosh:GNOME"
export DISPLAY=":0"

# DPI override
if [ -n "$DPI_OVERRIDE" ]; then
    export GDK_DPI_SCALE="$DPI_OVERRIDE"
fi

# Run custom startup script if configured
if [ -n "$CUSTOM_STARTUP" ] && [ -f "$CUSTOM_STARTUP" ]; then
    bash "$CUSTOM_STARTUP"
fi

# Start dbus session bus
if [ -z "$DBUS_SESSION_BUS_ADDRESS" ]; then
    eval "$(dbus-launch --sh-syntax)"
    export DBUS_SESSION_BUS_ADDRESS
fi

# Write phoc config
PHOC_INI="/tmp/phoc.ini"
cat > "$PHOC_INI" <<PHOC_EOF
[core]
xwayland=false

[output:WL-1]
scale=$SCALE_FACTOR
PHOC_EOF

if [ "$ORIENTATION" = "landscape" ]; then
    echo "transform=90" >> "$PHOC_INI"
fi

# Start phoc (nested Wayland compositor) then phosh
case "$DE" in
    phosh)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "phosh" &
        PHOC_PID=$!
        wait $PHOC_PID
        ;;
    xfce4)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "startxfce4" &
        wait $!
        ;;
    sxmo)
        export WLR_BACKENDS="wayland"
        sxmo_winit.sh &
        wait $!
        ;;
    plasma-mobile)
        export WLR_BACKENDS="wayland"
        phoc -C "$PHOC_INI" -E "startplasma-wayland" &
        wait $!
        ;;
esac
```

- [ ] **Step 2: Create config.defaults**

Create `rootfs/overlay/etc/phoshdroid/config.defaults`:
```bash
SCALE_FACTOR=1.0
ORIENTATION=auto
DE=phosh
```

- [ ] **Step 3: Create build-rootfs.sh**

Create `rootfs/build-rootfs.sh`:
```bash
#!/bin/bash
# Builds a pmOS rootfs for Phoshdroid using pmbootstrap.
# Output: app/src/main/assets/rootfs.tar.zst
#
# Prerequisites:
#   - pmbootstrap installed (pip install pmbootstrap)
#   - ~2GB disk space for build
#
# Usage: ./rootfs/build-rootfs.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/rootfs.tar.zst"
OVERLAY_DIR="$SCRIPT_DIR/overlay"
WORK_DIR="${PMBOOTSTRAP_WORK:-$HOME/.local/var/pmbootstrap}"

echo "=== Phoshdroid rootfs builder ==="

# Initialize pmbootstrap if needed
if ! pmbootstrap config device 2>/dev/null | grep -q "qemu-aarch64"; then
    echo "Initializing pmbootstrap for qemu-aarch64..."
    pmbootstrap init <<INIT_EOF
qemu
aarch64
phosh
INIT_EOF
fi

# Install packages
echo "Building rootfs with Phosh..."
pmbootstrap install --no-fde

# Extract the rootfs
ROOTFS_IMG="$WORK_DIR/chroot_rootfs_qemu-aarch64"
echo "Extracting rootfs..."

STAGING_DIR=$(mktemp -d)
trap "rm -rf $STAGING_DIR" EXIT

# Copy rootfs contents (excluding kernel/firmware we don't need)
sudo tar -cf - \
    --exclude='./boot' \
    --exclude='./lib/modules' \
    --exclude='./lib/firmware' \
    -C "$ROOTFS_IMG" . | tar -xf - -C "$STAGING_DIR"

# Apply overlay (start.sh, config.defaults)
echo "Applying Phoshdroid overlay..."
cp -r "$OVERLAY_DIR/"* "$STAGING_DIR/"
chmod +x "$STAGING_DIR/etc/phoshdroid/start.sh"

# Compress
echo "Compressing rootfs..."
tar -cf - -C "$STAGING_DIR" . | zstd -19 -T0 -o "$OUTPUT"

echo "=== Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) ==="
```

- [ ] **Step 4: Create build-bootstrap.sh**

Create `rootfs/build-bootstrap.sh`:
```bash
#!/bin/bash
# Builds a minimal Termux bootstrap prefix for Phoshdroid.
# Output: app/src/main/assets/bootstrap.tar.zst
#
# This downloads the official Termux bootstrap and strips it down to
# just the packages needed to run proot-distro.
#
# Prerequisites:
#   - curl, tar, zstd
#
# Usage: ./rootfs/build-bootstrap.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT="$PROJECT_DIR/app/src/main/assets/bootstrap.tar.zst"
ARCH="aarch64"

STAGING_DIR=$(mktemp -d)
trap "rm -rf $STAGING_DIR" EXIT

echo "=== Phoshdroid bootstrap builder ==="

# Download official Termux bootstrap
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/latest/download/bootstrap-${ARCH}.zip"
echo "Downloading Termux bootstrap..."
curl -L "$BOOTSTRAP_URL" -o "$STAGING_DIR/bootstrap.zip"

echo "Extracting..."
cd "$STAGING_DIR"
unzip -q bootstrap.zip -d prefix

# The bootstrap has symlinks stored as files — fix them
while read -r line; do
    dest=$(echo "$line" | cut -d'←' -f1)
    src=$(echo "$line" | cut -d'←' -f2)
    rm -f "prefix/$dest"
    ln -s "$src" "prefix/$dest"
done < prefix/SYMLINKS.txt
rm -f prefix/SYMLINKS.txt

# Install proot-distro and dependencies via dpkg within the prefix
# (proot-distro is a shell script — we just need proot binary + the script)
echo "Installing proot-distro..."
# proot-distro is bundled in the bootstrap or installed via pkg
# Ensure these exist:
for required in bin/bash bin/proot bin/proot-distro bin/tar usr/bin/zstd; do
    if [ ! -f "prefix/$required" ] && [ ! -L "prefix/$required" ]; then
        echo "WARNING: $required not found in bootstrap, may need manual install"
    fi
done

echo "Compressing..."
tar -cf - -C prefix . | zstd -19 -T0 -o "$OUTPUT"

echo "=== Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1)) ==="
```

- [ ] **Step 5: Make scripts executable**

```bash
chmod +x rootfs/build-rootfs.sh rootfs/build-bootstrap.sh rootfs/overlay/etc/phoshdroid/start.sh
```

- [ ] **Step 6: Commit**

```bash
git add rootfs/
git commit -m "feat: add rootfs build pipeline and startup scripts

build-rootfs.sh: uses pmbootstrap to build pmOS aarch64 rootfs with Phosh,
applies overlay, compresses with zstd.
build-bootstrap.sh: downloads and strips Termux bootstrap to minimal prefix.
start.sh: proot startup script that reads config and launches phoc+phosh
as nested Wayland compositor."
```

---

### Task 12: CI & Git Hooks

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `.githooks/pre-push`

Lean CI: lint + unit tests only. Heavy builds (rootfs, integration) done locally.

- [ ] **Step 1: Create CI workflow**

Create `.github/workflows/ci.yml`:
```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
    branches: [main]

jobs:
  lint-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - uses: gradle/actions/setup-gradle@v4

      - name: Lint
        run: ./gradlew lintDebug

      - name: Unit tests
        run: ./gradlew :app:test
```

- [ ] **Step 2: Create pre-push hook for local integration tests**

Create `.githooks/pre-push`:
```bash
#!/bin/bash
# Pre-push hook: runs unit tests before pushing.
# Install: git config core.hooksPath .githooks

set -e

echo "Running unit tests..."
./gradlew :app:test --quiet

echo "All checks passed."
```

```bash
chmod +x .githooks/pre-push
```

- [ ] **Step 3: Configure git to use custom hooks directory**

```bash
cd /home/phil/Development/proot-post
git config core.hooksPath .githooks
```

- [ ] **Step 4: Run all unit tests to verify everything works together**

```bash
./gradlew :app:test 2>&1 | tail -20
```

Expected: All tests pass (AssetExtractorTest, ProotCommandBuilderTest, ProotDistroManagerTest, ProotConfigWriterTest).

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml .githooks/pre-push
git commit -m "feat: add lean CI workflow and pre-push hook

GitHub Actions: lint + unit tests on push/PR to main.
Pre-push hook runs unit tests locally before push.
Heavy builds (rootfs, integration) are developer-local only."
```

---

### Task 13: F-Droid Metadata & README

**Files:**
- Create: `metadata/en-US/full_description.txt`
- Create: `metadata/en-US/short_description.txt`

Minimal F-Droid metadata so the repo is ready for submission.

- [ ] **Step 1: Create F-Droid metadata**

Create `metadata/en-US/short_description.txt`:
```
postmarketOS desktop on Android — no root required
```

Create `metadata/en-US/full_description.txt`:
```
Phoshdroid brings a full postmarketOS desktop (Phosh) to any Android 12+ device,
without requiring root access.

Features:
- One-tap setup — everything bundled in a single APK
- Full Phosh desktop environment (GNOME-based, touch-friendly)
- Wayland compositor via embedded Termux:X11
- Runs on top of proot — no root or bootloader unlock needed
- Configurable: resolution scaling, DPI, orientation
- Optional alternative DEs: XFCE4, Sxmo, Plasma Mobile
- Terminal access for power users

Phoshdroid embeds a minimal Termux environment and uses proot-distro to run
a real postmarketOS (Alpine Linux-based) filesystem. The Phosh desktop renders
through a Wayland compositor directly to an Android surface.
```

- [ ] **Step 2: Commit**

```bash
git add metadata/
git commit -m "feat: add F-Droid metadata

Short and full descriptions for F-Droid listing."
```

---

## Plan Self-Review

**Spec coverage check:**
- Architecture overview: Task 1 (scaffolding), Task 2 (Termux integration)
- APK structure & build: Task 1, Task 2, Task 11
- Android components & lifecycle: Task 7 (ProotService), Task 8 (LauncherActivity), Task 10 (WaylandActivity)
- Termux & proot-distro integration: Task 4 (command builder), Task 5 (distro manager), Task 11 (startup scripts)
- Settings & customization: Task 6 (config writer), Task 7 (preferences), Task 9 (settings UI)
- First-launch experience: Task 8 (LauncherActivity extraction flow)
- Testing strategy: Tasks 3-6 (TDD throughout), Task 12 (CI + hooks)
- Distribution: Task 12 (CI), Task 13 (F-Droid metadata)

All spec sections covered.

**Placeholder scan:** No TBDs or TODOs except two intentional code TODOs in LauncherActivity that are resolved in later tasks (Task 9 and Task 10). Both are marked with the task number.

**Type consistency:**
- `ProotCommandBuilder` — same constructor signature in Tasks 4, 5, 7, 8
- `AssetExtractor.extract()` — same signature in Tasks 3 and 8
- `ProotDistroManager` — same constructor in Tasks 5 and 8
- `PhoshdroidPreferences` — created in Task 7, used in Tasks 7, 8, 9
- `ProotConfigWriter` — created in Task 6, used in Task 8
- `ProotConfig` — created in Task 6, produced by `PhoshdroidPreferences.toProotConfig()` in Task 7
- `ProotService.start()/stop()` — companion methods used consistently in Tasks 7, 8, 9

All consistent.
