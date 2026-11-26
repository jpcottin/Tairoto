#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) in vec2 vCorner;
layout(location = 1) in float vBright;

layout(location = 0) out vec4 outColor;

void main() {
    float r2 = dot(vCorner, vCorner);
    float a = exp(-r2 * 3.2) * vBright * 0.55;
    outColor = vec4(srgb(vec3(0.82, 0.94, 0.96)) * F.sun.w, a);
}
