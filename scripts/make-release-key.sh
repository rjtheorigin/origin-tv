#!/usr/bin/env bash
# Makes the release signing key once, in signing/ (git-ignored), with the JDK in the Docker build image.
# Keep a copy of signing/ somewhere safe and private: an update installs over an earlier version only when it is
# signed with the same key, and both apps must share it (Live Indian TV lets only an app signed like it use its server).
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f signing/release.properties ] && { echo "signing/release.properties already exists; keeping it." >&2; exit 1; }
IMAGE="${IMAGE:-origintv-android-build}"
docker image inspect "$IMAGE" >/dev/null 2>&1 || docker build --platform linux/amd64 -t "$IMAGE" tools/docker
mkdir -p signing
PW=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)
docker run --rm --platform linux/amd64 --user "$(id -u):$(id -g)" -v "$PWD/signing:/s" "$IMAGE" \
  keytool -genkeypair -keystore /s/origintv-release.jks -alias origintv -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PW" -keypass "$PW" -dname "CN=Origin TV, O=Origin TV"
printf 'storeFile=origintv-release.jks\nstorePassword=%s\nkeyAlias=origintv\nkeyPassword=%s\n' "$PW" "$PW" > signing/release.properties
chmod 600 signing/release.properties signing/origintv-release.jks
echo "Made signing/origintv-release.jks and signing/release.properties. Back up the signing/ folder now."
