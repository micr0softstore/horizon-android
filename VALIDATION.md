# Validation

Target: Android 16, arm64 phone. A matching private resource bundle was prepared on 2026-10-30. On **2026-10-31**, the user reported: **“the game launches and runs fine!”** This is a successful user-reported phone result. The exact phone model/GPU, world/session details, and individual checks exercised have not been recorded; no instrumented on-device result was captured by the development agent.

For continuation context, see [handoff.md](handoff.md). Resource preparation, integrity checks, and the actual-importer test are documented in [artifacts/RESOURCE_BUNDLE_VALIDATION.md](artifacts/RESOURCE_BUNDLE_VALIDATION.md).

## Automated checks

Verified on 2026-10-31 against upstream commit `073afd484e8667f597790eff62321c35f07e7ba7`:

- Optimized arm64 Rust build: **passed**, with the `audio` feature enabled. Rust 1.99.1, cargo-ndk 4.1.2, NDK 27.2.12479018, Android native API 26, upstream `fast` profile (optimization level 3, no LTO), debug information/incremental compilation disabled. The native link sets `-Wl,-z,max-page-size=16384`.
- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: **passed**, without the source-only `allowMissingNative` escape hatch. Compile/target SDK 36, minimum SDK 26.
- **16 tests passed**, no failures/skips: five ZIP-import traversal/rollback/cancellation tests, eight authenticated controller transport/ordering/reconnect/turn/hotbar/mouse tests, and three launcher/editor/settings UI smoke tests.
- Android lint: **0 errors, 17 warnings**. Warnings concern newer available dependencies, orientation behavior on large Android 16 screens, missing ChromeOS x86 support, backup rules, and optional storage/preferences API improvements. They are not silently disabled.
- Launcher, control-editor, and settings screenshots rendered with Robolectric native graphics at a 412 × 915 dp phone size and visually inspected.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk` (and `artifacts/horizon-android-arm64-debug.apk`), approximately 39 MiB, package `dev.horizon.android`, version `0.1.0-dev`. APK Signature Scheme v2 verification and `zipalign -c -P 16 -v 4` pass. It contains the arm64 runtime; no Minecraft game resources are included.
- `scripts/verify-apk.sh`: **passed against the packaged APK**. Both bundled arm64 libraries (`libmain.so` and `libandroidx.graphics.path.so`) are AArch64 ELF shared objects with every LOAD segment aligned to at least 16 KiB. The three required Rust/GameActivity entry points are exported; signature verification uses minimum API 26 and treats signature warnings as errors.
- The `bundleDebug` dry-run task graph includes `verifyNative`, so the missing-runtime guard covers APK and AAB tasks.

APK SHA-256:

```text
ea76ba170b18e700ef7559c98c62c61e3c73c6c52afa7e435f0d6c9c2f1420dd
```

Reports are generated under `app/build/reports/`; test XML is under `app/build/test-results/testDebugUnitTest/`. The build outputs and screenshots are local generated artifacts, not tracked source.

## Control System Parity with Mojolauncher (2026-10-31)

- **Default Layout:** Rectangular buttons matching Mojolauncher / PojavLauncher default layout with exact bindings:
  - Top: `DEBUG` (F3), `CHAT` (T), `KEYBOARD` (IME soft keyboard), `TAB` (Player list), `MOUSE` (Virtual mouse toggle).
  - Left: `3RD` (F5 perspective toggle), `ESC` (Escape / Pause).
  - Movement D-Pad: `PRI` (Primary click / attack), `▲` (W), `SEC` (Secondary click / use), `◀` (A), `◇` (Crouch / Sneak toggle), `▶` (D), `GUI` (hide/show controls overlay), `▼` (S), `INV` (Inventory E).
  - Bottom Right: `⬛` (Jump / space).
- **Control Editor & Context Menu:**
  - Gear cogwheel button at top opens the drawer menu (Add button, Load, Save, Select default controlmap, Export controlmap).
  - Selecting a button displays `DELETE` and `CLONE` actions, as well as a Properties drawer allowing custom naming, size (width/height), opacity, corner radius, key/action mapping, and toggle/pass-thru switches.
- **Swipe-to-Look:** Direct single-finger dragging on the game screen translates to smooth camera turning via Horizon's `turn yaw pitch` command.
- **Direct Hotbar Selection:** Touching slots 1–9 at the bottom hotbar region switches active hotbar slots directly via `hotbar <1..9>`.
- **Ignore Notch Setting:** A launcher Settings switch that toggles `layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES` vs `LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER`.

## Device evidence and remaining verification

Basic launch and operation are now **user-reported working**. At the original automated build there was no connected device; the subsequent phone test was performed by the user. Simultaneous multitouch scenarios, measured frame rate, device audio, lifecycle recovery, save/reload integrity, repeat launches, and sustained sessions still require explicit results. Do not mark the checklist below complete solely from the general success report, or treat one phone as broad hardware coverage.

The renderer still inherits upstream's 2,048 texture-array-layer requirement and `Depth32Float` use. Older GPUs, including phones upgraded to Android 16, may be rejected. Audio is compiled in; Android audio-focus handling is not implemented. Full Android IME integration, physical gamepad support, account sign-in, and on-device resource preparation are not implemented by this port.

## Device acceptance checklist

1. Install the arm64 APK. Open Play, Controls, and Settings; verify wallpaper colors and dark/light mode.
2. Import the pinned version's prepared resource ZIP. A malformed/rejected replacement must preserve the previous bundle.
3. Launch. Confirm the native title screen appears and the control connection becomes active.
4. Create a local world. Move and look simultaneously, jump while moving, hold attack/use, open inventory, cycle hotbar slots, pause and return.
5. Drag a control, resize it, change opacity and binding, and Save. Relaunch the game and launcher; verify persistence. Cancel must discard edits.
6. Press Home, lock/unlock, switch apps, and return. Verify rendering recovers, no controls remain held, and the world was paused/saved appropriately.
7. Save and quit, then launch again. The launcher must remain alive; the second game process must create a fresh Winit event loop.
8. Exercise audio, 16 KB page devices if available, and a sustained session. Begin at 8 chunks and reduce distance if the phone heats up or runs out of memory.

Report the phone model/GPU, Android version, symptoms, and the native logs/crash report for any failure. A successful compile cannot substitute for these checks.
