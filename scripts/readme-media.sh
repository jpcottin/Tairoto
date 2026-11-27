#!/usr/bin/env bash
# Records the pictures of the README from running emulators (drawn on the
# computer's GPU: -gpu host), into docs/media:
#   lagoon.jpg       the lagoon as it opens
#   feeding.gif      a finger sprinkling food, the fish coming for it
#   closeup.jpg      the octopus and a hermit crab, close
#   two-screens.gif  one lagoon on two phones side by side, fish crossing
#   arranging.jpg    both screens while they are arranged by hand
#   table.jpg        the share sheet's map of the table
#
#   scripts/readme-media.sh                       # emulator-5700 and emulator-5708
#   SERIALS="emulator-5554 emulator-5556" scripts/readme-media.sh
set -euo pipefail
cd "$(dirname "$0")/.."
serials=(${SERIALS:-emulator-5700 emulator-5708})
a=${serials[0]}
b=${serials[1]}
pkg=com.jpcottin.tairoto
out=docs/media
tmp=build/readme-media
port=${PORT:-7173}
mkdir -p "$out" "$tmp"
adbs() { adb -s "$1" "${@:2}"; }

./gradlew -q :app:assembleDebug :relay:installDist
for s in "$a" "$b"; do
    adbs "$s" install -r -t app/build/outputs/apk/debug/app-debug.apk > /dev/null
    adbs "$s" shell pm grant "$pkg" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
    adbs "$s" shell settings put secure immersive_mode_confirmations confirmed
done
# Names as people give their phones, not the emulator's model.
adbs "$a" shell settings put global device_name "\"Moana's phone\""
adbs "$b" shell settings put global device_name "\"Teiva's phone\""

start() { adbs "$1" shell am start -S -W -n "$pkg/.MainActivity" --ez tairoto.fresh true --ez tairoto.hints false --ez tairoto.stats false --es tairoto.quality high "${@:2}" > /dev/null; }
# The centre of the node whose content description starts with [2], from the accessibility tree.
centre() {
    adbs "$1" shell uiautomator dump /sdcard/ui.xml > /dev/null
    adbs "$1" exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
xml = sys.stdin.read()
for m in re.finditer(r"content-desc=\"([^\"]*)\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", xml):
    if m.group(1).startswith(sys.argv[1]):
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$2"
}
shot() { adbs "$1" exec-out screencap -p > "$tmp/$2.png"; ffmpeg -loglevel error -y -i "$tmp/$2.png" -vf scale=540:-2 -q:v 3 "$out/$2.jpg"; }
# A video into a GIF a README can carry: from [4] s for [5] s, 10 frames a second, [3] px wide, its own palette.
gif() { ffmpeg -loglevel error -y -ss "${4:-0}" -t "${5:-8}" -i "$1" -vf "fps=10,scale=$3:-2:flags=lanczos,split[x][y];[x]palettegen=max_colors=128:stats_mode=diff[p];[y][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" "$2"; }
record() { adbs "$1" shell screenrecord --time-limit "$3" --size 540x1212 --bit-rate 8000000 "/sdcard/$2.mp4"; adbs "$1" pull "/sdcard/$2.mp4" "$tmp/$2.mp4" > /dev/null; }
read -r w h <<< "$(adbs "$a" shell wm size | awk -F'[ x]' '/Override|Physical/ {w=$(NF-1); h=$NF} END {print w, h}')"

echo "The lagoon"
start "$a" --el tairoto.seed 11
sleep 6
shot "$a" lagoon

echo "Feeding"
record "$a" feeding 9 &
sleep 1
adbs "$a" shell input swipe $(( w / 5 )) $(( h / 2 )) $(( w * 4 / 5 )) $(( h / 2 + 150 )) 1200
sleep 1
adbs "$a" shell input swipe $(( w / 2 )) $(( h * 2 / 5 )) $(( w / 2 + 60 )) $(( h * 3 / 5 )) 900
wait
gif "$tmp/feeding.mp4" "$out/feeding.gif" 270 0.5 8

echo "Close up"
start "$a" --el tairoto.seed 5 --es tairoto.population day_octopus:1,hermit_crab:2,green_chromis:8 --es tairoto.decor coral_head:1,giant_clam:1 --ef tairoto.zoom 2.2
sleep 8
shot "$a" closeup

echo "Two screens"
relay/build/install/relay/bin/relay --port "$port" > "$tmp/relay.log" 2>&1 &
relay_pid=$!
trap 'kill $relay_pid 2>/dev/null || true' EXIT
sleep 1
start "$b" --el tairoto.seed 11 --es tairoto.share host --es tairoto.relay "10.0.2.2:$port" --es tairoto.session readme
sleep 4
start "$a" --el tairoto.seed 12 --es tairoto.share join --es tairoto.relay "10.0.2.2:$port" --es tairoto.session readme
sleep 10
# Food on the seam draws fish from both sides across it.
record "$b" left 12 &
rb=$!
record "$a" right 12 &
ra=$!
sleep 1.5
adbs "$a" shell input swipe 20 $(( h / 2 )) 260 $(( h / 2 + 200 )) 1200
adbs "$b" shell input swipe $(( w - 260 )) $(( h / 3 )) $(( w - 20 )) $(( h / 3 + 120 )) 1000
wait "$rb" "$ra"
# Each phone in a dark frame (emulators have none), so the picture shows two devices, edge to edge.
ffmpeg -loglevel error -y -i "$tmp/left.mp4" -i "$tmp/right.mp4" -filter_complex \
    "[0:v]pad=iw+28:ih+28:14:14:color=0x111418[l];[1:v]pad=iw+28:ih+28:14:14:color=0x111418[r];[l][r]hstack=inputs=2,pad=iw+16:ih+16:8:8:color=white" "$tmp/two.mp4"
gif "$tmp/two.mp4" "$out/two-screens.gif" 480 2 9

echo "The table"
# Left alone, a shared screen fades its buttons: a touch brings them back.
adbs "$b" shell input tap $(( w / 2 )) $(( h * 3 / 4 ))
sleep 1
adbs "$b" shell input tap $(centre "$b" "Shared on")
sleep 3
shot "$b" table
adbs "$b" shell input keyevent KEYCODE_BACK
sleep 1

echo "Arranging"
start "$b" --el tairoto.seed 11 --es tairoto.share host --es tairoto.relay "10.0.2.2:$port" --es tairoto.session readme2 --ez tairoto.arrange true
sleep 4
start "$a" --el tairoto.seed 12 --es tairoto.share join --es tairoto.relay "10.0.2.2:$port" --es tairoto.session readme2
sleep 8
adbs "$b" exec-out screencap -p > "$tmp/arr-left.png"
adbs "$a" exec-out screencap -p > "$tmp/arr-right.png"
ffmpeg -loglevel error -y -i "$tmp/arr-left.png" -i "$tmp/arr-right.png" -filter_complex "[0:v]scale=540:-2[l];[1:v]scale=540:-2[r];[l][r]hstack=inputs=2" -q:v 3 "$out/arranging.jpg"
ls -la "$out"
