cmake_minimum_required(VERSION 3.22.1)
project(drifttank LANGUAGES C CXX)

# --- GLSL -> SPIR-V as C arrays, with glslc from the NDK -----------------------
file(GLOB GLSLC_CANDIDATES "${ANDROID_NDK}/shader-tools/*/glslc" "${ANDROID_NDK}/shader-tools/*/glslc.exe")
if(NOT GLSLC_CANDIDATES)
    message(FATAL_ERROR "glslc not found under ${ANDROID_NDK}/shader-tools")
endif()
list(GET GLSLC_CANDIDATES 0 GLSLC)

set(SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
set(GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GEN_DIR}")

set(SHADERS fullscreen.vert floor.frag shadow.vert shadow.frag creature.vert creature.frag
            motes.vert motes.frag composite.frag)
set(SHADER_HEADERS "")
foreach(SHADER ${SHADERS})
    string(REPLACE "." "_" NAME "${SHADER}")
    set(OUT "${GEN_DIR}/${NAME}.spv.h")
    add_custom_command(
        OUTPUT "${OUT}"
        COMMAND "${GLSLC}" -mfmt=c -O -I "${SHADER_DIR}" "${SHADER_DIR}/${SHADER}" -o "${OUT}"
        DEPENDS "${SHADER_DIR}/${SHADER}" "${SHADER_DIR}/common.glsl"
        COMMENT "glslc ${SHADER}"
        VERBATIM)
    list(APPEND SHADER_HEADERS "${OUT}")
endforeach()
add_custom_target(shaders DEPENDS ${SHADER_HEADERS})

# --- The engine library ---------------------------------------------------------
add_library(drifttank SHARED
    sim/world.cpp
    render/mesh.cpp
    render/renderer.cpp
    engine/engine.cpp
    jni/jni_bridge.cpp)
add_dependencies(drifttank shaders)
target_include_directories(drifttank PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}" "${GEN_DIR}")
# The `VkFoo ci{VK_STRUCTURE_TYPE_...}` idiom leaves the rest of each struct zeroed on purpose.
target_compile_options(drifttank PRIVATE -Wall -Wextra -Werror -Wno-missing-field-initializers -O2)
target_link_libraries(drifttank vulkan android log)
# 16 KB pages (Android 15+ devices): align the segments.
target_link_options(drifttank PRIVATE "-Wl,-z,max-page-size=16384")
