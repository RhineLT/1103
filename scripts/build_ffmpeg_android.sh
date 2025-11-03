#!/usr/bin/env bash
set -euo pipefail

# Build a static ffmpeg binary for Android (arm64-v8a) with libx265 and place it under app/src/main/assets/ffmpeg/arm64-v8a/ffmpeg
# Requirements:
# - ANDROID_NDK_HOME (or ANDROID_NDK) must point to extracted Android NDK root
# - git, curl, make, pkg-config available
# - Ubuntu packages: build-essential, yasm or nasm

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
ASSETS_DIR="$ROOT_DIR/app/src/main/assets/ffmpeg/arm64-v8a"
FFMPEG_VERSION=${FFMPEG_VERSION:-"n7.0"}
X265_VERSION=${X265_VERSION:-"3.5"}
NDK_ROOT=${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}

if [[ -z "${NDK_ROOT}" ]]; then
  echo "ERROR: ANDROID_NDK_HOME (or ANDROID_NDK) is not set." >&2
  exit 1
fi

LLVM_BIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
CC="$LLVM_BIN/aarch64-linux-android21-clang"
CXX="$LLVM_BIN/aarch64-linux-android21-clang++"
AR="$LLVM_BIN/llvm-ar"
AS="$LLVM_BIN/llvm-as"
LD="$LLVM_BIN/ld.lld"
RANLIB="$LLVM_BIN/llvm-ranlib"
STRIP="$LLVM_BIN/llvm-strip"

WORK_DIR="${ROOT_DIR}/.build/ffmpeg-android-arm64"
SRC_DIR="$WORK_DIR/src"
BUILD_DIR="$WORK_DIR/build"
INSTALL_DIR="$WORK_DIR/install"
X265_PREFIX="$INSTALL_DIR/x265"

mkdir -p "$WORK_DIR" "$SRC_DIR" "$BUILD_DIR" "$INSTALL_DIR" "$ASSETS_DIR"

if [[ ! -d "$SRC_DIR/ffmpeg" ]]; then
  echo "==> Downloading FFmpeg ${FFMPEG_VERSION}"
  cd "$SRC_DIR"
  curl -sSL -o ffmpeg.tar.gz "https://github.com/FFmpeg/FFmpeg/archive/refs/tags/${FFMPEG_VERSION}.tar.gz"
  tar -xzf ffmpeg.tar.gz
  mv FFmpeg-* ffmpeg
fi

# x265
if [[ ! -d "$SRC_DIR/x265" ]]; then
  echo "==> Downloading x265 ${X265_VERSION}"
  cd "$SRC_DIR"
  curl -sSL -o x265.tar.gz "https://bitbucket.org/multicoreware/x265_git/get/${X265_VERSION}.tar.gz"
  mkdir -p x265
  tar -xzf x265.tar.gz --strip-components=1 -C x265
fi

# Build x265 (8-bit only)
echo "==> Building x265 for arm64-v8a"
mkdir -p "$BUILD_DIR/x265"
cd "$BUILD_DIR/x265"
cmake "$SRC_DIR/x265/source" \
  -G "Ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=21 \
  -DENABLE_SHARED=OFF \
  -DENABLE_ASSEMBLY=OFF \
  -DENABLE_PIC=ON \
  -DHIGH_BIT_DEPTH=OFF \
  -DEXPORT_C_API=ON \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$X265_PREFIX"

cmake --build . -j"$(nproc)"
cmake --install .

cd "$SRC_DIR/ffmpeg"

# Clean previous
make distclean || true

PKG_CONFIG_PATH="${X265_PREFIX}/lib/pkgconfig:$PKG_CONFIG_PATH"

# Configure for Android arm64
./configure \
  --arch=aarch64 \
  --target-os=android \
  --enable-cross-compile \
  --cc="$CC" \
  --cxx="$CXX" \
  --ar="$AR" \
  --ranlib="$RANLIB" \
  --strip="$STRIP" \
  --nm="$LLVM_BIN/llvm-nm" \
  --objcc="$CC" \
  --enable-static \
  --disable-shared \
  --disable-debug \
  --disable-doc \
  --enable-programs \
  --enable-gpl \
  --enable-libx265 \
  --extra-cflags="-I${X265_PREFIX}/include" \
  --extra-ldflags="-L${X265_PREFIX}/lib"

make -j"$(nproc)"

# Copy binary
cp -f ffmpeg "$ASSETS_DIR/ffmpeg"
chmod +x "$ASSETS_DIR/ffmpeg"

echo "==> FFmpeg binary placed at $ASSETS_DIR/ffmpeg"
