#!/usr/bin/env bash
# Builds a static libcrypto (OpenSSL 3.5 LTS) for every Android ABI used by the app.
# Output: airplay/src/main/cpp/prebuilt/openssl/<abi>/{lib/libcrypto.a,include/}
#
# Usage: ANDROID_NDK_HOME=/path/to/ndk scripts/build-openssl-android.sh
set -euo pipefail

OPENSSL_VERSION="3.5.9"
MIN_API=23
ABIS=(armeabi-v7a arm64-v8a x86 x86_64)

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/airplay/src/main/cpp/prebuilt/openssl"
WORK="$ROOT/build/openssl-work"

: "${ANDROID_NDK_HOME:=${ANDROID_NDK_ROOT:-$HOME/Library/Android/sdk/ndk/27.2.12479018}}"
[ -d "$ANDROID_NDK_HOME" ] || { echo "NDK not found: $ANDROID_NDK_HOME" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;   # NDK ships universal binaries under this name
  Linux)  HOST_TAG=linux-x86_64 ;;
  *) echo "unsupported host" >&2; exit 1 ;;
esac
export ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
export PATH="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"

mkdir -p "$WORK"
TARBALL="$WORK/openssl-$OPENSSL_VERSION.tar.gz"
if [ ! -f "$TARBALL" ]; then
  curl -fsSL -o "$TARBALL" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
  curl -fsSL -o "$TARBALL.sha256" \
    "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz.sha256"
fi
EXPECTED="$(awk '{print $1}' "$TARBALL.sha256")"
if command -v sha256sum >/dev/null; then
  ACTUAL="$(sha256sum "$TARBALL" | awk '{print $1}')"
else
  ACTUAL="$(shasum -a 256 "$TARBALL" | awk '{print $1}')"
fi
[ "$EXPECTED" = "$ACTUAL" ] || { echo "SHA-256 mismatch for OpenSSL tarball" >&2; exit 1; }

for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    armeabi-v7a) TARGET=android-arm ;;
    arm64-v8a)   TARGET=android-arm64 ;;
    x86)         TARGET=android-x86 ;;
    x86_64)      TARGET=android-x86_64 ;;
  esac
  if [ -f "$OUT/$ABI/lib/libcrypto.a" ]; then
    echo "== $ABI already built"; continue
  fi
  echo "== Building OpenSSL $OPENSSL_VERSION for $ABI"
  SRC="$WORK/src-$ABI"
  rm -rf "$SRC"; mkdir -p "$SRC"
  tar xzf "$TARBALL" -C "$SRC" --strip-components=1
  (
    cd "$SRC"
    ./Configure "$TARGET" -D__ANDROID_API__=$MIN_API \
      no-shared no-tests no-apps no-docs no-engine no-dso no-ui-console \
      no-legacy no-ssl3 no-comp no-module -fPIC \
      --prefix="$OUT/$ABI" --openssldir=/nonexistent
    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" build_libs
    make install_dev
  )
  rm -rf "$OUT/$ABI/lib/libssl.a" "$OUT/$ABI/lib/pkgconfig" "$OUT/$ABI/lib/cmake" "$SRC"
done
cp "$WORK/../openssl-work/src-LICENSE" "$OUT/LICENSE.txt" 2>/dev/null || \
  tar xzf "$TARBALL" -O "openssl-$OPENSSL_VERSION/LICENSE.txt" > "$OUT/LICENSE.txt"
echo "OpenSSL $OPENSSL_VERSION" > "$OUT/VERSION"
echo "Done: $OUT"
