#!/usr/bin/env bash
# One lagoon across three emulators, through the relay on this computer, and
# a feeding frenzy on the middle screen: fish from the screens on either side
# smell the food, swim over and change hands at the edges. Every device logs
# what it does as JSON lines (tag TairotoNet); share_e2e_check.py then checks
# that no creature was ever simulated by two devices at once, that every one
# ends up on exactly one, and that no food was lost or counted twice.
#
#   scripts/share-e2e.sh                       # emulator-5700, -5704, -5708
#   SERIALS="emulator-5554 emulator-5556 emulator-5558" scripts/share-e2e.sh
#
# The emulators must be up; the first hosts, the others join in order (left to right).
set -euo pipefail
cd "$(dirname "$0")/.."

serials=(${SERIALS:-emulator-5700 emulator-5704 emulator-5708})
port=${PORT:-7171}
settle=${SETTLE:-20}
frenzy=${FRENZY:-45}
session="e2e-$$"
pkg=com.jpcottin.tairoto
out=build/share-e2e
rm -rf "$out"
mkdir -p "$out"

echo "Building the app and the relay"
./gradlew -q :relay:installDist :app:assembleDebug
apk=app/build/outputs/apk/debug/app-debug.apk

relay/build/install/relay/bin/relay --port "$port" > "$out/relay.log" 2>&1 &
relay_pid=$!
trap 'kill $relay_pid 2>/dev/null || true' EXIT
sleep 1

adbs() { adb -s "$1" "${@:2}"; }

for s in "${serials[@]}"; do
    echo "Installing on $s"
    adbs "$s" install -r -t "$apk" > /dev/null
    # Android 17: reaching 10.0.2.2 is local network access, a runtime permission.
    adbs "$s" shell pm grant "$pkg" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
    adbs "$s" shell am force-stop "$pkg"
    for _ in $(seq 20); do [ -z "$(adbs "$s" shell pidof "$pkg" 2>/dev/null)" ] && break; sleep 0.25; done
    adbs "$s" logcat -c
done

launch() {
    # -S stops the app first, in the same breath: an instance left running would take the intent without its extras.
    adbs "$1" shell am start -S -W -n "$pkg/.MainActivity" \
        --ez tairoto.fresh true --ez tairoto.hints false --ez tairoto.trace "${TRACE:-false}" \
        --es tairoto.share "$2" --es tairoto.relay "10.0.2.2:$port" --es tairoto.session "$session" > /dev/null
}

echo "Hosting on ${serials[0]}"
launch "${serials[0]}" host
sleep 4
for s in "${serials[@]:1}"; do
    echo "Joining from $s"
    launch "$s" join
    sleep 2
done
echo "Letting the lagoon settle (${settle}s)"
sleep "$settle"

# The frenzy: strokes of flakes and a few pellets across the middle screen.
mid=${serials[1]}
size=$(adbs "$mid" shell wm size | awk -F'[ x]' '/Override|Physical/ {w=$(NF-1); h=$NF} END {print w, h}')
read -r w h <<< "$size"
echo "Feeding on $mid (${w}x${h}) for ${frenzy}s"
echo "{\"ev\":\"frenzy\",\"at\":$(date +%s)}" > "$out/frenzy.json"
for i in $(seq 6); do
    y=$(( h * (2 + i) / 10 ))
    adbs "$mid" shell input swipe $(( w / 6 )) "$y" $(( w * 5 / 6 )) "$y" 900
    adbs "$mid" shell input swipe $(( w / 2 )) $(( h / 4 )) $(( w / 2 )) $(( h * 3 / 4 )) 800
    adbs "$mid" shell input swipe $(( w / 2 + 20 * i )) $(( h / 2 )) $(( w / 2 + 20 * i )) $(( h / 2 )) 700
    sleep 3
done
sleep "$frenzy"

for s in "${serials[@]}"; do
    adbs "$s" exec-out screencap -p > "$out/$s.png" || true
    # Logcat drops lines from a busy app: the app also writes every line to a file.
    adbs "$s" exec-out run-as "$pkg" cat files/net-log.jsonl > "$out/$s.log"
done
echo "Logs and screenshots in $out"
python3 scripts/share_e2e_check.py "$out" "${serials[@]}"
