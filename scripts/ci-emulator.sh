#!/usr/bin/env bash
# Starts a headless emulator for the UI tests, drawing with lavapipe (Mesa's
# software Vulkan, bundled with the emulator): no GPU needed, the same pixels
# on every machine. Used by CI; locally it makes its own AVD, so it never
# touches emulators already running.
#
#   scripts/ci-emulator.sh                  # AVD Tairoto_CI on port 5712
#   PORT=5714 AVD=Other scripts/ci-emulator.sh
#   ANDROID_SERIAL=emulator-5712 ./gradlew :app:connectedDebugAndroidTest
set -euo pipefail
AVD="${AVD:-Tairoto_CI}"
PORT="${PORT:-5712}"
IMAGE="${IMAGE:-system-images;android-37.0;default;x86_64}"
SERIAL="emulator-$PORT"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
bin="$sdk/cmdline-tools/latest/bin"

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
hw.lcd.width=1080
hw.lcd.height=2400
hw.lcd.density=420
hw.ramSize=4096
hw.keyboard=yes
hw.gpu.enabled=yes
disk.dataPartition.size=6G
CONFIG
fi

"$sdk/emulator/emulator" -avd "$AVD" -port "$PORT" -gpu lavapipe -no-window -no-snapshot \
    -no-audio -no-boot-anim -camera-back none -camera-front none > "${LOG:-/tmp/$AVD.log}" 2>&1 &
adb start-server > /dev/null 2>&1 || true
log="${LOG:-/tmp/$AVD.log}"
booted() { [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }
for _ in $(seq 300); do booted && break; sleep 2; done
if ! booted; then
    echo "$SERIAL did not boot in 10 minutes; the emulator said:" >&2
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
