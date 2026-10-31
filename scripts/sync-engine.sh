#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
engine_dir="$project_dir/engine"
engine_commit="073afd484e8667f597790eff62321c35f07e7ba7"
patch_file="$project_dir/patches/horizon-android.patch"

if [[ ! -f "$patch_file" ]]; then
    echo "Missing Android source patch: $patch_file" >&2
    exit 1
fi
if [[ ! -e "$engine_dir" ]]; then
    git clone --no-checkout "${HORIZON_UPSTREAM_SOURCE:-https://github.com/horizon-rust/horizon.git}" "$engine_dir"
    git -C "$engine_dir" checkout --detach "$engine_commit"
fi
if [[ "$(git -C "$engine_dir" rev-parse HEAD)" != "$engine_commit" ]]; then
    echo "engine/ must be at $engine_commit; refusing to change an existing checkout." >&2
    exit 1
fi
if git -C "$engine_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
    echo "Horizon Android patch is already applied."
elif [[ -n "$(git -C "$engine_dir" status --porcelain)" ]]; then
    echo "engine/ has local changes. Review them before applying the Android patch." >&2
    exit 1
else
    git -C "$engine_dir" apply --check "$patch_file"
    git -C "$engine_dir" apply "$patch_file"
    echo "Applied Android port to Horizon $engine_commit."
fi
