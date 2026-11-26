#!/usr/bin/env bash
# Runs the instrumented tests on a foldable emulator, including FoldTest, which
# folds and unfolds the device itself through the emulator's gRPC controller
# (espresso-device).
#
#   scripts/fold-tests.sh                 # uses the AVD Tairoto_Fold (Pixel 9 Pro Fold, API 37)
#   AVD=MyFold PORT=5704 GRPC=8556 scripts/fold-tests.sh -Pandroid.testInstrumentationRunnerArguments.class=com.jpcottin.tairoto.FoldTest
#
# What it takes, and why:
#  - the emulator started with -grpc <port>; the port is handed to the tests as
#    the instrumentation argument grpc.port (the Gradle plugin does not pass it);
#  - the guest's Wi-Fi off: app traffic on the emulated Wi-Fi does not reach the
#    host's 10.0.2.2, the one on eth0 does;
#  - ACCESS_LOCAL_NETWORK (Android 17) and INTERNET for the debug app, granted
#    by the test itself (src/debug/AndroidManifest.xml);
#  - the device kept awake: a fold may put it to sleep behind "swipe up to
#    continue"; the test wakes it as a user would.
set -euo pipefail
cd "$(dirname "$0")/.."
AVD="${AVD:-Tairoto_Fold}"
PORT="${PORT:-5704}"
GRPC="${GRPC:-8556}"
SERIAL="emulator-$PORT"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"

if ! adb -s "$SERIAL" get-state >/dev/null 2>&1; then
    "$sdk/emulator/emulator" -avd "$AVD" -port "$PORT" -grpc "$GRPC" -gpu "${GPU:-host}" \
        -no-snapshot -no-boot-anim -no-audio >/dev/null 2>&1 &
    adb -s "$SERIAL" wait-for-device
    until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done
fi
a() { adb -s "$SERIAL" "$@"; }
a shell svc wifi disable
a shell svc power stayon true
a shell locksettings set-disabled true >/dev/null
a shell settings put secure immersive_mode_confirmations confirmed
a emu unfold >/dev/null
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard

ANDROID_SERIAL="$SERIAL" ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.grpc.port="$GRPC" "$@"
