#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// Marine snow: specks drifting in the water at every depth. Generated here
// from the vertex index alone, no buffers; the deep half is drawn before the
// creatures and the shallow half after them.

layout(push_constant) uniform Push {
    float layer;   // 0 deep, 1 shallow
} P;

layout(location = 0) out vec2 vCorner;
layout(location = 1) out float vBright;

void main() {
    int mote = gl_VertexIndex / 6;
    int corner = gl_VertexIndex % 6;
    vec2 cornerOffsets[6] = vec2[6](vec2(-1, -1), vec2(1, -1), vec2(1, 1), vec2(-1, -1), vec2(1, 1), vec2(-1, 1));
    vec2 k = vec2(float(mote) * 1.618 + P.layer * 101.0, float(mote) * 0.732 + 9.0);
    float h1 = hash12(k), h2 = hash12(k + 3.1), h3 = hash12(k + 7.7), h4 = hash12(k + 1.9), h5 = hash12(k + 5.5);

    float D = F.tank.z;
    float z = P.layer < 0.5 ? mix(D * 0.5, D * 0.96, h3) : mix(D * 0.03, D * 0.5, h3);
    float t = timeS();
    vec2 drift = vec2(0.45, 0.18) * (0.5 + h4) * t + vec2(sin(t * 0.3 + h5 * 6.0), cos(t * 0.23 + h4 * 6.0)) * 1.0;
    vec2 p = mod(vec2(h1 * F.tank.x, h2 * F.tank.y) + drift, F.tank.xy);

    float size = mix(0.09, 0.28, h5 * h5);
    float px = max(size * F.cam.z * depthScale(z), 1.3);
    vec2 s = worldToScreen(p, z) + cornerOffsets[corner] * px;
    gl_Position = vec4(s / F.outSize.xy * 2.0 - 1.0, 0.0, 1.0);
    vCorner = cornerOffsets[corner];
    float lit = 0.55 + 0.9 * causticsAt(p, t, 1);
    vBright = lit * mix(0.35, 0.9, h4) * mix(1.0, 0.45, z / D) * (size * F.cam.z * depthScale(z) / px);
}
