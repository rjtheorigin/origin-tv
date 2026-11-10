#!/usr/bin/env bash
# Builds JioTV Go (https://github.com/JioTV-Go/jiotv_go, CC BY 4.0) from source for 32-bit ARM Android and puts it
# where Live Indian TV bundles it: live/app/src/main/jniLibs/armeabi-v7a/libjiotvgo.so (git-ignored).
#
#   server/build-jiotv-go.sh              the pinned version below
#   VERSION=v3.23.0 server/build-jiotv-go.sh
#
# Needs Docker. Runs as linux/amd64 (the Android NDK's Linux toolchain is x86-64), so on an Apple Silicon Mac turn on
# Rosetta in Docker Desktop. The shipped 1.0.0 binary was built from v3.22.0 with this recipe (NDK r26b, Go 1.26,
# armv7a-linux-androideabi28-clang); its SHA-256 starts 4ddc60fb.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${VERSION:-v3.22.0}"
OUT="$PWD/live/app/src/main/jniLibs/armeabi-v7a"
mkdir -p "$OUT"
docker run --rm --platform linux/amd64 -v "$OUT:/out" -e VERSION="$VERSION" golang:1.26-bookworm sh -euc '
  apt-get update -qq && apt-get install -y -qq unzip curl git >/dev/null
  curl -fsSL -o /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-r26b-linux.zip
  unzip -q /tmp/ndk.zip -d /opt
  git clone -q --depth 1 --branch "$VERSION" https://github.com/JioTV-Go/jiotv_go /src
  cd /src
  CC=/opt/android-ndk-r26b/toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi28-clang \
  CGO_ENABLED=1 GOOS=android GOARCH=arm GOARM=7 GOEXPERIMENT=jsonv2,greenteagc \
    go build -trimpath -o /out/libjiotvgo.so .
'
ls -l "$OUT/libjiotvgo.so"
shasum -a 256 "$OUT/libjiotvgo.so"
