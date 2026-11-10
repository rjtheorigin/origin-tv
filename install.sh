#!/usr/bin/env bash
# Origin TV installer: puts Live Indian TV (live channels) and the Origin TV home screen on an Android TV, over Wi-Fi,
# from a Mac or Linux computer on the same network.
#
#   TV_HOST=192.168.1.50 ./install.sh                 both apps; Origin TV becomes the Home screen
#   TV_HOST=192.168.1.50 ONLY=live ./install.sh    Live Indian TV only
#   TV_HOST=192.168.1.50 PHONE_REMOTE=1 ./install.sh  also switch on the phone remote (README, "Phone remote")
#
# First, on the TV: Settings > Device Preferences > About, press OK on "Build" seven times; then in Developer options
# turn on USB debugging (on some TVs also "Network debugging" or "ADB debugging"). When this script connects, the TV
# asks to allow this computer: tick "Always allow" and press OK.
#
# The APKs come from ./release (the installer zip ships them there), else from a build in this checkout
# (scripts/package-release.sh), else from the latest GitHub release. Undo everything with scripts/uninstall.sh.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the IP address of the TV (TV Settings > Network), e.g. TV_HOST=192.168.1.50 ./install.sh}"
export TV_HOST
REPO=rjtheorigin/origin-tv

# The ADB client is Python (adb-shell), so no Android tools are needed on this computer. Made once.
if [ ! -x tools/venv/bin/python ]; then
  command -v python3 >/dev/null || { echo "ERROR: python3 is needed (macOS: xcode-select --install; Linux: your package manager)." >&2; exit 1; }
  echo "Setting up the ADB client (once)..."
  python3 -m venv tools/venv
  tools/venv/bin/pip install -q -r tools/requirements.txt
fi
PY=tools/venv/bin/python

find_apk() {   # name, path of a local build
  for f in "release/$1.apk" "$2"; do [ -f "$f" ] && { echo "$f"; return 0; }; done
  mkdir -p release
  echo "Downloading $1.apk from the latest release..." >&2
  if curl -fsSL -o "release/$1.apk" "https://github.com/$REPO/releases/latest/download/$1.apk"; then echo "release/$1.apk"; return 0; fi
  rm -f "release/$1.apk"
  echo "ERROR: no $1.apk here and the download failed. Put it in ./release/ (from the GitHub release page)." >&2
  return 1
}
LIVE_APK=$(find_apk LiveIndianTV live/app/build/outputs/apk/release/app-release.apk)
if [ "${ONLY:-}" != live ]; then ORIGIN=$(find_apk OriginTV home/app/build/outputs/apk/release/app-release.apk); fi

echo "Connecting to $TV_HOST (accept the prompt on the TV if it appears)..."
"$PY" -I tools/tv.py --probe
SDK=$("$PY" -I tools/tv.py 'getprop ro.build.version.sdk' | tr -dc '0-9')
ABIS=$("$PY" -I tools/tv.py 'getprop ro.product.cpu.abilist' | tr -d '\r')
if [ -n "$SDK" ] && [ "$SDK" -lt 26 ]; then
  echo "ERROR: this TV runs Android SDK $SDK; Origin TV and Live Indian TV need Android 8.0 (SDK 26) or newer. Nothing was installed." >&2
  exit 1
fi
case "$ABIS" in
  *armeabi-v7a*) ;;
  *) echo "ERROR: this TV's processor ($ABIS) cannot run Live Indian TV's stream server, which is built for 32-bit ARM. Nothing was installed." >&2
     exit 1 ;;
esac

echo "Installing Live Indian TV..."
"$PY" -I tools/b64push.py "$LIVE_APK" /data/local/tmp/liveindiantv.apk
OUT=$("$PY" -I tools/tv.py 'pm install -r /data/local/tmp/liveindiantv.apk; rm -f /data/local/tmp/liveindiantv.apk')
echo "$OUT"
case "$OUT" in *Success*) ;; *) echo "ERROR: Live Indian TV did not install (see above). Nothing else was changed." >&2; exit 1 ;; esac

if [ "${ONLY:-}" = live ]; then
  echo
  echo "Done. Open Live Indian TV on the TV and sign in with your Jio number."
  exit 0
fi

echo "Installing Origin TV and making it the Home screen..."
APK="$PWD/$ORIGIN" home/build-and-install.sh

echo
echo "Done. Next, on the TV:"
echo "  1. Open Live Indian TV (first tile on Home) and sign in with your Jio number and the OTP Jio sends you."
echo "  2. Press Home. Live channels and films fill the Home screen within a few minutes of signing in."
echo "  3. To go back to the classic launcher at any time: the gear (top right) > Switch to classic Home."
echo "When you are done, you can switch USB debugging off again in Developer options."
