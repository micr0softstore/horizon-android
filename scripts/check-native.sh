#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
bash "$project_dir/scripts/sync-engine.sh"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$sdk_dir/ndk/27.2.12479018}"
cd "$project_dir/engine"
cargo ndk -t arm64-v8a --platform 26 check --locked -p horizon-android --features audio
