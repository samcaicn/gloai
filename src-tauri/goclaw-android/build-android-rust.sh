#!/bin/bash

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_DIR="$(dirname "$SCRIPT_DIR")/gen/android"
JNILIBS_DIR="$ANDROID_DIR/app/src/main/jniLibs"

echo "Building goclaw Rust library for Android..."

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.1.10909197}"
if [ ! -d "$ANDROID_NDK_HOME" ]; then
    ANDROID_NDK_HOME="$HOME/Library/Android/sdk/ndk/26.1.10909197"
fi

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "Error: ANDROID_NDK_HOME not found."
    echo "Please set ANDROID_NDK_HOME environment variable or install NDK."
    echo "Example: export ANDROID_NDK_HOME=\$HOME/Library/Android/sdk/ndk/26.1.10909197"
    exit 1
fi

echo "Using NDK: $ANDROID_NDK_HOME"

API_LEVEL=24

rustup target add aarch64-linux-android
rustup target add armv7-linux-androideabi
rustup target add i686-linux-android
rustup target add x86_64-linux-android

mkdir -p "$JNILIBS_DIR/arm64-v8a"
mkdir -p "$JNILIBS_DIR/armeabi-v7a"
mkdir -p "$JNILIBS_DIR/x86"
mkdir -p "$JNILIBS_DIR/x86_64"

cd "$SCRIPT_DIR"

export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android24-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/armv7a-linux-androideabi24-clang"
export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/i686-linux-android24-clang"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/x86_64-linux-android24-clang"

if [ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64" ]; then
    export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
    export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi24-clang"
    export CARGO_TARGET_I686_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/i686-linux-android24-clang"
    export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/x86_64-linux-android24-clang"
fi

AR_path="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-ar"
if [ ! -f "$AR_path" ]; then
    AR_path="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-ar"
fi

export CARGO_TARGET_AARCH64_LINUX_ANDROID_AR="$AR_path"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_AR="$AR_path"
export CARGO_TARGET_I686_LINUX_ANDROID_AR="$AR_path"
export CARGO_TARGET_X86_64_LINUX_ANDROID_AR="$AR_path"

echo "Building for arm64-v8a..."
cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libgoclaw.so "$JNILIBS_DIR/arm64-v8a/libgoclaw.so"

echo "Building for armeabi-v7a..."
cargo build --release --target armv7-linux-androideabi
cp target/armv7-linux-androideabi/release/libgoclaw.so "$JNILIBS_DIR/armeabi-v7a/libgoclaw.so"

echo "Building for x86_64..."
cargo build --release --target x86_64-linux-android
cp target/x86_64-linux-android/release/libgoclaw.so "$JNILIBS_DIR/x86_64/libgoclaw.so"

echo "Building for x86..."
cargo build --release --target i686-linux-android
cp target/i686-linux-android/release/libgoclaw.so "$JNILIBS_DIR/x86/libgoclaw.so"

echo "Done! Native libraries built in: $JNILIBS_DIR"
ls -la "$JNILIBS_DIR"/*/
