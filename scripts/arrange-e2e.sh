#!/usr/bin/env bash
# Two emulators share a lagoon (through the relay on this computer) and are
# laid side by side the way a person would: a finger swiped off the right edge
# of the guest's screen and onto the left edge of the host's. Then checks,
# from every device's log, that the host's screen now lies right of the
# guest's, frames touching (none on an emulator), the finger's path level;
# that both draw the same table; and that the guest's own fish came along and
# swim on its screen.
#
#   scripts/arrange-e2e.sh                     # host emulator-5700, guest emulator-5708
#   SERIALS="emulator-5700 emulator-5704" scripts/arrange-e2e.sh
set -euo pipefail
cd "$(dirname "$0")/.."

serials=(${SERIALS:-emulator-5700 emulator-5708})
port=${PORT:-7172}
session="arrange-$$"
pkg=com.jpcottin.tairoto
out=build/arrange-e2e
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
    adbs "$s" shell pm grant "$pkg" android.permission.ACCESS_LOCAL_NETWORK 2>/dev/null || true
    adbs "$s" shell am force-stop "$pkg"
    for _ in $(seq 20); do [ -z "$(adbs "$s" shell pidof "$pkg" 2>/dev/null)" ] && break; sleep 0.25; done
done

host=${serials[0]}
guest=${serials[1]}
echo "Hosting on $host, arranging from the start"
adbs "$host" shell am start -S -W -n "$pkg/.MainActivity" --ez tairoto.fresh true --ez tairoto.hints false ${QUALITY:+--es tairoto.quality $QUALITY} ${FPS:+--ei tairoto.fps $FPS} \
    --es tairoto.share host --es tairoto.relay "10.0.2.2:$port" --es tairoto.session "$session" --ez tairoto.arrange true > /dev/null
sleep 4
echo "Joining from $guest"
adbs "$guest" shell am start -S -W -n "$pkg/.MainActivity" --ez tairoto.fresh true --ez tairoto.hints false ${QUALITY:+--es tairoto.quality $QUALITY} ${FPS:+--ei tairoto.fps $FPS} \
    --es tairoto.share join --es tairoto.relay "10.0.2.2:$port" --es tairoto.session "$session" > /dev/null
sleep 8

size() { adbs "$1" shell wm size | awk -F'[ x]' '/Override|Physical/ {w=$(NF-1); h=$NF} END {print w, h}'; }
read -r gw gh <<< "$(size "$guest")"
read -r hw hh <<< "$(size "$host")"
gy=$(( gh * 45 / 100 ))
hy=$(( hh * 60 / 100 ))
adbs "$host" exec-out screencap -p > "$out/before-$host.png" || true
echo "Swiping off $guest's right edge (y=$gy) onto $host's left edge (y=$hy)"
echo "{\"ev\":\"swiped\",\"guest_y\":$gy,\"host_y\":$hy}" > "$out/swipe.json"
adbs "$guest" shell input swipe $(( gw / 2 )) "$gy" $(( gw - 1 )) "$gy" 250 &
swipe_pid=$!
sleep 0.45
adbs "$host" shell input swipe 0 "$hy" $(( hw / 3 )) "$hy" 250
wait "$swipe_pid"
sleep 4
for s in "${serials[@]}"; do
    adbs "$s" exec-out screencap -p > "$out/$s.png" || true
    adbs "$s" exec-out run-as "$pkg" cat files/net-log.jsonl > "$out/$s.log"
done
echo "Logs and screenshots in $out"
python3 scripts/arrange_e2e_check.py "$out" "$host" "$guest"
