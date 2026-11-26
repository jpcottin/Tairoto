#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// Paints every creature from its vertex coordinates alone: no textures.
//   Green chromis: iridescent apple-green to blue, darker along the back,
//   fine scales, gold-ringed eyes, forked translucent tail with fin rays.
//   Green sea turtle: domed carapace of scutes, each with its own sunburst of
//   amber streaks, dark grooves, a pale rim; skin of dark scales edged in
//   cream on the head and flippers.

layout(location = 0) in vec2 vUv;
layout(location = 1) flat in int vPart;
layout(location = 2) flat in int vVisual;
layout(location = 3) in float vRho;
layout(location = 4) in float vZ;
layout(location = 5) flat in float vSeed;
layout(location = 6) in vec2 vWorld;
layout(location = 7) in float vFrame;

layout(location = 0) out vec4 outColor;

struct Lit {
    vec3 sun;
    vec3 ambient;
    vec3 H;
};

Lit lighting(float caustic) {
    Lit l;
    vec3 L = normalize(F.sun.xyz);
    l.sun = srgb(vec3(1.0, 0.97, 0.9)) * F.sun.w * (0.55 + 1.1 * caustic);
    l.ambient = srgb(vec3(0.36, 0.56, 0.62)) * 0.42;
    l.H = normalize(L + vec3(0.0, 0.0, 1.0));
    return l;
}

/** A local normal (x along the part, y across, z up) turned into the world. */
vec3 toWorld(vec3 nl) {
    vec2 f = vec2(cos(vFrame), sin(vFrame));
    vec2 r = vec2(-f.y, f.x);
    return normalize(vec3(f * nl.x + r * nl.y, nl.z));
}

/** Cellular pattern: x = distance to the nearest cell centre, y = to its edge, z = cell id. */
vec3 cells(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 8.0, f2 = 8.0;
    vec2 id = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(x, y);
            vec2 o = 0.2 + 0.6 * hash22(i + g);
            vec2 r = g + o - f;
            float d = dot(r, r);
            if (d < f1) {
                f2 = f1;
                f1 = d;
                id = i + g;
            } else if (d < f2) {
                f2 = d;
            }
        }
    }
    return vec3(sqrt(f1), sqrt(f2) - sqrt(f1), hash12(id));
}

/** Soft edge: crisp near the surface, defocused in the deep. */
float edgeAlpha(float edge) {
    float depthBlur = 0.02 + 0.28 * smoothstep(0.25, 1.0, vZ / F.tank.z);
    float aa = max(fwidth(edge) * 1.4, depthBlur);
    return 1.0 - smoothstep(1.0 - aa, 1.0, edge);
}

vec4 finish(vec3 color, float alpha) {
    return vec4(throughWater(color, vZ), alpha);
}

// ------------------------------------------------------------------ chromis

vec4 chromisBody(Lit l, float caustic) {
    float u = vUv.x / 0.78;   // 0 snout .. 1 peduncle
    float v = vUv.y;
    float av = abs(v);
    vec3 n = toWorld(vec3(u < 0.08 ? 0.5 * (0.08 - u) / 0.08 : 0.0, v * 0.92, sqrt(max(1.0 - v * v * 0.85, 0.05))));

    vec3 dorsum = srgb(vec3(0.27, 0.55, 0.5));
    vec3 flank = srgb(vec3(0.52, 0.86, 0.78));
    vec3 base = mix(dorsum, flank, smoothstep(0.12, 0.85, av));
    base = mix(base, srgb(vec3(0.2, 0.42, 0.42)), smoothstep(0.08, 0.0, u) * 0.6);  // darker snout

    // Iridescence: thin-film colour that changes with the angle to the light.
    float ndh = max(dot(n, l.H), 0.0);
    float film = 0.5 + 0.5 * sin(ndh * 7.0 + u * 4.0 + vSeed * TAU + timeS() * 0.4 + F.misc.x * 3.0);
    vec3 iri = mix(srgb(vec3(0.3, 0.62, 0.98)), srgb(vec3(0.45, 0.98, 0.58)), film);
    base = mix(base, iri, 0.35 * smoothstep(0.25, 0.9, av) + 0.12);

    // Scales: overlapping rows that catch the light one by one.
    vec2 sc = vec2(u * 46.0, v * 4.6 + floor(u * 46.0) * 0.5);
    vec2 cell = fract(sc) - 0.5;
    float rim = smoothstep(0.32, 0.5, length(cell));
    base *= 1.0 - rim * 0.12;
    float glitter = step(0.86, hash12(floor(sc) + vSeed * 13.0)) * pow(ndh, 24.0) * 1.6;

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    vec3 color = base * (l.sun * diffuse + l.ambient);
    color += l.sun * (pow(ndh, 60.0) * 0.45 + glitter) * mix(vec3(1.0), iri, 0.5);
    // Light passing through the thin edge of the body.
    color += flank * l.sun * smoothstep(0.75, 1.0, av) * 0.18;

    // Eyes at the sides of the head: black pupil, gold ring, a highlight.
    vec2 e = vec2((u - 0.085) / 0.04, (av - 0.62) / 0.3);
    float eye = length(e);
    if (eye < 1.0) {
        vec3 iris = mix(srgb(vec3(0.02, 0.03, 0.03)), srgb(vec3(0.72, 0.6, 0.25)), smoothstep(0.5, 0.85, eye));
        float hl = smoothstep(0.35, 0.0, length(e - vec2(-0.3, -0.35 * sign(v))));
        color = mix(color, iris * (l.sun * 0.5 + l.ambient) + hl * 1.4, smoothstep(1.0, 0.85, eye));
    }
    return finish(color, edgeAlpha(av));
}

vec4 chromisFin(Lit l, float caustic) {
    float u = vUv.x;
    float v = vUv.y;
    float av = abs(v);
    vec3 tint = srgb(vec3(0.58, 0.88, 0.82));
    float alpha;
    float rays;
    if (vPart == 1) {
        // Forked tail: the middle is cut back, the lobes reach far.
        float limit = 0.42 + 0.58 * pow(av, 0.85);
        float cut = 1.0 - smoothstep(limit - 0.06, limit, u);
        rays = 0.5 + 0.5 * cos(v * 26.0);
        alpha = cut * mix(0.72, 0.38, u) * (0.75 + 0.25 * rays);
        tint *= 0.9 + 0.15 * rays;
    } else if (vPart == 2) {
        rays = 0.5 + 0.5 * cos(v * 14.0 + u * 2.0);
        alpha = mix(0.45, 0.2, u) * (0.8 + 0.2 * rays) * (1.0 - smoothstep(0.75, 1.0, u));
    } else {
        rays = 0.5 + 0.5 * cos(u * 60.0);
        tint = srgb(vec3(0.3, 0.55, 0.5));
        alpha = 0.75 * (0.65 + 0.35 * rays);
    }
    vec3 color = tint * (l.sun * 0.7 + l.ambient) * (0.85 + 0.6 * caustic);
    return finish(color, alpha * edgeAlpha(av));
}

// ------------------------------------------------------------------ turtle

// Scute centres in shell space (u forward, |v| across), mirrored left/right.
const vec2 SCUTES[9] = vec2[9](
    vec2(0.7, 0.0), vec2(0.36, 0.0), vec2(0.02, 0.0), vec2(-0.33, 0.0), vec2(-0.66, 0.0),
    vec2(0.5, 0.56), vec2(0.15, 0.62), vec2(-0.2, 0.6), vec2(-0.52, 0.5));

vec4 turtleShell(Lit l, float caustic) {
    float u = vUv.x;
    float v = vUv.y;
    vec2 q = vec2(u, abs(v));
    float rho = vRho;

    // Dome: highest a little behind the middle.
    vec2 dome = vec2(u + 0.08, v) * vec2(0.95, 1.15);
    float zz = sqrt(max(1.0 - dot(dome, dome) * 0.75, 0.04));
    vec3 n = toWorld(normalize(vec3(dome.x * 1.25, dome.y * 1.25, zz)));

    vec3 color;
    float groove;
    if (rho > 0.84) {
        // Marginal scutes: a ring of small plates around the rim.
        float ang = atan(v, u);
        float seg = ang / TAU * 26.0;
        float edgeSeg = abs(fract(seg) - 0.5);
        float ring = abs(rho - 0.84);
        groove = max(1.0 - smoothstep(0.0, 0.035, 0.5 - edgeSeg), 1.0 - smoothstep(0.0, 0.025, ring));
        float k = hash12(vec2(floor(seg), 3.0) + vSeed);
        color = mix(srgb(vec3(0.34, 0.26, 0.13)), srgb(vec3(0.55, 0.43, 0.22)), k * 0.6 + 0.2);
        color = mix(color, srgb(vec3(0.66, 0.6, 0.38)), smoothstep(0.95, 1.0, rho) * 0.8);
    } else {
        // Nearest and second-nearest scute centres: the gap between them is a groove.
        float d1 = 9.0, d2 = 9.0;
        int best = 0;
        for (int i = 0; i < 9; i++) {
            vec2 d = (q - SCUTES[i]) * vec2(1.0, 1.25);
            float dd = length(d);
            if (dd < d1) {
                d2 = d1;
                d1 = dd;
                best = i;
            } else if (dd < d2) {
                d2 = dd;
            }
        }
        groove = 1.0 - smoothstep(0.0, 0.045, d2 - d1);
        vec2 rel = (q - SCUTES[best]) * vec2(1.0, 1.25);
        // Growth rings and a sunburst of streaks radiating from the scute's centre.
        float ang = atan(rel.y, rel.x);
        float k = float(best) * 7.13 + vSeed * 31.0;
        float streak = smoothstep(0.35, 0.85, vnoise(vec2(ang * 3.2 + k, d1 * 1.5)));
        streak = max(streak, smoothstep(0.55, 0.95, vnoise(vec2(ang * 7.0 - k, 4.0))));
        float rings = 0.5 + 0.5 * sin(d1 * 70.0);
        vec3 dark = srgb(vec3(0.16, 0.11, 0.05));
        vec3 olive = srgb(vec3(0.36, 0.29, 0.13));
        vec3 amber = srgb(vec3(0.7, 0.5, 0.2));
        color = mix(olive, dark, smoothstep(0.1, 0.45, d1) * 0.55);
        color = mix(color, amber, streak * smoothstep(0.03, 0.3, d1) * 0.85);
        color *= 0.93 + 0.07 * rings;
    }
    vec3 grooveColor = srgb(vec3(0.07, 0.05, 0.03));
    color = mix(color, grooveColor, groove * 0.85);

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = color * (l.sun * diffuse + l.ambient);
    // Wet keratin: a sharp highlight and a broad sheen, dulled in the grooves.
    lit += l.sun * (pow(ndh, 260.0) * 0.5 + pow(ndh, 18.0) * 0.05) * (1.0 - groove * 0.8);
    float alpha = 1.0 - smoothstep(1.0 - max(fwidth(rho) * 1.5, 0.02 + 0.2 * vZ / F.tank.z), 1.0, rho);
    return finish(lit, alpha);
}

vec4 turtleSkin(Lit l, float caustic) {
    float u = vUv.x;
    float v = vUv.y;
    float av = abs(v);
    bool head = vPart == 11;
    vec3 n = toWorld(vec3(0.0, v * (head ? 0.85 : 0.55), sqrt(max(1.0 - v * v * (head ? 0.7 : 0.3), 0.1))));

    // Scales: big plates on the head and the flippers' leading edge, small behind.
    vec2 sp = head ? vec2(u * 7.0, v * 2.6) : vec2(u * 11.0, v * (2.2 + 2.0 * step(0.0, v)));
    vec3 c = cells(sp + vSeed * 17.0);
    float border = 1.0 - smoothstep(0.03, 0.12, c.y);
    vec3 scale = mix(srgb(vec3(0.24, 0.2, 0.12)), srgb(vec3(0.4, 0.33, 0.19)), c.z);
    vec3 edge = srgb(vec3(0.8, 0.74, 0.54));
    vec3 color = mix(scale, edge, border * 0.9);
    if (!head) color = mix(color, srgb(vec3(0.55, 0.5, 0.36)), smoothstep(0.7, 1.0, u) * 0.4);

    if (head) {
        // Eyes on the sides, a dark beak at the tip.
        vec2 e = vec2((u - 0.6) / 0.09, (av - 0.66) / 0.3);
        float eye = length(e);
        color = mix(color, srgb(vec3(0.1, 0.07, 0.04)), smoothstep(0.9, 0.98, u) * 0.6);
        if (eye < 1.0) {
            vec3 iris = mix(srgb(vec3(0.02, 0.02, 0.02)), srgb(vec3(0.35, 0.25, 0.1)), smoothstep(0.55, 0.9, eye));
            float hl = smoothstep(0.3, 0.0, length(e - vec2(-0.3, -0.3 * sign(v))));
            color = mix(color, iris + hl * 0.8, smoothstep(1.0, 0.8, eye));
        }
    }

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = color * (l.sun * diffuse + l.ambient) + l.sun * pow(ndh, 40.0) * 0.25 * (1.0 - border);
    // The tips of the flippers are thin: a little light comes through.
    if (!head) lit += scale * l.sun * smoothstep(0.6, 1.0, u) * 0.15;
    return finish(lit, edgeAlpha(av));
}

void main() {
    float depth = vZ / F.tank.z;
    float caustic = causticsAt(vWorld, timeS(), 1) * (1.0 - 0.55 * depth);
    Lit l = lighting(caustic);
    if (vVisual == 1) {
        outColor = vPart == 10 ? turtleShell(l, caustic) : turtleSkin(l, caustic);
    } else {
        outColor = vPart == 0 ? chromisBody(l, caustic) : chromisFin(l, caustic);
    }
}
