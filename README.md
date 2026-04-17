# Phoshdroid

**Phone + Linux, same screen, no root, no tears.**

Phoshdroid runs [postmarketOS](https://postmarketos.org/) + [Phosh](https://gitlab.gnome.org/World/Phosh/phosh) — a real, glassy, mobile-first Linux desktop — on an unrooted Android phone. Swipe-up unlocks, apps render full-screen, touch events land where they should. It is, by any reasonable measure, absurd that this works.

It works anyway.

```
  Android 12+  ──┐
                 │
                 ▼
          ┌──────────────┐      ┌─────────┐
          │  Phoshdroid  │──►   │  proot  │──►  pmOS rootfs (Alpine + phosh)
          │     APK      │      └─────────┘          │
          └──────┬───────┘                           │
                 │                                   │
          ┌──────▼───────┐                           │
          │ Termux:X11   │◄──── wl_touch / wl_pointer ◄── phoc (wayland)
          │  (in-proc)   │                           │
          └──────────────┘                           │
                                                     ▼
                                                  phosh 🟢
```

## What actually happens

- **One APK.** `./gradlew assembleDebug` → drop on any Android 12+ phone → tap → Phosh.
- **Unrooted.** Proot + binaries in `nativeLibraryDir` to survive Android's W^X exec rules. No fastboot, no magisk, no sacrifices.
- **In-process X server.** Termux:X11 (Xlorie + phoc + phosh) all run inside the Android app. No socket juggling between processes. (This is harder than it sounds — see [§ The conn_fd saga](#-the-conn_fd-saga).)
- **Touch unlock.** Swipe up on the lockscreen — gestures reach phosh like they would on a Librem 5. We had to fight several layers of the input stack for this one.
- **Full-screen, any device.** Reads your phone's display metrics at launch, sizes phoc's Wayland output to match, hides Android nav bar via immersive sticky, excludes the whole surface from system-gesture zones.
- **Device-friendly.** 16KB page-size support for the latest Android devices. Bottom strip reserved so phosh's edge-swipe has an edge to swipe from.

## Try it

```bash
git clone --recursive git@github.com:zweck/Phoshdroid.git
cd Phoshdroid

# Build the pmOS rootfs (one-time, needs pmbootstrap)
./rootfs/build-rootfs.sh
./rootfs/package-rootfs.sh

# Assemble + install the APK
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.phoshdroid.app/.LauncherActivity
```

Any phone running Android 12 (API 31) or newer. Tested heavily on Android 16 with 16KB page size.

## The conn_fd saga

The single biggest blocker: Termux:X11's upstream design assumes the X server (`CmdEntryPoint`) and the activity (`MainActivity`) run in **separate processes**. They share a global variable `conn_fd` — which is fine when each process has its own copy.

Running both in one process means both sides *literally share the same variable*. The server's `addFd()` writes its end of the socketpair to `conn_fd`. A second later the activity's `connect_()` writes *its* end of the socketpair to the same `conn_fd`. Server's end is gone. Server continues sending shared buffers to an fd the client hasn't claimed. Phosh renders into the void. Black screen.

Fix: split `conn_fd` into private `server_conn_fd` in `cmdentrypoint.c` and private `client_conn_fd` in `activity.c`. Two lines of diff, three days of investigation.

## The swipe saga

Touch worked. `wl_touch.down` → `wl_touch.motion (lots)` → `wl_touch.up` all delivered to phosh. Coordinates correct. Phosh's grabber animation stopped when fingers landed — so the surface was receiving input — but the swipe never completed the unlock.

Two separate problems layered on top of each other:

1. **Phantom pointer events.** Termux:X11's Direct-Touch mode was *also* calling `moveCursorToScreenPoint` on every finger-drag, sending an X11 pointer-motion alongside the touch stream. Phoc saw `wl_pointer.motion` with no button held and classified the whole interaction as hover — our real `wl_touch` was treated as noise.
2. **Zero-motion velocity resets.** Android sends `ACTION_MOVE` events as fast as it can, even when the finger hasn't physically moved between frames. Upstream Termux:X11 was passing every one of those through as `XI_TouchUpdate(x, y)` — including the duplicates. Phosh's swipe-velocity estimator saw constant motion at zero velocity → classified as "long press, no swipe" → snapped back.

Fix: gate the cursor-follow to `SimulatedTouchInputStrategy` only, and dedup identical `XI_TouchUpdate` events per pointer id. Now phosh detects real flicks and unlocks.

## Architecture, briefly

- **Android side** (`app/`, Kotlin): launcher activity, proot service, rootfs extraction.
- **X11 side** (`termux-x11/`, submodule, `phoshdroid-integration` branch): Xlorie X server, LorieView SurfaceView, touch → X11 translation.
- **Terminal side** (`termux-app/`, submodule, `phoshdroid-integration` branch): terminal-emulator + termux-shared, linked for 16KB pages.
- **Linux side** (`rootfs/`): pmbootstrap-built aarch64 rootfs + overlay (phosh startup script, config).

The rootfs.bin asset is ~600MB of Alpine Linux + phosh + dependencies. The APK is ~800MB debug / smaller release.

## What works

- Phosh shell renders full-screen at device resolution
- Touch gestures (swipe-up unlock, swipe-down control panel) are recognized
- Lockscreen dismisses without PIN (via `sm.puri.phosh.lockscreen require-unlock=false`)
- D-Bus session bus available to phosh (via `dbus-run-session`)
- phoc's Wayland compositor runs with X11 backend talking to our embedded X server
- Immersive sticky fullscreen + system-gesture exclusion rects so Android's home-swipe doesn't eat phosh's gestures

## What doesn't work yet

- **SVG icons.** gdk-pixbuf 2.42 has PNG/JPEG built in but no SVG support; gdk-pixbuf 2.44 uses [glycin](https://gitlab.gnome.org/sophie-h/glycin) which requires `bwrap` sandboxing that won't run in proot. Fix path: pre-rasterize the Adwaita icon set to PNG on host, bake into the rootfs.
- **Android keyboard integration.** phosh wants `sm.puri.OSK0` (Stevia, Wayland OSK). Doable by registering as a Wayland `zwp_input_method_v2` client, or by auto-showing Android's IME on X11 focus-in. Not yet wired up.
- **System services.** No NetworkManager / ModemManager / UPower / BlueZ — none of them have a system bus in proot. Apps that rely on them degrade gracefully to "permanently disabled" states.
- **Audio.** `callaudiod` floods the log with `Connection refused`. PulseAudio/PipeWire bridging is a whole other project.

## Contributing

This is early-ambitious-project territory. If any of this sounds interesting, PRs welcome. Open questions that would benefit from help:

- Building a gdk-pixbuf SVG loader that works under proot (or pre-rasterizing the icon theme)
- Wayland `zwp_input_method_v2` backing onto Android's InputMethodManager
- PulseAudio network protocol → Android AudioTrack bridge
- A phoc/wlroots patch that honors `WLR_X11_OUTPUT_SIZE` so we don't need the `mode=` hack in phoc.ini

## Credits

- [Termux:X11](https://github.com/termux/termux-x11) — the X server on Android that makes all this possible
- [Termux](https://github.com/termux/termux-app) — terminal + shared infrastructure
- [postmarketOS](https://postmarketos.org/) — the Linux distribution
- [Phosh](https://gitlab.gnome.org/World/Phosh/phosh) — the mobile shell
- [proot](https://proot-me.github.io/) — the unprivileged-root trick that starts the whole house of cards

## License

Same as Termux and Phosh upstreams (GPL-family). See `LICENSE` in each submodule.
