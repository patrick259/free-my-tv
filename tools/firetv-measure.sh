#!/usr/bin/env bash
# Fills the Comparison in Free My TV's settings on a Fire TV: the Home button's time, free memory and the home
# screen's memory, for Amazon's home screen (Original) and ours. Fire OS refuses ADB from the TV itself, so the app
# can't measure on its own; this measures from the computer and hands the numbers in (MetricsReceiver).
# Takes about two minutes; the screen goes to Amazon's home and Wi-Fi settings a few times, and ends on Free My TV.
#
#   tools/firetv-measure.sh 192.168.40.4
set -euo pipefail
tv="${1:?usage: tools/firetv-measure.sh <tv ip>}:5555"
service=io.freemytv/io.freemytv.RemoteKeyService
sh() { adb -s "$tv" shell "$@" </dev/null | tr -d '\r'; }
median() { sort -n | awk '{a[NR]=$1} END {if (NR) print a[int((NR+1)/2)]}'; }
avail() { sh cat /proc/meminfo | awk '/MemAvailable/ {print int($2 / 1024)}'; }
pss() { sh dumpsys meminfo "$1" | awk '/TOTAL:/ {print int($2 / 1024); exit}'; }
settle() { sleep 30; }

adb connect "$tv" >/dev/null
services=$(sh settings get secure enabled_accessibility_services)
case ":$services:" in *":$service:"*) ;; *) echo "The Home button service is off: run tools/firetv-install.sh first." >&2; exit 1 ;; esac

echo "Original: Amazon's home screen, Free My TV stopped"
sh settings put secure enabled_accessibility_services '""'
sh am force-stop io.freemytv
home=()
for i in 1 2 3; do
    sh am start -W -a android.settings.WIFI_SETTINGS >/dev/null
    sleep 2
    home+=("$(sh am start -W -a android.intent.action.MAIN -c android.intent.category.HOME | awk '/TotalTime/ {print $2}')")
    sleep 2
done
original_ms=$(printf '%s\n' "${home[@]}" | median)
settle
original_avail=$(avail)
original_pss=$(pss com.amazon.tv.launcher)

echo "Free My TV: the Home button service back on"
sh settings put secure enabled_accessibility_services "$services"
sh settings put secure accessibility_enabled 1
sleep 8
home=()
for i in 1 2 3; do
    sh am start -W -a android.settings.WIFI_SETTINGS >/dev/null
    sleep 2
    sh logcat -c
    sh input keyevent KEYCODE_HOME
    sleep 8     # Fire OS holds app starts for 5 s after Home (the to-do's "Home wait")
    # From the system's Home start to our screen taking the remote (HomeActivity logs it).
    home+=("$(sh logcat -d -v epoch -s ActivityManager:I FreeMyTV:I | awk '
        /START u0/ && /category.HOME/ && !t0 {t0 = $1}
        /home screen focused/ && t0 {print int(($1 - t0) * 1000); exit}')")
done
free_ms=$(printf '%s\n' "${home[@]}" | grep . | median)
settle
free_avail=$(avail)
free_pss=$(pss io.freemytv:home)

send() {
    sh am broadcast -a io.freemytv.METRICS -n io.freemytv/.MetricsReceiver --es mode "$1" \
        --el homeMs "${2:-0}" --el memAvailableMB "${3:-0}" --el homePssMB "${4:-0}" >/dev/null
}
send original "$original_ms" "$original_avail" "$original_pss"
send free "$free_ms" "$free_avail" "$free_pss"
printf '%-20s %10s %12s\n' "" Original "Free My TV"
printf '%-20s %8s ms %9s ms\n' "Home button" "$original_ms" "$free_ms"
printf '%-20s %8s MB %9s MB\n' "Free memory" "$original_avail" "$free_avail"
printf '%-20s %8s MB %9s MB\n' "Home screen memory" "$original_pss" "$free_pss"
