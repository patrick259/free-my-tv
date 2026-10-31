#!/usr/bin/env bash
# Installs FreeMyTV.apk on a Fire TV, turns the Home button service back on and grants READ_TV_LISTINGS and READ_LOGS. Fire OS
# refuses ADB from the TV itself, so the app can't do this on its own, and an update can turn the service off.
#
#   tools/firetv-install.sh 192.168.40.4     # after ./build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
tv="${1:?usage: tools/firetv-install.sh <tv ip>}:5555"
service=io.freemytv/io.freemytv.RemoteKeyService

adb connect "$tv" >/dev/null
adb -s "$tv" install -r FreeMyTV.apk
# The apps' "Continue watching" rows (WatchNext): a permission only adb can grant.
adb -s "$tv" shell pm grant io.freemytv android.permission.READ_TV_LISTINGS </dev/null
# Home presses where no window changes (Amazon's settings, which are a page of its home screen): from the system log.
adb -s "$tv" shell pm grant io.freemytv android.permission.READ_LOGS </dev/null
# What music is playing (Now Playing): a notification listener that reads no notifications.
adb -s "$tv" shell cmd notification allow_listener io.freemytv/io.freemytv.MusicListener </dev/null
# Ours plus whatever else is on (TalkBack and the like), once.
current=$(adb -s "$tv" shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
case ":$current:" in
    *":$service:"*) list=$current ;;
    ":null:" | "::") list=$service ;;
    *) list="$current:$service" ;;
esac
# Off, then on: after an update the service restarts but gets no events until it's switched off and on again, once
# its process is back up (a few seconds).
sleep 4
adb -s "$tv" shell settings put secure enabled_accessibility_services '""' </dev/null
sleep 2
adb -s "$tv" shell settings put secure enabled_accessibility_services "$list" </dev/null
adb -s "$tv" shell settings put secure accessibility_enabled 1 </dev/null
echo "Installed; Home button service on, Continue watching readable, Home presses seen."
