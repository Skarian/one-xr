#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
submodule_path="$repo_root/references/One-Pro-IMU-Retriever-Demo"
patch_dir="$repo_root/references/patches/one-pro-imu-retriever-demo"
expected_commit="16f45c73610b04b4da238895b46733794a9f5944"

if [[ ! -d "$submodule_path/.git" && ! -f "$submodule_path/.git" ]]; then
  echo "missing submodule checkout at $submodule_path" >&2
  exit 1
fi

current_commit="$(git -C "$submodule_path" rev-parse HEAD)"
if [[ "$current_commit" != "$expected_commit" ]]; then
  echo "expected submodule commit $expected_commit but found $current_commit" >&2
  exit 1
fi

shopt -s nullglob
patches=("$patch_dir"/*.patch)
shopt -u nullglob

if [[ "${#patches[@]}" -eq 0 ]]; then
  echo "no patch files found under $patch_dir" >&2
  exit 1
fi

for patch in "${patches[@]}"; do
  if git -C "$submodule_path" apply --check --whitespace=nowarn "$patch" >/dev/null 2>&1; then
    git -C "$submodule_path" apply --whitespace=nowarn "$patch"
    echo "applied $(basename "$patch")"
    continue
  fi

  if git -C "$submodule_path" apply --reverse --check --whitespace=nowarn "$patch" >/dev/null 2>&1; then
    echo "already applied $(basename "$patch")"
    continue
  fi

  echo "unable to apply $(basename "$patch") cleanly" >&2
  exit 1
done
