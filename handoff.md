# Horizon Android — developer handoff

Last updated: **2026-10-31**. Read this first when continuing the project without the original conversation.

## 1. What this is

An Android port of [horizon-rust/horizon](https://github.com/horizon-rust/horizon), with a native Kotlin/Jetpack Compose launcher and the existing Horizon game running in Rust. Horizon itself reimplements Minecraft: Java Edition; this integration preserves that engine rather than rewriting its gameplay again.

The user's requested experience is:

- A playable Android game, preferably keeping the runtime in Rust.
- A native Kotlin launcher with Google's Material You design language.
- A Pojav-style editable touch-control overlay over the running game.

The first target is an **Android 16 arm64 phone**. This is not a Java/JVM launcher, a WebView demo, or Minecraft Bedrock. Java is needed on the development computer for resource preparation, not to execute the game on Android.

Upstream is deliberately pinned to **`073afd484e8667f597790eff62321c35f07e7ba7`**, targeting **Minecraft Java 26.3**. Do not substitute an arbitrary Minecraft release or latest upstream checkout.

## 2. Current state: working development build

On **2026-10-31**, after receiving the APK and prepared resources, the user reported: **“the game launches and runs fine!”** Their previously specified device target was Android 16 arm64.

That is a successful user-reported on-device result, not merely a successful compilation. Preserve it as the working baseline. The exact phone model/GPU, session duration, frame rate, and individual features exercised were not supplied. Do not infer that every control, audio, lifecycle, or save/reload scenario has passed.

| Area | Current evidence |
| --- | --- |
| Rust game on Android | User reports successful launch and operation on their phone. |
| Kotlin launcher and control editor | Implemented; UI startup tests and screenshot inspection passed. |
| Native build | Optimized arm64 build with audio succeeded. |
| Automated Android checks | 16 tests passed; lint: 0 errors, 17 warnings. |
| APK packaging | Debug-signed APK; signature, required native exports, and 16 KiB alignment checks passed. |
| Game resources | Prepared from the user's installation, checked, zipped, and successfully exercised through the actual compiled importer. |
| Broad compatibility / release readiness | Not established. Only the above device report is available; shipped build is a development APK. |

The build/test baseline was recorded on October 30; the phone report is the October 31 update. This handoff changes documentation, not the APK or engine implementation.

## 3. Where everything is

Current project directory on the original computer:

```text
/home/msstore/.codex/.chatgpt-projects/g-p-6abf95343a548191a8e5cb915f655cfd/horizon-android
```

Paths below are relative to that directory unless explicitly absolute.

| Item | Location / purpose |
| --- | --- |
| Working APK | `artifacts/horizon-android-arm64-debug.apk` (~39 MiB). |
| Normal Gradle APK output | `app/build/outputs/apk/debug/app-debug.apk`. |
| Source snapshot | `artifacts/horizon-android-source.zip`; October 30 snapshot, not automatically updated by later edits. It does **not** include this new handoff, generated engine checkout, native binaries, or game assets. |
| Importable private game ZIP | `/home/msstore/Downloads/horizon-resources-26.3.zip` — 413,668,897 bytes (~414 MB). |
| Prepared resource tree | `engine/.local/horizon-client/26.3/`. |
| Cached imported client/server JARs | `engine/.local/minecraft/26.3/jars/`; not public source. |
| End-user/build guide | [README.md](README.md). |
| Build and device-check record | [VALIDATION.md](VALIDATION.md). |
| Detailed private-resource checks | [artifacts/RESOURCE_BUNDLE_VALIDATION.md](artifacts/RESOURCE_BUNDLE_VALIDATION.md). |
| Preparation/import check logs | `artifacts/horizon-resource-*.log`. |

Known-good SHA-256 values:

```text
APK:          ea76ba170b18e700ef7559c98c62c61e3c73c6c52afa7e435f0d6c9c2f1420dd
Resource ZIP: e3c2cab367915386b1118e7432c7e5c7a66f192ac51c08a5c34e31a0df56ab22
```

These identify the delivered artifacts, not a promise that every subsequent rebuild is byte-identical.

### Source of truth versus generated files

Keep `app/` source, Gradle configuration/wrapper, `scripts/`, `patches/horizon-android.patch`, licenses, and documentation. `engine/` is an ignored Git checkout of upstream with the Android patch applied. Its modified files and new `crates/horizon-android/` directory are expected; do not discard them as accidental dirt.

`app/src/main/jniLibs/`, `engine/`, build outputs, and `artifacts/` are excluded by `.gitignore`. Changes made only inside `engine/` will be lost from a source-only handoff unless captured in the patch. The outer Android project is currently a normal directory, **not its own initialized Git repository**; `engine/` has Git metadata. Before publishing or setting up version control, review exclusions and third-party notices.

The original `/tmp/horizon-upstream`, temporary Minecraft-layout adapter, and temporary JVM validation helpers have already disappeared. Do not depend on those paths or their build caches. Everything needed to rebuild the Android source is in this project plus the pinned upstream. On the original host, disk space was approximately 6.7 GB free at this handoff: check capacity before a fresh native build or duplicating resource trees, and never delete the user's installations or worlds to make room.

The parent ChatGPT project has a read-only `sources/` directory and unrelated projects. Keep work inside `horizon-android/`; do not change those other files.

## 4. Architecture and invariants

```text
LauncherActivity / Compose launcher
  ├─ imports resource ZIP and edits launch settings / control layout
  └─ writes atomic active-launch.json, then opens HorizonGameActivity
       └─ separate Android process: dev.horizon.android:game
            ├─ GameActivity native surface → libmain.so → Horizon Rust game
            └─ Compose touch overlay → authenticated loopback TCP → virtual pad

Rust → atomic runtime-status.json → game activity / launcher feedback
```

### Native host and lifecycle

- `crates/horizon-android` builds the `main` cdylib, packaged as `libmain.so`. Its `android_main` receives `AndroidApp`, initializes logging/crash handling/allocator, reads the launch description, and invokes the Rust client.
- Kotlin must write launch configuration **before** `startActivity`: native execution can begin inside `GameActivity.onCreate`.
- `AndroidApp.internal_data_path()` already represents Android's `filesDir`. Adding another `files/` component breaks the handoff.
- The dedicated `:game` process is intentional: Winit supports only one event loop per process. On destruction, Kotlin releases controls, calls `super.onDestroy()` to unwind/join the native activity, then terminates **only the game process**. Removing this isolation can break the second launch. Do not kill the launcher process.
- Android startup processes lifecycle events while resources load; do not restore desktop-style blocking startup merely because a control endpoint is enabled.
- Suspend drops the rendering surface, releases input and pauses the integrated server; resume recreates the surface and resets timing. These paths exist, but their full device acceptance remains to be recorded.
- Native menu touches are mapped to mouse input. Gameplay touch controls come from the overlay.
- Runtime status is `running`, `completed`, or `failed`, with an optional message. A crash before status/logging initialization may leave no useful result; a native panic/abort or Android process death after startup can leave stale `running`. Check Logcat rather than treating that status as proof the process is still alive.
- Back uses the game's pause/back action when connected. Before connection or during disconnection, it offers a close-game confirmation instead of silently doing nothing.

### Touch controls

The overlay is a Compose view over the native surface **inside the same activity window**. It requires neither accessibility access nor draw-over-other-apps permission.

`ControlBridge` reads the private `control.json` endpoint descriptor, connects to `127.0.0.1`, authenticates using its token, and uses Horizon's existing commands, such as `pad south down`, `pad south up`, `pad stick left x y`, and `pad release`.

Preserve ordered button edges, shared-binding reference counts, coalesced stick samples, and input release on touch cancellation, focus loss, backgrounding, editing, and reconnect. Replaying old presses after reconnect can create stuck movement or unintended actions. Do not replace the private endpoint with an unauthenticated public socket.

Layouts use normalized center coordinates and size relative to the shorter screen edge. The editor supports add/remove, drag, resize, opacity, action mapping, reset, Save, and Cancel. Its JSON files are atomically written and reread across processes. The current UI uses the landscape layout and exposes one local profile, `default`; a complete portrait/tablet profile workflow is not implemented.

### Storage

Under Android's app-private `filesDir`:

```text
horizon/
  active-launch.json             absolute game/resources/home/endpoint paths + distance
  runtime-status.json
  profiles/default/
    game/                       worlds and options
    resources/                  imported prepared resources
    home/                       native logs and crash reports
    control.json                runtime endpoint descriptor; not a saved layout
    controls-landscape.json     saved touch layout
```

SharedPreferences are used only for launcher settings. Do not use them as an unsynchronized cross-process launch channel. Backup is disabled; uninstalling or clearing app data can lose worlds and layouts. Keep the application ID and signing key stable for in-place updates, and back up valuable worlds before testing risky changes.

A fresh developer machine normally generates a different debug signing key. Its APK may fail `adb install -r` with a signature mismatch even when the code is correct. Do not reflexively uninstall to fix this: first arrange a safe world/layout backup and a consistent signing strategy.

## 5. Code map: where to make changes

Kotlin paths are under `app/src/main/java/dev/horizon/android/`.

| File / area | Responsibility |
| --- | --- |
| `LauncherActivity.kt`, `LauncherScreen.kt` | Launcher entry, Play/Controls/Settings, readiness and import UI. |
| `HorizonTheme.kt` | Material 3 theme, wallpaper-derived colors, light/dark appearance. |
| `ProfileStorage.kt` | Paths, launch/result JSON, settings, launch preflight. |
| `PreparedBundleImporter.kt` | Transactional ZIP extraction, path/size protection, cancellation and rollback. |
| `HorizonGameActivity.kt` | Native activity, isolated process lifecycle, overlay attachment, system bars/Back. |
| `GameOverlay.kt`, `ControlLayout.kt` | Touch widgets, editor, bindings and layout persistence. |
| `ControlBridge.kt` | Controller transport, queueing, authentication and reconnect handling. |
| `app/src/test/java/dev/horizon/android/` | Importer, controller transport and launcher UI tests. |
| `app/src/main/AndroidManifest.xml` | Permissions, nonexported `:game` activity, landscape/configuration handling. |
| `app/build.gradle.kts`, `app/proguard-rules.pro` | Android packaging, ABI discovery, native-runtime guard and JNI keep rules. |
| `patches/horizon-android.patch` | Durable representation of **all** Rust/upstream Android changes. |

Inside the generated engine, start with `crates/horizon-android/src/lib.rs`, then `crates/horizon-client/src/game.rs`. Other patched areas include integrated-server suspension, pad release, missing-resource UI, rendering-surface recreation, Android clipboard/HDR stubs, feature/dependency gating, and exposing the desktop client's game module as a library. Existing renderer, world logic, and integrated server remain upstream code.

## 6. Rebuild and verify

### Known working versions

| Component | Version / setting |
| --- | --- |
| Android compile / target / minimum SDK | 36 / 36 / 26. Minimum is a declaration, not evidence of testing every older phone. |
| Shipped ABI | `arm64-v8a`; x86_64 is optional, not delivered or device-validated. |
| NDK / SDK build tools | `27.2.12479018` / `35.0.0`. |
| Rust / cargo-ndk used | `1.99.1` / `4.1.2`; use the lockfile. |
| Gradle / Android Gradle Plugin | `8.14.3` / `8.11.0`. |
| Kotlin / Compose plugin | `2.1.20`. |
| Compose BOM / Material 3 | `2025.05.01` / explicit `1.4.0`. |
| Native activity pairing | `games-activity:4.4.0`, `android-activity:0.6.1`, Winit `0.30.13`. |
| Graphics | WGPU `30.0.1`. |
| Java | JDK 17+ for Android builds (21 used); **JDK 25** for preparing game resources. |
| Application | `dev.horizon.android`, version code 1, version name `0.1.0-dev`. |

The GameActivity Java/native versions are intentionally paired. Do not independently upgrade one side just to remove a dependency warning.

On the original machine, the SDK is `/home/msstore/Documents/Codex/2026-09-16/hi/work/android-sdk`, with the pinned NDK under its `ndk/` directory. Its SDK manager is under `cmdline-tools/bin/`; a fresh standard installation may use `cmdline-tools/latest/bin/` instead.

From the Android project root, with an installed SDK/NDK and Rust:

```bash
export ANDROID_HOME=/path/to/Android/Sdk
rustup target add aarch64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
bash scripts/sync-engine.sh

# Matches the development build's optimization strategy; includes audio by default.
HORIZON_BUILD_PROFILE=fast CARGO_BUILD_JOBS=3 \
  CARGO_PROFILE_FAST_DEBUG=0 CARGO_PROFILE_FAST_INCREMENTAL=false \
  bash scripts/build-native.sh

./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
bash scripts/verify-apk.sh
```

Important build behavior:

- **Gradle does not compile Rust.** It finds libraries already in `app/src/main/jniLibs/` at configuration time. After Rust edits, run the native build before starting a new Gradle packaging command. The native guard checks presence, not whether a library is fresh.
- `scripts/check-native.sh` is a faster type-check, not a replacement for linking and packaging.
- Native build defaults to upstream `release` with LTO unless `HORIZON_BUILD_PROFILE=fast` is set. `HORIZON_AUDIO=0` isolates audio issues; the shipped build used audio.
- `HORIZON_ABIS="arm64-v8a x86_64"` adds an emulator ABI. If narrowing the ABI list later, move old unrequested ABI outputs out of `jniLibs`; the script refuses to silently include them.
- The linker sets 16 KiB page alignment. Legacy JNI packaging/extraction supports the launcher's `nativeLibraryDir` readiness check; do not change that packaging without revisiting the check.
- `verify-apk.sh` extracts the **packaged** libraries and validates architecture, LOAD alignment, three GameActivity exports, ZIP alignment, and APK signature. It currently assumes Linux tools and verifies arm64, not every possible extra ABI.
- `-PallowMissingNative=true` is only a UI/source-development escape hatch. Such a build is not a game release. Normal APK and AAB tasks require a native runtime.
- Only a debug APK has been delivered. Release minification, signing, distribution, and a release AAB still need their own validation. Never publish or commit a private signing key.

For Kotlin-only changes with the existing runtime, run the Gradle tests/lint/assemble commands directly. UI tests use Robolectric/API 35; they are not Android 16 instrumentation tests. Reports and screenshots are under `app/build/reports/`.

### Keeping Rust edits reproducible

1. Verify the engine's HEAD is the pinned commit. Review its current diff before editing.
2. Make the Android change in `engine/` and validate it.
3. Regenerate the **complete** Android patch relative to the pin, including new files. Ordinary `git diff` omits untracked files; account for every intended new file (for example, intent-to-add `crates/horizon-android/`) before generating a candidate patch.
4. Test that candidate on a **separate fresh checkout** at the same commit. Check application, resulting file contents, and `git diff --check`; do not reset or delete the working engine to perform this test.
5. Replace the durable patch only after review, rebuild native, package, verify, and test on device. Preserve unrelated user edits.

`sync-engine.sh` is idempotent but is **not** an equivalence checker: its successful reverse-patch check can coexist with additional local edits. A successful sync alone does not prove the patch captures the engine in use.

When deliberately upgrading upstream, reconcile the pin in `sync-engine.sh`, Gradle's `ENGINE_COMMIT`, documentation, patch/Cargo.lock, Java/native activity ABI, and prepared-resource compatibility together. Keep the currently working APK/resources available for comparison.

## 7. Game resources and private data

The current ZIP is ready to import; there is no need to prepare it again just to test the app. Select the ZIP with **Import game files**, without extracting it first. Allow roughly 1.5 GB free for the ZIP and installed data, plus room for worlds.

It contains **28,167 files / 624,164,835 uncompressed bytes**, including **4,962 sound files**. The source is the user's matching Prism client/assets/libraries plus their official-launcher version manifest. The missing matching server JAR was downloaded from Mojang and checksum-verified. Original installations were not modified. Credentials, account data, saves, mods, and preparation logs are not in the ZIP.

The resources are proprietary Minecraft-derived content, **not** covered by Horizon's MIT source license. Keep the ZIP, prepared tree, imported JARs and resource caches private. Do not add them to Git, a public release, source archive, or APK. The code's own licenses and bundled third-party notices must still accompany code/binary distribution.

To regenerate on another computer, use an owned Java 26.3 installation, its matching server JAR, JDK 25, and the same pinned preparer. Follow [README.md](README.md). On this computer, `/home/msstore/.minecraft` has the version manifest but lacked the client/assets/libraries; those were found under `/home/msstore/.local/share/PrismLauncher/`. Do not assume passing `.minecraft` alone is sufficient. A temporary adapter exposed the existing files in the official launcher's layout without changing either installation; recreate it if necessary. The cached imported JARs and prepared tree remain under `engine/.local/`.

For the original machine's current installations, this recreates the adapter. Run from `engine/`, with JDK 25 available, only when preparation needs rerunning; the cached server JAR used here already exists:

```bash
horizon_input="$(mktemp -d /tmp/horizon-resource-input.XXXXXX)"
mkdir -p "$horizon_input/versions/26.3"
ln -s /home/msstore/.minecraft/versions/26.3/26.3.json "$horizon_input/versions/26.3/26.3.json"
ln -s /home/msstore/.local/share/PrismLauncher/libraries/com/mojang/minecraft/26.3/minecraft-26.3-client.jar "$horizon_input/versions/26.3/26.3.jar"
ln -s /home/msstore/.local/share/PrismLauncher/libraries "$horizon_input/libraries"
ln -s /home/msstore/.local/share/PrismLauncher/assets "$horizon_input/assets"
cargo run --locked --profile fast -p horizon-prepare -- \
  --prepare --minecraft-dir "$horizon_input" \
  --server-jar .local/minecraft/26.3/jars/server.jar
cargo run --locked --profile fast -p horizon-prepare -- \
  sounds --minecraft-dir "$horizon_input"
```

Package only files inside `.local/horizon-client/26.3/`, excluding preparation logs and the `classes/` / `client-classes/` exporter directories. Write a new archive when experimenting; preserve the known-good ZIP. Do not ZIP the adapter or launcher directories.

Importer requirements:

- Root `horizon-resources.json`, not `26.3/horizon-resources.json` or `./horizon-resources.json`.
- Bare relative paths; no traversal, backslashes, duplicate normalized destinations, or enclosing directory.
- At most 50,000 ZIP entries, 2 GiB per file, 6 GiB total expanded data; at least 64 MiB reserve storage during extraction.
- Readiness currently checks manifest **existence**, not the complete manifest schema/content or every required asset. A malformed bundle can pass that initial gate and fail in Rust. Keep the staged import/rollback behavior when improving validation.

The resource checks passed individual manifest hashes, indexed-asset hashes, JAR resource checks, ZIP CRC, and an extraction using the actual compiled importer. One caveat remains: Prism's newer asset index differs from the preparer's fixed CI fixture, and the whole-output regression fingerprint did **not** match. The exact full cause was not established; no expected hash or source version was changed to hide it. See the resource validation report for details. The user's subsequent working-game report is encouraging but does not resolve that tooling discrepancy.

## 8. Known limitations and next priorities

Implemented is not the same as exhaustively validated. The remaining work is to harden and extend a working baseline, not to replace it with another scaffold.

### First: capture a repeatable phone acceptance baseline

Record phone model/GPU, Android build, memory, chosen render distance, test duration and APK hash. Then run and record:

1. Create/load a world; move and look together; jump/attack/use while moving; inventory, hotbar, pause and menu touches.
2. Save, quit, reload the world, then launch again without force-stopping the launcher.
3. Home, lock/unlock, app switching and background return: surface recovers, controls release, no stuck keys, saves remain intact. Test OS process death separately with a disposable world.
4. Edit control positions, size, opacity and binding; Save survives restart, Cancel discards changes, notch/navigation insets stay usable.
5. Check audio, FPS, startup latency, RAM and heat over a longer session. Start at the default 8-chunk distance.

These details have not yet been supplied by the user. Reuse the longer checklist in `VALIDATION.md`, and date new results rather than converting a general success report into checked-off cases.

### Then: reliability and usability

- World backup/export and diagnostic-log export before broader testing; import progress/cancellation feedback and clearer startup failures.
- Android audio-focus handling, fuller Android IME integration, clipboard behavior, and physical controller support. Current audio is compiled in, keyboard helper is limited, Android clipboard is in-memory, and physical-gamepad integration is not enabled.
- Test another Adreno/Mali device and larger-screen layouts. Android 16 does not by itself guarantee GPU compatibility: the renderer assumes 2,048 texture-array layers and `Depth32Float`. Capability fallbacks require probing and corresponding renderer changes, not a blind format switch.
- Tablet/portrait/adaptive layout support; Android 16 can ignore forced landscape on large screens.
- Profile picker, on-device resource preparation, and legitimate account sign-in are future features, not present functionality. Do not promise authenticated multiplayer parity.
- Establish version control/CI, repeatable device tests, a release signing/update strategy, and release-build verification. Keep private assets and keys outside public automation artifacts.

Upstream's own gameplay gaps still apply; distinguish an upstream engine issue from an Android host/input/rendering issue before changing the port.

## 9. Debugging and safe continuation

For a USB-connected device with debugging enabled:

```bash
adb devices -l
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell run-as dev.horizon.android cat files/horizon/runtime-status.json
adb exec-out run-as dev.horizon.android tar \
  -C files/horizon/profiles/default/home -cf - . > horizon-logs.tar
```

The debug build permits `run-as`; a future non-debug release may not. Use Android Studio Logcat for `dev.horizon.android:game` if loading fails before a status file appears. Do not assume there is a connected device merely because the user tested independently. Logs can contain local paths, world and server details; review before sharing.

Common starting points:

- **Engine unavailable:** confirm arm64 `libmain.so` is actually in the installed APK and extracted; a source-only build cannot run the game.
- **Import rejected:** check ZIP root paths and limits, then manifest and resource compatibility.
- **Starting/reconnecting indefinitely:** inspect native logs/status, endpoint creation and token authentication before changing touch widgets.
- **First launch works, second fails:** inspect isolated-process destruction and Winit event-loop lifetime.
- **Black screen after resume:** inspect surface recreation and suspend state, not just launcher UI.
- **Stuck or dropped inputs:** inspect per-pointer cancellation, queue ordering/refcounts, releases and reconnect behavior.
- **Changes seem absent:** remember that Gradle can package an old native library; verify the built artifact and update the saved source patch.

Recommended first action for a new developer: read this file and `VALIDATION.md`, preserve the known-good APK/resource ZIP, review source status, reproduce the existing build, then work through the targeted device checklist before large dependency upgrades or architectural changes. Refresh this handoff and any shared source archive when handing the project to the next person.
