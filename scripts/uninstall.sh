#!/usr/bin/env bash
# Undoes install.sh: gives Home back to the TV's own launcher, then removes Origin TV.
#
#   TV_HOST=192.168.1.50 scripts/uninstall.sh            Origin TV only (Live Indian TV stays, with its sign-in)
#   TV_HOST=192.168.1.50 LIVE=1 scripts/uninstall.sh    Live Indian TV too
#
# Origin TV is removed only once another launcher owns Home, so the TV is never left without one.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the IP address of the TV, e.g. TV_HOST=192.168.1.50 scripts/uninstall.sh}"
export TV_HOST
PY=tools/venv/bin/python
[ -x "$PY" ] || { echo "Run ./install.sh once first, or: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
ORIGIN=io.github.rjtheorigin.origintv
# The stock launchers install.sh may have switched off (the same list as home/build-and-install.sh).
KNOWN="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
HOME_QUERY='cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

DISABLED=$("$PY" -I tools/tv.py 'pm list packages -d' | tr -d '\r' | sed 's/^package://')
for pkg in $KNOWN; do
  if printf '%s\n' "$DISABLED" | grep -qx "$pkg"; then
    echo "Switching $pkg back on..."
    "$PY" -I tools/tv.py "pm enable --user 0 $pkg" >/dev/null
  fi
done
# The first stock launcher the TV has becomes Home again.
STOCK=""
for pkg in $KNOWN; do
  C=$("$PY" -I tools/tv.py "$HOME_QUERY $pkg" | tail -n 1 | tr -d '\r')
  case "$C" in "$pkg"/*) STOCK=$C; break ;; esac
done
if [ -n "$STOCK" ]; then
  "$PY" -I tools/tv.py "cmd package set-home-activity --user 0 $STOCK" >/dev/null
fi
# Off the accessibility list and back to the stock keyboard, before the app goes.
"$PY" -I tools/tv.py "s=\$(settings get secure enabled_accessibility_services); n=\$(echo \"\$s\" | tr ':' '\n' | grep -v '^$ORIGIN/' | paste -sd: -); settings put secure enabled_accessibility_services \"\$n\"; ime reset >/dev/null; echo accessibility-and-keyboard-restored"

if [ -z "$STOCK" ]; then
  echo "ERROR: no stock launcher found to take Home back, so Origin TV was left installed (the TV needs a Home app)." >&2
  echo "Pick another launcher as Home first, then run this again." >&2
  exit 1
fi
"$PY" -I tools/tv.py "pm uninstall $ORIGIN"
NOW=$("$PY" -I tools/tv.py "$HOME_QUERY" | tail -n 1 | tr -d '\r')
echo "Home now resolves to $NOW"
if [ "${LIVE:-0}" = 1 ]; then "$PY" -I tools/tv.py "pm uninstall io.github.rjtheorigin.liveindiantv"; fi
echo "Done."
