#!/usr/bin/env bash
# Builds both apps in Docker and puts a release together in dist/:
#   dist/OriginTV.apk, dist/JoshiTV.apk          the two apps
#   dist/OriginTV-installer.zip                  install.sh with both APKs and the ADB client: unzip, run one command
#   dist/SHA256SUMS.txt
# Needs: Docker; signing/release.properties for a release signed with the Origin TV key (scripts/make-release-key.sh);
# live/app/src/main/jniLibs/armeabi-v7a/libjiotvgo.so (server/build-jiotv-go.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER=live/app/src/main/jniLibs/armeabi-v7a/libjiotvgo.so
[ -f "$SERVER" ] || { echo "ERROR: $SERVER is missing: build it with server/build-jiotv-go.sh first." >&2; exit 1; }
[ -f signing/release.properties ] || echo "WARNING: no signing/release.properties: the APKs will be signed with this computer's debug key." >&2

tools/docker-build.sh home testReleaseUnitTest lintRelease assembleRelease
tools/docker-build.sh live lintRelease assembleRelease

rm -rf dist && mkdir -p dist/OriginTV-installer/{release,tools,home,scripts}
cp home/app/build/outputs/apk/release/app-release.apk dist/OriginTV.apk
cp live/app/build/outputs/apk/release/app-release.apk dist/JoshiTV.apk
I=dist/OriginTV-installer
cp dist/OriginTV.apk dist/JoshiTV.apk "$I/release/"
cp install.sh README.md LICENSE THIRD_PARTY_NOTICES.md "$I/"
cp home/build-and-install.sh "$I/home/"
cp scripts/uninstall.sh "$I/scripts/"
cp tools/tv.py tools/b64push.py tools/requirements.txt "$I/tools/"
(cd dist && zip -qr OriginTV-installer.zip OriginTV-installer && rm -rf OriginTV-installer)
(cd dist && shasum -a 256 OriginTV.apk JoshiTV.apk OriginTV-installer.zip > SHA256SUMS.txt)
ls -l dist
cat dist/SHA256SUMS.txt
