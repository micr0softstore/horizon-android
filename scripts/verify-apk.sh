#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
apk="${1:-$project_dir/app/build/outputs/apk/debug/app-debug.apk}"

fail() {
    echo "verify-apk: $*" >&2
    exit 1
}

sdk_dir=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
        sdk_dir="$candidate"
        break
    fi
done
[[ -n "$sdk_dir" ]] || fail "set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK"
[[ -d "$sdk_dir/build-tools" ]] || fail "Android SDK build-tools directory is missing: $sdk_dir/build-tools"

# Keep this in step with build-native.sh so the verifier reads ELF metadata
# with the same NDK release that linked the library.
ndk_dir="${ANDROID_NDK_HOME:-$sdk_dir/ndk/27.2.12479018}"
readelf="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
[[ -x "$readelf" ]] || fail "NDK llvm-readelf missing: $readelf"

sdk_tool() {
    local tool="$1"
    local found
    found="$(
        find "$sdk_dir/build-tools" -mindepth 2 -maxdepth 2 -type f -name "$tool" -print \
            | LC_ALL=C sort -V \
            | tail -n 1
    )"
    [[ -n "$found" && -x "$found" ]] || fail "Android SDK build-tools $tool is missing below $sdk_dir/build-tools"
    printf '%s\n' "$found"
}

apksigner="$(sdk_tool apksigner)"
zipalign="$(sdk_tool zipalign)"

verify_arm64_library() {
    local entry="$1"
    local library="$2"
    local elf_header
    local program_headers
    local alignment
    local -a load_alignments

    echo "Inspecting packaged $entry"
    elf_header="$("$readelf" --file-header "$library")"
    printf '%s\n' "$elf_header"
    grep -Eq '^[[:space:]]*Class:[[:space:]]+ELF64[[:space:]]*$' <<< "$elf_header" \
        || fail "$entry is not a 64-bit ELF"
    grep -Eq '^[[:space:]]*Type:[[:space:]]+DYN[[:space:]]+' <<< "$elf_header" \
        || fail "$entry is not an ELF shared library"
    grep -Eq '^[[:space:]]*Machine:[[:space:]]+AArch64[[:space:]]*$' <<< "$elf_header" \
        || fail "$entry is not arm64/AArch64"

    program_headers="$("$readelf" --program-headers --wide "$library")"
    printf '%s\n' "$program_headers"
    mapfile -t load_alignments < <(awk '$1 == "LOAD" { print $NF }' <<< "$program_headers")
    ((${#load_alignments[@]} > 0)) || fail "$entry has no ELF LOAD segments"
    for alignment in "${load_alignments[@]}"; do
        [[ "$alignment" =~ ^0[xX][[:xdigit:]]+$ ]] \
            || fail "could not parse $entry ELF LOAD alignment: $alignment"
        if ((alignment < 0x4000)); then
            fail "$entry ELF LOAD alignment $alignment is below 0x4000 (16 KiB)"
        fi
    done
    echo "$entry: all ${#load_alignments[@]} ELF LOAD segments have >= 0x4000 alignment."
}

verify_game_activity_symbols() {
    local library="$1"
    local dynamic_symbols
    local symbol
    local -a required_symbols=(
        android_main
        GameActivity_onCreate
        Java_com_google_androidgamesdk_GameActivity_initializeNativeCode
    )

    # GameActivity loads libmain.so and resolves these exported entry points.
    # It does not use NativeActivity's ANativeActivity_onCreate or JNI_OnLoad.
    dynamic_symbols="$("$readelf" --dyn-syms --wide "$library")"
    for symbol in "${required_symbols[@]}"; do
        if ! awk -v symbol="$symbol" '
            $4 == "FUNC" && $5 == "GLOBAL" && $6 == "DEFAULT" && $7 != "UND" && $NF == symbol {
                found = 1
                print
            }
            END { exit !found }
        ' <<< "$dynamic_symbols"; then
            fail "required exported GameActivity entry symbol is missing: $symbol"
        fi
    done
    echo "Required GameActivity entry symbols are exported."
}

[[ -f "$apk" ]] || fail "APK missing: $apk"
command -v unzip >/dev/null || fail "unzip is required to inspect the packaged native library"
unzip -tqq "$apk" >/dev/null

main_entry="lib/arm64-v8a/libmain.so"
zip_entries="$(unzip -Z1 "$apk")"
mapfile -t arm64_entries < <(
    awk '/^lib\/arm64-v8a\/[^/]+\.so$/ { print }' <<< "$zip_entries" | LC_ALL=C sort
)
((${#arm64_entries[@]} > 0)) || fail "APK contains no arm64 native libraries"
main_count=0
for entry in "${arm64_entries[@]}"; do
    [[ "$entry" == "$main_entry" ]] && ((main_count += 1))
done
[[ "$main_count" == "1" ]] || fail "expected exactly one $main_entry in $apk; found $main_count"

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/horizon-verify-apk.XXXXXX")"
trap 'rm -rf -- "$work_dir"' EXIT
for index in "${!arm64_entries[@]}"; do
    entry="${arm64_entries[$index]}"
    packaged_lib="$work_dir/$index-${entry##*/}"
    unzip -p "$apk" "$entry" > "$packaged_lib"
    [[ -s "$packaged_lib" ]] || fail "packaged $entry is empty"
    verify_arm64_library "$entry" "$packaged_lib"
    if [[ "$entry" == "$main_entry" ]]; then
        verify_game_activity_symbols "$packaged_lib"
    fi
done
echo "Verified ${#arm64_entries[@]} packaged arm64 native libraries (not app/src/main/jniLibs)."

# Check the APK that will actually be installed: all ordinary ZIP entries are
# aligned and uncompressed native libraries are 16 KiB page-aligned.
echo "Checking 16 KiB APK ZIP alignment with $zipalign"
"$zipalign" -c -P 16 4 "$apk"

echo "Verifying APK signature with $apksigner"
"$apksigner" verify --verbose --print-certs --min-sdk-version 26 -Werr "$apk"
echo "APK verification passed: $apk"
