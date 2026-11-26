#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// A creature's shadow on the sand: the same triangles, moved along the sun's
// direction down to the sand, widened and faded the higher the creature swims.

layout(location = 0) in vec2 inPos;
layout(location = 1) in vec2 inUv;
layout(location = 2) in float inZ;
layout(location = 3) in float inKind;
layout(location = 4) in float inSeed;
layout(location = 5) in float inFrame;

layout(location = 0) out vec2 vUv;
layout(location = 1) flat out int vPart;
layout(location = 2) out float vRho;
layout(location = 3) out float vHeight;

void main() {
    float D = F.tank.z;
    float h = max(D - inZ, 0.0);
    vec3 L = normalize(F.sun.xyz);
    vec2 offset = -L.xy / L.z * h;
    int part = int(floor(inKind + 0.001)) % 16;
    float rho = fract(inKind) * 2.0;

    // Only the shell grows a penumbra in geometry; ribbons soften in the fragment
    // shader, or a thin fin would grow spikes.
    float blur = 0.2 + h * 0.035;
    vec2 f = vec2(cos(inFrame), sin(inFrame));
    vec2 r = vec2(-f.y, f.x);
    vec2 grow;
    if (part == 10) {
        vec2 radial = f * inUv.x + r * inUv.y;
        grow = length(radial) > 1e-4 ? normalize(radial) * blur * rho : vec2(0.0);
    } else {
        grow = vec2(0.0);
    }
    vUv = inUv;
    vPart = part;
    vRho = rho;
    vHeight = h / D;
    gl_Position = projectNdc(inPos + offset + grow, D);
}
