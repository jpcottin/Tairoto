#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) in vec2 vUv;
layout(location = 1) flat in int vPart;
layout(location = 2) in float vRho;
layout(location = 3) in float vHeight;
layout(location = 4) flat in int vVisual;
layout(location = 5) in vec4 vAux;

layout(location = 0) out vec4 outColor;

/** How dark each part's shadow is: thin fins and antennae let most light by. */
float strengthOf(int visual, int part) {
    if (visual == 2) return 0.3;                                  // food
    if (visual == 3) return (part == 0 ? 0.6 : 0.35) * (1.0 - vAux.x);  // ray, none once under the sand
    if (visual == 4) return part == 3 ? 0.0 : (part == 0 ? 0.45 : 0.5); // octopus
    if (visual == 5) {                                            // hermit crab
        if (part == 0) return 0.6;
        if (part == 5) return 0.0;
        return part == 1 ? 0.4 : 0.5;
    }
    if (visual == 6) return (part == 1 ? 0.35 : 0.12) * vAux.x;   // ink, sand
    if (part == 0 || part == 10) return 0.62;                     // body, shell
    if (part == 11 || part == 12) return 0.55;                    // head, flippers
    if (part == 1) return 0.18;                                   // tail fin
    return 0.0;                                                   // pectorals and the dorsal ridge are too thin
}

void main() {
    float strength = strengthOf(vVisual, vPart);
    float edge;
    if (vVisual == 2) edge = length(vUv);
    else if (vVisual >= 3) edge = vVisual == 6 ? length(vUv) : vAux.w;
    else edge = vPart == 10 ? vRho : abs(vUv.y);
    float soft = clamp(0.45 + vHeight * 0.6, 0.45, 0.98);
    if (vVisual == 6) soft = 0.9;
    float a = (1.0 - smoothstep(1.0 - soft, 1.0, edge)) * strength * mix(0.9, 0.35, vHeight);
    // Blending multiplies the sand by (1 - a).
    outColor = vec4(0.0, 0.0, 0.0, a);
}
