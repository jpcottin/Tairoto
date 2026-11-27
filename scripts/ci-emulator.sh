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

if ! "$bin/avdmanager" list avd -c | grep -qx "$AVD"; then
    # Only what is missing: an SDK already set up keeps its versions.
    imgdir="$sdk/$(echo "$IMAGE" | tr ';' '/')"
    need=()
    [ -d "$imgdir" ] || need+=("$IMAGE")
    [ -x "$sdk/emulator/emulator" ] || need+=(emulator)
    [ -x "$sdk/platform-tools/adb" ] || need+=(platform-tools)
    if [ ${#need[@]} -gt 0 ]; then yes | "$bin/sdkmanager" --install "${need[@]}" > /dev/null; fi
    echo no | "$bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 > /dev/null
    # Enough memory and a fixed size, so screenshots and layouts are the same every run.
    cfg="$HOME/.android/avd/$AVD.avd/config.ini"
    printf 'hw.ramSize=4096\nhw.keyboard=yes\ndisk.dataPartition.size=6G\n' >> "$cfg"
fi

"$sdk/emulator/emulator" -avd "$AVD" -port "$PORT" -gpu lavapipe -no-window -no-snapshot \
    -no-audio -no-boot-anim -camera-back none -camera-front none > "${LOG:-/tmp/$AVD.log}" 2>&1 &
adb -s "$SERIAL" wait-for-device
until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
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
