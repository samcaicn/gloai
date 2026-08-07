#!/usr/bin/env bash
#
# 构建 colearn Android launcher APK。
# 流程: javac -> d8 -> aapt package -> add classes.dex(native lib) -> zipalign -> apksigner 签名
# 约束: classes.dex 必须位于 APK 根目录; Go 二进制作为 native lib(lib/arm64-v8a/*.so)打入,
#      在 Android 10+ 特别是部分厂商 ROM(如 ColorOS) 上绕过 app data dir exec 限制。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$SCRIPT_DIR/../android/app"
SRC_DIR="$APP_DIR/src/main/java"
RES_DIR="$APP_DIR/src/main/res"
ASSETS_DIR="$APP_DIR/src/main/assets"
MANIFEST="$APP_DIR/src/main/AndroidManifest.xml"
BUILD_DIR="$APP_DIR/build"
OUT_APK="$APP_DIR/colearn.apk"

KEY_ALIAS="colearn"
STORE_PASS="android"
KEY_PASS="android"

mkdir -p "$BUILD_DIR"

# debug.keystore: 首次生成(复用保证签名稳定)
if [ ! -f "$BUILD_DIR/debug.keystore" ]; then
  echo "[*] 生成 debug.keystore (alias=$KEY_ALIAS) ..."
  if ! command -v keytool >/dev/null 2>&1; then
    echo "[!] 需 JDK/keytool (apt-get install openjdk-21-jdk-headless)"; exit 1
  fi
    keytool -genkeypair -v -keystore "$BUILD_DIR/debug.keystore" -alias "$KEY_ALIAS" \
      -keyalg RSA -keysize 2048 -storetype PKCS12 \
      -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
      -dname "CN=colearn,OU=colearn,O=colearn,L=,S=,C=" -validity 10000
fi

# ---------------------------------------------------------------------------
# 1) 定位/安装 Android SDK
# ---------------------------------------------------------------------------
if [ -z "${ANDROID_HOME:-}" ]; then
  for p in /opt/android-sdk /usr/lib/android-sdk "$HOME/android-sdk"; do
    [ -d "$p" ] && { ANDROID_HOME="$p"; break; }
  done
fi
if [ -z "${ANDROID_HOME:-}" ] || [ ! -d "${ANDROID_HOME:-}" ]; then
  echo "[*] 未找到 Android SDK,在线安装 cmdline-tools ..."
  export ANDROID_HOME="$HOME/android-sdk"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -sL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdtools.zip
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
  yes | "$SDKM" --sdk_root="$ANDROID_HOME" --licenses >/dev/null
  "$SDKM" --sdk_root="$ANDROID_HOME" "platform-tools" "build-tools;35.0.0" "platforms;android-34"
fi
export ANDROID_HOME
BT_DIR=$(ls -d "$ANDROID_HOME"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
BT_DIR="${BT_DIR%/}"
AAPT="$BT_DIR/aapt"
D8="$BT_DIR/d8"
ZIPALIGN="$BT_DIR/zipalign"
APKSIGNER="$BT_DIR/apksigner"

# Android jar (android.jar)
AJAR="$ANDROID_HOME/platforms/android-34/android.jar"

# JDK
JAVAC=$(command -v javac 2>/dev/null || echo "")

# ---------------------------------------------------------------------------
# 2) 准备 Go 二进制 -> lib/arm64-v8a/libcolearn_launcher.so
# ---------------------------------------------------------------------------
LAUNCHER_BIN="$SCRIPT_DIR/../build/colearn-launcher-android-arm64"
if [ ! -f "$LAUNCHER_BIN" ]; then
  LAUNCHER_BIN="$ASSETS_DIR/colearn-launcher"
fi
if [ ! -f "$LAUNCHER_BIN" ]; then
  echo "[*] Go 二进制缺失,尝试 'make build-launcher-android-arm64' ..."
  (cd "$SCRIPT_DIR/.." && make build-launcher-android-arm64) || true
  LAUNCHER_BIN="$SCRIPT_DIR/../build/colearn-launcher-android-arm64"
fi
if [ ! -f "$LAUNCHER_BIN" ]; then
  echo "[!] Go 二进制不存在: $SCRIPT_DIR/../build/colearn-launcher-android-arm64"
  echo "[!] 请先运行 'make build-launcher-android-arm64' 或放二进制到 src/main/assets/colearn-launcher"
  exit 1
fi

rm -rf "$BUILD_DIR/nativelib"
mkdir -p "$BUILD_DIR/nativelib/lib/arm64-v8a"
cp "$LAUNCHER_BIN" "$BUILD_DIR/nativelib/lib/arm64-v8a/libcolearn_launcher.so"

# ---------------------------------------------------------------------------
# 3) javac -> jar -> d8 dex
# ---------------------------------------------------------------------------
echo "[*] javac ..."
rm -rf "$BUILD_DIR/obj" "$BUILD_DIR/dexdir" "$BUILD_DIR/classes.dex" "$BUILD_DIR/classes.jar"
mkdir -p "$BUILD_DIR/obj" "$BUILD_DIR/dexdir"
"$JAVAC" --release 8 -encoding UTF-8 -d "$BUILD_DIR/obj" -cp "$AJAR" $(find "$SRC_DIR" -name '*.java')
jar cf "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/obj" .
echo "[*] d8 -> dex ..."
"$D8" --release --lib "$AJAR" --output "$BUILD_DIR/dexdir" "$BUILD_DIR/classes.jar"
cp "$BUILD_DIR/dexdir/classes.dex" "$BUILD_DIR/classes.dex"

# ---------------------------------------------------------------------------
# 4) aapt package -> add classes.dex(native lib in APK root) -> native lib
# ---------------------------------------------------------------------------
echo "[*] aapt 打包 ..."
rm -f "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/colearn-aligned.apk" "$BUILD_DIR/colearn.apk"
AAPT_ARGS=(-F "$BUILD_DIR/unsigned.apk" -S "$RES_DIR" -I "$AJAR")
# 仅当 assets 目录存在时才打入 assets(avoid empty-missing -A dir breaking aapt)
if [ -d "$ASSETS_DIR" ]; then
  AAPT_ARGS+=(-A "$ASSETS_DIR")
fi
"$AAPT" package -f -M "$MANIFEST" "${AAPT_ARGS[@]}"
echo "[*] 加入 classes.dex ..."
cp "$BUILD_DIR/classes.dex" "$APP_DIR/classes.dex"
( cd "$APP_DIR" && "$AAPT" add "$BUILD_DIR/unsigned.apk" classes.dex )
rm -f "$APP_DIR/classes.dex"
echo "[*] 加入 native lib lib/arm64-v8a/libcolearn_launcher.so ..."
( cd "$BUILD_DIR/nativelib" && "$AAPT" add "$BUILD_DIR/unsigned.apk" lib/arm64-v8a/libcolearn_launcher.so )
echo "[*] zipalign ..."
"$ZIPALIGN" -f 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/colearn-aligned.apk"
echo "[*] apksigner 签名 ..."
"$APKSIGNER" sign \
  --ks "$BUILD_DIR/debug.keystore" --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
  --v4-signing-enabled \
  --out "$BUILD_DIR/colearn.apk" "$BUILD_DIR/colearn-aligned.apk"

mv "$BUILD_DIR/colearn.apk" "$OUT_APK"
if stat -f%z "$OUT_APK" >/dev/null 2>&1; then
  APK_SIZE=$(stat -f%z "$OUT_APK")
else
  APK_SIZE=$(stat -c%sz "$OUT_APK")
fi
echo "[*] 构建完成: $OUT_APK ($APK_SIZE bytes)"
echo "[*] dex/路径校验:"
unzip -l "$OUT_APK" | grep -E '\.dex$|lib/arm64'
"$APKSIGNER" verify -v "$OUT_APK" | grep -iE 'Verifies|v2 scheme|v3 scheme'
