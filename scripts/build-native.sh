#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$project_dir/scripts/sync-engine.sh"
command -v cargo >/dev/null || { echo "Install Rust with rustup first." >&2; exit 1; }
cargo ndk --version >/dev/null || { echo "Install cargo-ndk: cargo install cargo-ndk --locked" >&2; exit 1; }

sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    if [[ -z "$sdk_dir" || ! -d "$sdk_dir/ndk/27.2.12479018" ]]; then
        echo "Set ANDROID_HOME and install ndk;27.2.12479018, or set ANDROID_NDK_HOME." >&2
        exit 1
    fi
    export ANDROID_NDK_HOME="$sdk_dir/ndk/27.2.12479018"
fi

# Separate native code per ABI; do not advertise ABIs that lack the engine.
read -r -a build_abis <<< "${HORIZON_ABIS:-arm64-v8a}"
ndk_args=()
for abi in "${build_abis[@]}"; do
    case "$abi" in
        arm64-v8a) rustup target add aarch64-linux-android ;;
        x86_64) rustup target add x86_64-linux-android ;;
        *) echo "Unsupported ABI: $abi (use arm64-v8a or x86_64)" >&2; exit 1 ;;
    esac
    ndk_args+=(-t "$abi")
done
# Gradle discovers ABIs from these files. Do not silently package an old engine
# for an ABI omitted from this build, and do not delete a user's prior output.
for existing in "$project_dir"/app/src/main/jniLibs/*/libmain.so; do
    [[ -f "$existing" ]] || continue
    existing_dir="${existing%/libmain.so}"
    existing_abi="${existing_dir##*/}"
    if [[ " ${build_abis[*]} " != *" $existing_abi "* ]]; then
        echo "An unrequested ABI remains at $existing_dir. Move it outside jniLibs or include $existing_abi in HORIZON_ABIS before building." >&2
        exit 1
    fi
done
build_profile="${HORIZON_BUILD_PROFILE:-release}"
feature_args=()
if [[ "${HORIZON_AUDIO:-1}" == "1" ]]; then feature_args+=(--features audio); fi

# Android 15/16 devices may use 16 KB memory pages. NDK r27 needs this
# explicitly for Rust's final shared-library link (r28+ does it by default).
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-4}"
cd "$project_dir/engine"
cargo ndk "${ndk_args[@]}" --platform 26 -o "$project_dir/app/src/main/jniLibs" \
    build --locked --profile "$build_profile" -p horizon-android "${feature_args[@]}"
echo "Native runtime ready. Build the APK with ./gradlew assembleDebug."
