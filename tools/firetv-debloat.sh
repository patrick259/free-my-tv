#!/usr/bin/env bash
# Turns off (or back on) the Amazon packages in tools/firetv-debloat.txt on a Fire TV, over adb. Nothing is uninstalled:
# pm disable-user --user 0, undone with pm enable.
#
#   tools/firetv-debloat.sh 192.168.40.4 apply     # turn the list off (protected ones are reported and skipped)
#   tools/firetv-debloat.sh 192.168.40.4 check     # what's back on (a Fire OS update can do that); `apply` again fixes it
#   tools/firetv-debloat.sh 192.168.40.4 restore   # turn the whole list back on
set -euo pipefail
cd "$(dirname "$0")/.."
tv="${1:?usage: tools/firetv-debloat.sh <tv ip> apply|check|restore}:5555"
what="${2:-check}"
list=tools/firetv-debloat.txt
# </dev/null: adb shell would otherwise read the rest of the list from stdin.
sh() { adb -s "$tv" shell "$@" </dev/null; }

adb connect "$tv" >/dev/null
installed=$(sh pm list packages | sed 's/^package://' | tr -d '\r')
disabled=$(sh pm list packages -d | sed 's/^package://' | tr -d '\r')
off=0 on=0 protected=0 missing=0
for pkg in $(sed -e 's/#.*//' -e 's/[[:space:]]//g' "$list" | grep -v '^$'); do
    if ! grep -qx "$pkg" <<<"$installed"; then missing=$((missing + 1)); continue; fi
    is_off=false; grep -qx "$pkg" <<<"$disabled" && is_off=true
    case "$what" in
        apply)
            $is_off && { off=$((off + 1)); continue; }
            out=$(sh pm disable-user --user 0 "$pkg" 2>&1 || true)
            if [[ $out == *disabled-user* ]]; then echo "off        $pkg"; off=$((off + 1))
            elif [[ $out == *protected* ]]; then echo "protected  $pkg"; protected=$((protected + 1))
            else echo "error      $pkg: $(tail -1 <<<"$out")"; on=$((on + 1)); fi ;;
        check)
            if $is_off; then off=$((off + 1)); else echo "on         $pkg"; on=$((on + 1)); fi ;;
        restore)
            $is_off || { on=$((on + 1)); continue; }
            sh pm enable "$pkg" >/dev/null && { echo "back on    $pkg"; on=$((on + 1)); } ;;
        *) echo "unknown action: $what" >&2; exit 1 ;;
    esac
done
echo "$what: $off off, $on on, $protected protected, $missing not on this TV"
