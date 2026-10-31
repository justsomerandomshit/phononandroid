#!/bin/bash
# Build libptts_ffi.so (Phonon's C interface, from the pinned third_party/xn-ptts submodule) into
# app/src/main/jniLibs: arm64-v8a for phones, x86_64 for emulators on x86 machines.
#
# Needs Rust, cargo-ndk (`cargo install cargo-ndk`) and the Android NDK. The NDK is found from
# ANDROID_NDK_HOME, or else the newest one under $ANDROID_HOME/ndk (or ~/Library/Android/sdk/ndk).
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

# xn picks its kernels at compile time, so arm64 gets two builds and the app loads the one the
# phone's CPU can run (NativeLib.kt): libptts_ffi.so with dotprod and fp16 (ARMv8.2, nearly every
# phone since 2019), and libptts_ffi_v80.so for plain ARMv8.0 cores such as the Cortex-A73/A53
# in the Snapdragon 680 and 685, where the first one dies of SIGILL.
# Replace the submodule's target-cpu=native (the build machine). Align to 16 KB pages, which
# Google Play requires from Android 15.
COMMON="-C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$COMMON -C target-cpu=generic -C target-feature=+dotprod,+fp16"
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

if [[ " $ABIS " == *" arm64-v8a "* ]]; then
  # Its own target dir, so neither build invalidates the other's cache.
  BASE="$(mktemp -d)"
  CARGO_TARGET_DIR="$SRC/target/armv8.0" \
    CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="$COMMON -C target-cpu=generic" \
    cargo ndk -t arm64-v8a -o "$BASE" build --release -p ptts-ffi
  V80="$OUT/arm64-v8a/libptts_ffi_v80.so"
  mv "$BASE/arm64-v8a/libptts_ffi.so" "$V80"
  rm -rf "$BASE"
  # xn hardwires its dot product kernels at compile time, so the baseline build must contain no
  # sdot or udot. (Half float instructions do appear: xn-gemm checks for fp16 at run time.)
  OBJDUMP="$(ls "$ANDROID_NDK_HOME"/toolchains/llvm/prebuilt/*/bin/llvm-objdump | head -1)"
  BAD="$("$OBJDUMP" -d --no-show-raw-insn "$V80" | grep -E $'\t[su]dot\t' | head -5 || true)"
  if [ -n "$BAD" ]; then
    echo "libptts_ffi_v80.so uses dotprod (ARMv8.2):" >&2
    echo "$BAD" >&2
    exit 1
  fi
fi
du -sh "$OUT"/*/*.so
