#!/usr/bin/env bash
# Installs FreeMyTV.apk on a Fire TV and turns the Home button service back on. Fire OS refuses ADB from the TV
# itself, so the app can't do this on its own, and an update can turn the service off.
#
#   tools/firetv-install.sh 192.168.40.4     # after ./build.sh
set -euo pipefail
cd "$(dirname "$0")/.."
tv="${1:?usage: tools/firetv-install.sh <tv ip>}:5555"
service=io.freemytv/io.freemytv.RemoteKeyService

adb connect "$tv" >/dev/null
adb -s "$tv" install -r FreeMyTV.apk
# Ours plus whatever else is on (TalkBack and the like), once.
current=$(adb -s "$tv" shell settings get secure enabled_accessibility_services </dev/null | tr -d '\r')
case ":$current:" in
    *":$service:"*) list=$current ;;
    ":null:" | "::") list=$service ;;
    *) list="$current:$service" ;;
esac
adb -s "$tv" shell settings put secure enabled_accessibility_services "$list" </dev/null
adb -s "$tv" shell settings put secure accessibility_enabled 1 </dev/null
echo "Installed; Home button service on."
