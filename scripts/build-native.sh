#!/bin/bash
# Build libptts_ffi.so (Phonon's C interface, from the pinned third_party/xn-ptts submodule) into
# app/src/main/jniLibs: arm64-v8a for phones, x86_64 for emulators on x86 machines.
#
# Needs Rust, cargo-ndk (`cargo install cargo-ndk`) and the Android NDK. The NDK is found from
# ANDROID_NDK_HOME, or else the newest one under $ANDROID_HOME/ndk (or ~/Library/Android/sdk/ndk).
#
# ARM64_FEATURES= ./scripts/build-native.sh builds for pre-ARMv8.2 phones (no dotprod/fp16), slower.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/xn-ptts"
OUT="$ROOT/app/src/main/jniLibs"

[ -f "$SRC/Cargo.toml" ] || git -C "$ROOT" submodule update --init third_party/xn-ptts

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
  ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
  [ -n "$ANDROID_NDK_HOME" ] || { echo "No NDK found: set ANDROID_NDK_HOME" >&2; exit 1; }
  export ANDROID_NDK_HOME
fi
echo "NDK: $ANDROID_NDK_HOME"

# xn picks its kernels at compile time, so the CPU features are fixed here rather than detected on
# the phone. dotprod and fp16 (ARMv8.2) are on nearly every phone since 2019.
ARM64_FEATURES="${ARM64_FEATURES-+dotprod,+fp16}"
# Replace the submodule's target-cpu=native (the build machine). Align to 16 KB pages, which
# Google Play requires from Android 15.
COMMON="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$COMMON -C target-cpu=generic${ARM64_FEATURES:+ -C target-feature=$ARM64_FEATURES}"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="$COMMON -C target-cpu=x86-64"
unset RUSTFLAGS CARGO_ENCODED_RUSTFLAGS

ABIS="${ABIS:-arm64-v8a x86_64}"
TARGETS=()
for abi in $ABIS; do
  TARGETS+=(-t "$abi")
  case "$abi" in
    arm64-v8a) rustup target add aarch64-linux-android >/dev/null ;;
    x86_64) rustup target add x86_64-linux-android >/dev/null ;;
  esac
done

# cargo-ndk skips the copy when the existing file is newer, which could keep a stale library.
rm -rf "$OUT"
cd "$SRC"
cargo ndk "${TARGETS[@]}" -o "$OUT" build --release -p ptts-ffi
du -sh "$OUT"/*/libptts_ffi.so
