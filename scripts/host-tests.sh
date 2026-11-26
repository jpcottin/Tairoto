#!/usr/bin/env bash
# Builds and runs the native unit tests on the host: no device, no emulator.
# The simulation and the mesh builder are plain C++; Google Test comes from the
# NDK's bundled sources (or a system copy under /usr/src/googletest).
#
#   scripts/host-tests.sh
#   scripts/host-tests.sh --gtest_filter='Turtle.*'
set -euo pipefail
cd "$(dirname "$0")/.."
cpp=engine/src/main/cpp
out=build/host-tests
mkdir -p "$out"

gtest=""
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
for d in "${NDK:-/nonexistent}/sources/third_party/googletest" "$sdk"/ndk/*/sources/third_party/googletest; do
    [ -d "$d" ] && gtest="$d"
done
if [ -z "$gtest" ] && [ -d /usr/src/googletest/googletest ]; then
    gtest=/usr/src/googletest/googletest
fi
if [ -z "$gtest" ]; then
    echo "Google Test sources not found: install an NDK or libgtest-dev, or set NDK=<ndk root>" >&2
    exit 2
fi

cxx="${CXX:-c++}"
# Google Test is compiled once and kept; the project sources every time.
if [ ! -f "$out/gtest.o" ] || [ "$gtest/src/gtest-all.cc" -nt "$out/gtest.o" ]; then
    "$cxx" -std=c++17 -O2 -I"$gtest/include" -I"$gtest" -c "$gtest/src/gtest-all.cc" -o "$out/gtest.o"
    "$cxx" -std=c++17 -O2 -I"$gtest/include" -I"$gtest" -c "$gtest/src/gtest_main.cc" -o "$out/gtest_main.o"
fi
"$cxx" -std=c++17 -O2 -g -Wall -Wextra -Werror \
    -I"$cpp" -I"$gtest/include" \
    "$cpp/test/sim_test.cpp" "$cpp/test/mesh_test.cpp" \
    "$cpp/sim/world.cpp" "$cpp/render/mesh.cpp" \
    "$out/gtest.o" "$out/gtest_main.o" \
    -lpthread -o "$out/engine_tests"
exec "$out/engine_tests" "$@"
