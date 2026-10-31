# Horizon for Android

An Android port of [horizon-rust/horizon](https://github.com/horizon-rust/horizon), with the game running natively in Rust and a Kotlin/Jetpack Compose launcher. The initial device target is **Android 16, arm64**; the app's minimum Android version is 8.0/API 26.

This is a development port. On 2026-10-31, the user reported that the game launches and runs fine on their Android 16 arm64 phone. That establishes a user-reported working baseline, not broad device compatibility or exhaustive audio, lifecycle, performance, and save testing. See [VALIDATION.md](VALIDATION.md) for the evidence and remaining checks, and [handoff.md](handoff.md) for architecture, build pitfalls, artifacts, and how to continue development.

## What is implemented

- Native Kotlin launcher using Material 3 1.4.0, wallpaper-derived Material You colors, light/dark themes, and local launch settings.
- Rust `libmain.so` containing Horizon's existing gameplay, renderer, integrated server, and optional AAudio output. No JVM runs the game.
- Android `GameActivity`/Winit integration, including an isolated game process so the game can be launched repeatedly.
- In-app touch overlay with movement/look sticks, held buttons, inventory, pause, hotbar cycling, and a keyboard helper.
- Shared control editor in the launcher and in game: drag, add/remove, remap, resize, adjust opacity, reset, save, and cancel.
- Token-authenticated localhost input using Horizon's existing virtual controller protocol. Analog samples are coalesced; button edges remain ordered; input is released on cancellation/backgrounding/reconnect.
- Private resource ZIP import with staging, traversal/size checks, cancellation, and rollback when a replacement fails.
- Atomic launch/result files, app-private profiles and logs, and conservative mobile render distance.

Horizon is pinned to commit `073afd484e8667f597790eff62321c35f07e7ba7`. Android changes are kept in [patches/horizon-android.patch](patches/horizon-android.patch), so they can be reviewed and rebased without copying the entire upstream into this project's tracked files. `engine/` is the generated patched checkout.

## Build

Use Android Studio or Linux with JDK 17+, Android SDK platform 36/build tools 35.0.0, NDK `27.2.12479018`, and a current stable Rust toolchain. The pinned upstream uses newer dependencies than its original Rust minimum; prefer current stable Rust.

```bash
export ANDROID_HOME=/path/to/Android/Sdk
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;35.0.0' 'ndk;27.2.12479018'
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked

bash scripts/sync-engine.sh
bash scripts/build-native.sh
./gradlew assembleDebug
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Native compilation can take substantial time and memory. `HORIZON_BUILD_PROFILE=fast` disables upstream's release LTO for quicker development builds. `HORIZON_AUDIO=0` is available for isolating audio issues. `HORIZON_ABIS="arm64-v8a x86_64"` builds an emulator ABI as well.

When narrowing `HORIZON_ABIS` after an earlier multi-ABI build, move unrequested ABI directories out of `app/src/main/jniLibs/` first. The script refuses to silently include a stale engine and will not delete earlier outputs for you.

The native linker uses 16 KB page alignment for newer Android devices. Run `bash scripts/verify-apk.sh` after packaging to check the APK signature, alignment, and native entry points. It currently expects the Linux NDK toolchain.

For launcher development without compiling the game:

```bash
./gradlew testDebugUnitTest lintDebug -PallowMissingNative=true
./gradlew assembleDebug -PallowMissingNative=true
```

Such an APK can open the launcher and editor, but its Play button remains disabled unless a compatible runtime is actually packaged. Normal APK packaging fails when the runtime is missing.

## Prepare your game files

The app does not include Minecraft assets or perform Microsoft account sign-in. For this first port, prepare the resource tree on your computer using your own Java Edition installation and the **same pinned Horizon version**. Follow [Horizon's build instructions](https://github.com/horizon-rust/horizon/tree/073afd484e8667f597790eff62321c35f07e7ba7#build-from-source); preparation requires JDK 25 and the matching client/server files.

From `engine/`, after installing Minecraft Java 26.3 and obtaining its matching server JAR:

```bash
cargo run --release -p horizon-prepare -- --prepare --server-jar /path/to/server.jar
cargo run --release -p horizon-prepare -- sounds
```

The default prepared tree is `.local/horizon-client/26.3/`. ZIP the **contents** of that directory, including `horizon-resources.json`, so the manifest is at the archive root. Do not put the tree inside an extra enclosing directory. Transfer the ZIP privately to your phone, open Horizon, choose **Import game files**, then **Launch Horizon**.

Keep prepared game resources private; they are not covered by Horizon's source-code license. The Android project and its APK do not contain them. No desktop JAR or downloaded native engine is executed by the launcher.

## Install and test on your phone

The development APK is debug-signed, targets Android 16, and contains the arm64 Rust runtime with audio. You can transfer it to your phone and open it to install, or use USB debugging:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Import the prepared resource ZIP, then launch at the default 8-chunk distance. Follow the [device acceptance checklist](VALIDATION.md#device-acceptance-checklist), especially movement/look together, saving a world, background/resume, and a second launch. This build is for testing, not a Play Store release.

If the game closes or never reaches its title screen, check the launcher's Details section. With USB debugging enabled, the debug APK also permits collecting its private native logs:

```bash
adb exec-out run-as dev.horizon.android tar -C files/horizon/profiles/default/home -cf - . > horizon-logs.tar
```

Logs can include local paths and world/server details; review them before sharing. A failure before native logging initializes may instead require Android Studio Logcat for the `dev.horizon.android:game` process.

## Touch controls

Use the Move stick to move; Look changes your view. Jump also confirms menu selections, Back cancels, and Pause follows the game's own pause/back flow. The sticks and buttons can operate together. On the title screen and in menus, Horizon's virtual gamepad provides navigation; Android menu taps are also bridged to native mouse events.

Choose **Controls** in the launcher to edit before launching, or the **Controls** button above the game to edit while running. Changes are saved only when you press Save. The preview is landscape, matching the default game orientation. Keyboard opens an Android text dialog; in-world chat and editable fields use Horizon's existing text command. Desktop authentication/browser dialogs and full Android IME integration are not implemented.

The overlay is part of the game's own window. It needs no accessibility or draw-over-other-apps permission. `INTERNET` is used for localhost control sockets and the Rust game's network features.

## Project layout

```text
app/src/main/java/dev/horizon/android/
  LauncherScreen.kt          Material You launcher
  ProfileStorage.kt          profiles, launch configuration, runtime status
  PreparedBundleImporter.kt  transactional resource import
  GameOverlay.kt             touch input and shared visual editor
  ControlLayout.kt           saved layout and action bindings
  ControlBridge.kt           authenticated virtual controller connection
  HorizonGameActivity.kt     native surface + Compose overlay
patches/horizon-android.patch Android changes to the pinned Rust upstream
scripts/                     repeatable source/native build checks
```

Worlds and options live under `files/horizon/profiles/default/game/`. Logs and crash reports live under the profile's `home/`. Control layouts are JSON files alongside them. The APK currently exposes one local profile; the storage format permits adding a profile picker later.

## Remaining work

Hardware validation is required before calling this a stable playable release. In particular: Vulkan/GLES capabilities on Adreno/Mali, Android lifecycle races, keyboard/controller hardware, audio focus, memory pressure, long sessions, and saves after Android kills the app. Android 16 alone does not guarantee GPU compatibility: the current upstream renderer requires 2,048 texture-array layers and `Depth32Float`, which can exclude older phones upgraded to newer Android versions. Account sign-in and on-device resource preparation remain separate work. Upstream's own gameplay limitations also remain.

## Credits

Horizon is by Ludovic Marie and the Horizon contributors, licensed under MIT for its original code. This Android integration preserves the Rust game and adds a native host. See [LICENSE](LICENSE), [upstream third-party notices](https://github.com/horizon-rust/horizon/blob/073afd484e8667f597790eff62321c35f07e7ba7/THIRD_PARTY_NOTICES.md), [Winit Android support](https://docs.rs/winit/0.30.13/winit/platform/android/), and [Android GameActivity](https://developer.android.com/games/agdk/game-activity/get-started). Minecraft is a trademark of Mojang/Microsoft; this is an unofficial project.
