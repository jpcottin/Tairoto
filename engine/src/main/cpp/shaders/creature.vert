#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in float inZ;
layout(location = 3) in float inKind;
layout(location = 4) in float inSeed;
layout(location = 5) in float inFrame;
layout(location = 6) in vec4 inAux;

layout(location = 0) out vec2 vUv;
layout(location = 1) flat out int vPart;
layout(location = 2) flat out int vVisual;
layout(location = 3) out float vRho;
layout(location = 4) out float vZ;
layout(location = 5) flat out float vSeed;
layout(location = 6) out vec2 vWorld;
layout(location = 7) out float vFrame;
layout(location = 8) out vec4 vAux;

void main() {
    int kind = int(floor(inKind + 0.001));
    vPart = kind % 16;
    vVisual = kind / 16;
    vRho = fract(inKind) * 2.0;
    vUv = inUv;
    vZ = inZ;
    vSeed = inSeed;
    vWorld = inPos;
    vFrame = inFrame;
    vAux = inAux;
    gl_Position = projectNdc(inPos, inZ);
}
