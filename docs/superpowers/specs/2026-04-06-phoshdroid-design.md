# Phoshdroid Design Spec

A single Android APK that delivers a full postmarketOS + Phosh desktop experience on any Android 12+ device, without root. Built on embedded Termux and Termux:X11 Wayland compositor, using proot-distro to run a bundled pmOS rootfs.

## Target Audience

- Tinkerers and developers who want a real Linux desktop on their Android phone without rooting
- postmarketOS enthusiasts who want to try pmOS on devices without official ports

## Key Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Display | Termux:X11 Wayland mode, bundled in APK | Single-app experience, native Wayland path |
| First-launch UX | Opinionated defaults, settings later | Fast to start, customizable when needed |
| Default DE | Phosh | Flagship pmOS UI, touch-friendly |
| Android target | 12+ (API 31+) | Avoids legacy storage/service headaches |
| Rootfs delivery | Bundled in APK | One-tap, no network required |
| Distribution | F-Droid + GitHub Releases | Fits audience, no size limits |
| Languages | Java (Termux/X11 integration) + Kotlin (new UI/settings) | Minimal upstream divergence + modern Android |
| proot approach | proot-distro as dependency | Leverage battle-tested tool, less maintenance |
| CI | Lean (lint + unit tests only) | Local hooks for rootfs builds and integration tests |

## 1. Architecture Overview

Phoshdroid is a single Android APK with four embedded layers:

```
+-----------------------------------+
|  Kotlin UI Layer                  |  Settings, launcher, progress UI
+-----------------------------------+
|  Termux:X11 Wayland Compositor    |  Java, renders Wayland clients to
|  (embedded as Activity)           |  an Android SurfaceView
+-----------------------------------+
|  Termux Bootstrap Environment     |  Minimal Termux with proot-distro,
|  (embedded, headless)             |  runs as a foreground service
+-----------------------------------+
|  pmOS Rootfs (compressed asset)   |  Alpine-based, Phosh + dependencies
|  Extracted to app-internal        |  pre-installed in the image
|  storage on first launch          |
+-----------------------------------+
```

### Startup Flow

1. User taps app icon
2. First launch: extract rootfs from APK assets to internal storage (~30-60 seconds, progress bar shown)
3. Start a Termux foreground service (keeps the environment alive)
4. proot-distro logs into the pmOS rootfs
5. Inside proot: launch dbus-daemon, then phoc (Phosh's Wayland compositor), then phosh
6. Termux:X11 Wayland activity launches fullscreen, connecting to phoc's Wayland socket
7. User sees the Phosh lock screen / desktop

Subsequent launches skip step 2 and go straight to steps 3-7 (~3-5 seconds).

## 2. APK Structure & Build System

### Gradle Modules

```
phoshdroid/
+-- app/                          # Kotlin - launcher, settings, lifecycle
+-- termux-shared/                # Java - shared Termux library code
+-- termux-x11/                   # Java - Wayland compositor Activity
+-- terminal-emulator/            # Java - Termux terminal emulator lib
+-- rootfs/                       # Build scripts to produce pmOS rootfs
```

### Rootfs Build Pipeline

Runs separately from Gradle:

1. Uses `pmbootstrap` to build a pmOS image targeting `aarch64` with Phosh UI
2. Strips unnecessary firmware/kernel (Android's kernel is used via proot)
3. Compresses with `zstd` for fast extraction
4. Output placed in `app/src/main/assets/rootfs.tar.zst`

### APK Contents

- `lib/` — proot binary, Termux native libs (built per-ABI: arm64-v8a primarily)
- `assets/rootfs.tar.zst` — the pmOS filesystem (~400-500MB compressed)
- `assets/bootstrap.tar.zst` — minimal Termux bootstrap with proot-distro (~15MB)

### Target ABIs

`arm64-v8a` primary. `armeabi-v7a` as a separate build variant — not bundled in the same APK to avoid doubling the size.

## 3. Android Components & Lifecycle

### Activities

**LauncherActivity** (Kotlin)
- Entry point. On first launch shows extraction progress.
- On subsequent launches, briefly shows a splash then transitions to the Wayland activity.
- Houses the settings screen (accessible via back gesture or notification action).

**WaylandActivity** (Java, from Termux:X11)
- Fullscreen activity that renders the Phosh desktop.
- Handles touch input, keyboard events, and display scaling.
- Runs in `singleTask` launch mode so there's only ever one instance.

### Services

**ProotService** (Kotlin, foreground service)
- Manages the proot session lifecycle.
- Shows a persistent notification ("Phoshdroid is running") with actions: Settings, Stop.
- Keeps the process alive when the user switches away.
- Runs the Termux bootstrap shell that calls `proot-distro login postmarketos --shared-tmp -- /bin/bash /etc/phoshdroid/start.sh`.

### Lifecycle Behavior

- **App switch away:** ProotService keeps running, Phosh stays alive. Returning to the app reconnects to the Wayland surface.
- **App killed by system:** ProotService's foreground notification makes this unlikely, but if it happens, next launch re-starts the proot session (fast, no re-extraction).
- **User taps Stop:** Sends SIGTERM to the proot process tree, stops the service, finishes WaylandActivity.
- **Screen rotation:** Passed through to Phosh via Wayland — Phosh handles its own orientation.

### Permissions

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` — to keep proot alive
- `POST_NOTIFICATIONS` — for the persistent notification (Android 13+)
- `WAKE_LOCK` — optional, to prevent sleep during long operations

No network, storage, or camera permissions needed. Everything lives in app-internal storage.

## 4. Termux & proot-distro Integration

### Termux Bootstrap

Embedded as a minimal prefix — just enough to run proot-distro:
- `bash`, `coreutils`, `proot`, `proot-distro`, `tar`, `zstd`
- No Termux package manager (pkg/apt) exposed to the user
- The bootstrap prefix lives at `{app_internal}/usr/`

### proot-distro Integration

On first launch, after extracting the rootfs tarball, register it as a proot-distro distribution by placing the appropriate config in `proot-distro/installed-rootfs/postmarketos/`.

Login command:
```
proot-distro login postmarketos --shared-tmp -- /bin/bash /etc/phoshdroid/start.sh
```

`--shared-tmp` exposes a shared directory for the Wayland socket so Termux:X11 can connect to phoc.

### proot Bind Mounts

- `/tmp` — shared between Termux and proot (Wayland socket lives here)
- `/dev/` — Android's /dev passed through (needed for basic device access)
- `/proc` and `/sys` — passed through by proot automatically
- `/sdcard` optionally bound to `/home/user/sdcard` (configurable in settings, off by default)

### Startup Script

`/etc/phoshdroid/start.sh` inside the rootfs:

1. Export XDG/Wayland environment variables
2. Set `WAYLAND_DISPLAY=wayland-0`
3. Start `dbus-daemon --session`
4. Start `phoc` (Phosh's compositor) with a config pointing to the Wayland nested backend
5. Start `phosh` (the shell)
6. Wait for process exit

### phoc-to-Termux:X11 Communication

phoc is a wlroots-based compositor and normally talks directly to DRM/KMS. Inside proot, there's no real display hardware. The approach:

- Compile phoc with the **wlroots Wayland nested backend**
- Termux:X11 acts as the "host" Wayland compositor
- phoc runs as a **nested Wayland compositor** inside Termux:X11
- This is analogous to running Weston inside Weston — well-trodden path

Phosh thinks it's running normally on phoc, and phoc renders into Termux:X11's surface.

## 5. Settings & Customization

Accessed via LauncherActivity or the persistent notification action. Material 3 design in Kotlin.

### Display

- Resolution scaling (0.5x, 0.75x, 1.0x, 1.5x of native) — passed to phoc config
- Force landscape / portrait / auto
- DPI override (default: auto-detected from Android display metrics)

### Desktop Environment

- Default: Phosh (pre-installed)
- Option to install alternatives via a picker: XFCE4, Sxmo, Plasma Mobile
- Triggers `apk add` inside the rootfs and swaps the startup script
- Power user feature, not prominently surfaced

### Storage

- Toggle to bind-mount `/sdcard` into the proot environment
- Shows disk usage (rootfs size, available space)

### Advanced

- Toggle for hidden Termux terminal (pull-up panel at bottom for shell access inside proot)
- Custom startup script override (path to a script run before Phosh)
- Reset to factory (re-extract rootfs from APK assets)

### Settings Storage

- Android `SharedPreferences` for app-level settings
- Settings that affect the proot environment are written to `/etc/phoshdroid/config` inside the rootfs as `KEY=VALUE` pairs, read by `start.sh`

## 6. First-Launch Experience

### Flow

1. **Splash screen** — Phoshdroid logo, "Preparing your desktop..." message
2. **Extraction phase** — Progress bar with percentage:
   - "Extracting Termux environment..." (~15MB, ~2-3 seconds)
   - "Extracting postmarketOS..." (~400-500MB, ~30-60 seconds depending on device)
3. **Configuration phase** — Brief, automatic:
   - Register rootfs with proot-distro
   - Write default config to rootfs
   - Set default user password (user: `user`, password: `1234` — shown to user with prompt to change)
4. **First boot** — Phosh starts, user sees the lock screen
5. **Welcome toast** — "Pull down from top for Phosh settings. Access Phoshdroid settings from the notification."

### Error Handling

- **Extraction fails (no space):** Clear error with required/available space, option to retry
- **proot fails to start:** Show log output with "Copy to clipboard" button for bug reporting
- **phoc/Phosh crash:** Auto-restart once, then fall back to the Termux terminal with an error message

### Subsequent Launches

- Splash for ~1 second while ProotService starts
- Straight into the Phosh desktop

## 7. Testing Strategy

### Unit Tests (Kotlin/Java)

- Settings read/write round-trips
- Rootfs extraction logic (mock assets, verify file integrity)
- proot-distro command construction (correct flags, bind mounts)
- ProotService lifecycle (start, stop, restart after kill)

### Integration Tests (on-device, run locally)

- Full extraction from APK assets to internal storage
- proot-distro login succeeds and can execute commands
- Wayland socket appears in shared `/tmp`
- Termux:X11 activity connects and renders frames

### Manual Test Matrix

- Devices: at least one Pixel (stock Android), one Samsung (OneUI), one budget device (low RAM)
- Android versions: 12, 13, 14
- Scenarios: first launch, subsequent launch, app switch and return, force stop and restart, low storage, screen rotation

### Testing Boundary

No automated UI tests for Phosh itself — that's upstream's responsibility. Our testing boundary is "Phosh starts and renders to the Wayland surface."

### CI/CD

- GitHub Actions: lint + unit tests only (kept cheap)
- Rootfs built locally via `pmbootstrap` script
- Integration tests run locally via pre-commit/pre-push hooks
- F-Droid metadata maintained in-repo
- APK + rootfs uploaded as GitHub Release artifacts
