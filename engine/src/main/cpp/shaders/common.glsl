// Shared by every Drift Tank shader: the frame block, the projection (the
// same formula as Camera.kt and render/camera.h), noise, and the caustics.

layout(std140, set = 0, binding = 0) uniform Frame {
    vec4 cam;       // centre.xy (mm), px per mm (output), camera height (mm)
    vec4 outSize;   // output w, h (px), scene target w, h (px)
    vec4 origin;    // parallax origin.xy (mm), time (s), quality (0 low, 1 high, 2 ultra, 3 ci)
    vec4 tank;      // width, height, depth (mm), motes per layer
    vec4 sun;       // direction toward the sun (xyz), intensity
    vec4 misc;      // tilt.xy, refraction strength (mm), exposure
    vec4 ripples[8];// centre.xy (mm), start time (s), amplitude (0 = unused)
} F;

const float PI = 3.14159265;
const float TAU = 6.28318531;

float timeS() { return F.origin.z; }
float quality() { return F.origin.w; }
bool ultra() { return quality() > 1.5 && quality() < 2.5; }

float depthScale(float z) { return F.cam.w / (F.cam.w + max(z, 0.0)); }

vec2 worldToScreen(vec2 p, float z) {
    vec2 o = F.origin.xy;
    return F.outSize.xy * 0.5 + ((p - o) * depthScale(z) + (o - F.cam.xy)) * F.cam.z;
}

vec2 screenToWorld(vec2 px, float z) {
    vec2 o = F.origin.xy;
    return o + ((px - F.outSize.xy * 0.5) / F.cam.z - (o - F.cam.xy)) / depthScale(z);
}

vec4 projectNdc(vec2 p, float z) {
    vec2 s = worldToScreen(p, z);
    return vec4(s / F.outSize.xy * 2.0 - 1.0, 0.0, 1.0);
}

/** Output pixel of a fragment in the (possibly smaller) scene target. */
vec2 scenePixelToOutput(vec2 fragCoord) { return fragCoord * (F.outSize.xy / F.outSize.zw); }

vec3 srgb(vec3 c) { return pow(c, vec3(2.2)); }

// ------------------------------------------------------------------ noise

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 hash22(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1, 0));
    float c = hash12(i + vec2(0, 1));
    float d = hash12(i + vec2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(vec2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) {
        s += a * vnoise(p);
        p = mat2(1.6, 1.2, -1.2, 1.6) * p + 7.1;
        a *= 0.5;
    }
    return s;
}

// ------------------------------------------------------------------ caustics
// Light focused by the moving surface draws a net of bright lines on the
// sand. Here: the edges of an animated cellular pattern, warped so the cells
// are never regular, in two layers that drift at different rates. It works in
// world millimetres, so the net continues from one screen to the next.

float causticEdge(vec2 p, float t) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 8.0, f2 = 8.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(x, y);
            vec2 h = hash22(i + g);
            vec2 o = 0.5 + 0.42 * sin(t * (0.55 + h) + TAU * h);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < f1) {
                f2 = f1;
                f1 = d;
            } else if (d < f2) {
                f2 = d;
            }
        }
    }
    return sqrt(f2) - sqrt(f1);
}

float causticsAt(vec2 pmm, float t, int layers) {
    vec2 p = pmm / 13.0;
    // Large-scale warp so the cells drift, small-scale warp so their edges curve.
    vec2 w = vec2(vnoise(p * 0.7 + t * 0.05), vnoise(p * 0.7 - t * 0.04 + 17.0));
    p += (w - 0.5) * 0.9;
    p += (vec2(vnoise(p * 2.3 + t * 0.12), vnoise(p * 2.3 - t * 0.1 + 5.0)) - 0.5) * 0.32;
    // Where the surface focuses light more, lines are brighter and sharper.
    float focus = smoothstep(0.2, 0.8, vnoise(pmm * 0.024 + vec2(t * 0.02, -t * 0.015)));
    float k = mix(16.0, 34.0, focus);
    float gain = mix(0.45, 1.25, focus);
    float e1 = causticEdge(p, t * 0.55);
    float a = exp(-e1 * k) * 0.8 + exp(-e1 * 6.0) * 0.18;
    if (layers < 2) return a * gain;
    float e2 = causticEdge(p * 1.37 + vec2(3.3, 1.7), t * 0.75);
    float b = exp(-e2 * (k + 4.0)) * 0.8 + exp(-e2 * 7.0) * 0.18;
    // Where two nets cross the light focuses most.
    return (a * 0.55 + b * 0.38 + a * b * 1.5) * gain;
}

/** Light absorbed by the water on its way down to depth z and back up (red goes first). */
vec3 waterTransmittance(float z) {
    return exp(-vec3(0.0068, 0.0022, 0.0012) * 2.0 * z);
}

vec3 waterColor() { return srgb(vec3(0.02, 0.3, 0.55)); }

/** Absorption plus in-scattered blue-green: what a colour looks like seen through z mm of water. */
vec3 throughWater(vec3 c, float z) {
    vec3 t = waterTransmittance(z);
    float scatter = 1.0 - exp(-0.007 * z);
    return c * t + waterColor() * scatter * 0.55 * F.sun.w;
}
