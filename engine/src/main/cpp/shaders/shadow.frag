#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 1) flat in int vPart;
layout(location = 2) in float vRho;
layout(location = 3) in float vHeight;
layout(location = 4) flat in int vVisual;

layout(location = 0) out vec4 outColor;

void main() {
    float strength;
    if (vVisual == 2) strength = 0.3;                     // food
    else if (vPart == 0 || vPart == 10) strength = 0.62;       // body, shell
    else if (vPart == 11 || vPart == 12) strength = 0.55; // head, flippers
    else if (vPart == 1) strength = 0.18;                 // tail fin
    else strength = 0.0;                                  // pectorals and the dorsal ridge are too thin
    float edge = vVisual == 2 ? length(vUv) : (vPart == 10 ? vRho : abs(vUv.y));
    float soft = clamp(0.45 + vHeight * 0.6, 0.45, 0.98);
    float a = (1.0 - smoothstep(1.0 - soft, 1.0, edge)) * strength * mix(0.9, 0.35, vHeight);
    // Blending multiplies the sand by (1 - a).
    outColor = vec4(0.0, 0.0, 0.0, a);
}
