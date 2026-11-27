#!/usr/bin/env bash
# Starts a headless emulator for the UI tests, drawing with lavapipe (Mesa's
# software Vulkan, bundled with the emulator): no GPU needed, the same pixels
# on every machine. Used by CI; locally it makes its own AVD, so it never
# touches emulators already running.
#
#   scripts/ci-emulator.sh                  # AVD Tairoto_CI on port 5712 (CI: PORT=5554, where adb looks)
#   PORT=5714 AVD=Other scripts/ci-emulator.sh
#   LCD_WIDTH=720 LCD_HEIGHT=1600 LCD_DENSITY=280 ...   # the same phone in mm, under half the pixels to draw
#   ANDROID_SERIAL=emulator-5712 ./gradlew :app:connectedDebugAndroidTest
set -euo pipefail
AVD="${AVD:-Tairoto_CI}"
PORT="${PORT:-5712}"
IMAGE="${IMAGE:-system-images;android-37.0;default;x86_64}"
SERIAL="emulator-$PORT"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
bin="$sdk/cmdline-tools/latest/bin"
# The SDK's own adb: a CI machine may have none on its PATH.
export PATH="$sdk/platform-tools:$PATH"

avd_dir="$HOME/.android/avd/$AVD.avd"
if [ ! -f "$avd_dir/config.ini" ]; then
    # Only what is missing: an SDK already set up keeps its versions.
    imgdir="$sdk/$(echo "$IMAGE" | tr ';' '/')"
    need=()
    [ -d "$imgdir" ] || need+=("$IMAGE")
    [ -x "$sdk/emulator/emulator" ] || need+=(emulator)
    [ -x "$sdk/platform-tools/adb" ] || need+=(platform-tools)
    # `yes` dies of a broken pipe once sdkmanager is done: not a failure.
    if [ ${#need[@]} -gt 0 ]; then { yes || true; } | "$bin/sdkmanager" --install "${need[@]}" > /dev/null; fi
    [ -d "$imgdir" ] || { echo "the system image $IMAGE did not install" >&2; exit 1; }
    # The AVD written out by hand: avdmanager's version must match the SDK's, a CI machine's often does not.
    mkdir -p "$avd_dir"
    printf 'avd.ini.encoding=UTF-8\npath=%s\npath.rel=avd/%s.avd\ntarget=android-37\n' "$avd_dir" "$AVD" > "$HOME/.android/avd/$AVD.ini"
    cat > "$avd_dir/config.ini" <<CONFIG
AvdId=$AVD
avd.ini.displayname=$AVD
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
image.sysdir.1=$(echo "$IMAGE" | tr ';' '/')/
tag.id=$(echo "$IMAGE" | cut -d';' -f3)
PlayStore.enabled=false
hw.lcd.width=${LCD_WIDTH:-1080}
hw.lcd.height=${LCD_HEIGHT:-2400}
hw.lcd.density=${LCD_DENSITY:-420}
hw.ramSize=4096
hw.keyboard=yes
hw.gpu.enabled=yes
disk.dataPartition.size=6G
CONFIG
fi

# adb first: a fresh machine makes its key when the server first starts, and the emulator
# trusts the key it finds when it boots; the other way round, adb is never let in.
adb start-server > /dev/null 2>&1 || true
"$sdk/emulator/emulator" -avd "$AVD" -port "$PORT" -gpu lavapipe -no-window -no-snapshot \
    -no-audio -no-boot-anim -camera-back none -camera-front none > "${LOG:-/tmp/$AVD.log}" 2>&1 &
log="${LOG:-/tmp/$AVD.log}"
booted() { [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
for _ in $(seq 300); do booted && break; sleep 2; done
if ! booted; then
    echo "$SERIAL did not boot in 10 minutes. adb sees:" >&2
    adb devices -l >&2
    echo "The emulator said:" >&2
    tail -40 "$log" >&2
    exit 1
fi
a() { adb -s "$SERIAL" "$@"; }
a shell settings put global window_animation_scale 0
a shell settings put global transition_animation_scale 0
a shell settings put global animator_duration_scale 0
a shell svc power stayon true
# The lagoon hides the system bars: the first time, Android explains it in a window that takes the focus.
a shell settings put secure immersive_mode_confirmations confirmed
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
echo "$SERIAL is up (lavapipe)"
