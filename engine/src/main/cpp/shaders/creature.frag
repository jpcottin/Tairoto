#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// Paints every creature from its vertex coordinates alone: no textures.
//   Green chromis: iridescent apple-green to blue, darker along the back,
//   fine scales, gold-ringed eyes, forked translucent tail with fin rays.
//   Green sea turtle: domed carapace of scutes, each with its own sunburst of
//   amber streaks, dark grooves, a pale rim; skin of dark scales edged in
//   cream on the head and flippers.
//   Fai manu, the ocellated eagle ray: a charcoal-blue disc covered in white
//   spots, some ringed, wings that catch the light as they beat; under the
//   sand only its eyes and the faint mound of its outline show.
//   Feʻe, the day octopus: mottled brown, pale spots, dark waves passing over
//   it; it takes on the sand's colours and grain, flashes dark red when
//   frightened, and shows the pale rims of its suckers.
//   Uʻa, the white-spotted hermit crab: red legs and claws with black-ringed
//   white spots and bristly edges, eyes on stalks, in a glossy turban shell.
//   Clouds: stirred-up sand, and octopus ink.

layout(location = 0) in vec2 vUv;
layout(location = 1) flat in int vPart;
layout(location = 2) flat in int vVisual;
layout(location = 3) in float vRho;
layout(location = 4) in float vZ;
layout(location = 5) flat in float vSeed;
layout(location = 6) in vec2 vWorld;
layout(location = 7) in float vFrame;
layout(location = 8) in vec4 vAux;

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
    float glitter = step(0.86, hash12(floor(sc) + vSeed * 13.0)) * pow(ndh, 24.0) * 1.6 * (1.0 - smoothstep(0.22, 0.42, length(cell)));

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

// ------------------------------------------------------------------ food
// Flakes in the colours of real fish food (orange, red, spirulina green,
// yellow), speckled and a little translucent; pellets round and brown with a
// sheen; crumbs like the flakes they came from. Food going off turns dull
// and grey-brown, with a pale fuzz on the sand.

vec4 food(Lit l, float caustic) {
    float freshness = vRho;  // fract(kind) * 2 carries freshness for food
    float r = length(vUv);
    vec3 color;
    float alpha = 1.0;
    if (vPart == 1) {
        vec3 n = normalize(vec3(vUv * 0.9, sqrt(max(1.0 - r * r, 0.05))));
        vec3 brown = mix(srgb(vec3(0.78, 0.52, 0.27)), srgb(vec3(0.66, 0.42, 0.2)), vSeed);
        float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
        color = brown * (l.sun * diffuse + l.ambient) + l.sun * pow(max(dot(n, l.H), 0.0), 40.0) * 0.4;
        alpha = 1.0 - smoothstep(0.92, 1.0, r);
    } else {
        float pick = fract(vSeed * 7.31);
        vec3 base = pick < 0.35 ? srgb(vec3(0.96, 0.48, 0.14))
                  : pick < 0.6  ? srgb(vec3(0.85, 0.2, 0.13))
                  : pick < 0.82 ? srgb(vec3(0.4, 0.62, 0.27))
                  : srgb(vec3(0.95, 0.8, 0.32));
        float speck = vnoise(vUv * 6.0 + vSeed * 40.0);
        base *= 0.8 + 0.35 * speck;
        if (vPart == 2) base *= 0.85;
        color = base * (l.sun * 0.85 + l.ambient) * (0.85 + 0.5 * caustic);
        alpha = vPart == 0 ? 0.9 : 0.95;
    }
    // Going off: duller, browner, and a pale fuzz.
    vec3 rotten = srgb(vec3(0.33, 0.3, 0.22)) * (l.sun * 0.7 + l.ambient);
    color = mix(rotten, color, smoothstep(0.0, 0.7, freshness));
    float fuzz = (1.0 - freshness) * smoothstep(0.55, 0.9, vnoise(vUv * 9.0 + vSeed * 13.0));
    color = mix(color, srgb(vec3(0.85, 0.86, 0.8)) * (l.sun * 0.6 + l.ambient), fuzz * 0.5);
    return finish(color, alpha);
}

// ------------------------------------------------------------------ eagle ray

vec4 rayDisc(Lit l, float caustic) {
    float u = vUv.x, v = vUv.y;  // body lengths: u forward from the centre, v across
    float av = abs(v);
    float cover = vAux.x;
    // The wing's slope from the beat, and a low ridge along the body.
    vec3 nl = normalize(vec3(-vAux.y, -vAux.z, 1.0));
    float ridge = exp(-v * v * 45.0);
    nl = normalize(nl + vec3(-u * 0.35 * ridge, -v * 3.0 * ridge, 0.0));
    vec3 n = toWorld(nl);

    vec3 back = srgb(vec3(0.14, 0.16, 0.21));
    back = mix(back, srgb(vec3(0.09, 0.1, 0.12)), smoothstep(0.24, 0.42, u));  // the head is darker
    back *= 0.9 + 0.15 * vnoise(vec2(u, v) * 9.0 + vSeed * 7.0);
    // White spots everywhere on the back, smaller toward the wing tips, some ringed.
    vec3 cl = cells(vec2(u, v) * 17.0 + vSeed * 31.0);
    float size = mix(0.3, 0.17, smoothstep(0.0, 0.6, av)) * mix(0.7, 1.15, cl.z);
    float spot = 1.0 - smoothstep(size * 0.72, size, cl.x);
    if (cl.z > 0.72) spot *= smoothstep(size * 0.32, size * 0.46, cl.x);
    spot *= 1.0 - 0.85 * smoothstep(0.3, 0.42, u);
    vec3 color = mix(back, srgb(vec3(0.9, 0.93, 0.95)), spot * 0.92);

    // Eyes bulging on the sides of the head, spiracles just behind.
    float eye = length((vec2(u, av) - vec2(0.34, 0.1)) / vec2(0.03, 0.02));
    float spiracle = length((vec2(u, av) - vec2(0.27, 0.075)) / vec2(0.022, 0.012));
    color = mix(color, srgb(vec3(0.04, 0.04, 0.05)), (1.0 - smoothstep(0.7, 1.0, spiracle)) * 0.8);

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = color * (l.sun * diffuse + l.ambient) + l.sun * (pow(ndh, 70.0) * 0.35 + pow(ndh, 12.0) * 0.04);
    if (eye < 1.0) {
        vec3 iris = mix(srgb(vec3(0.02, 0.02, 0.03)), srgb(vec3(0.5, 0.45, 0.3)), smoothstep(0.55, 0.9, eye));
        float hl = smoothstep(0.35, 0.0, length(vec2(u - 0.335, av - 0.104) / vec2(0.012, 0.008)));
        lit = mix(lit, iris * (l.sun * 0.6 + l.ambient) + hl * 1.2, 1.0 - smoothstep(0.85, 1.0, eye));
    }

    float aa = 0.02 + 0.28 * smoothstep(0.25, 1.0, vZ / F.tank.z);
    if (cover > 0.001) {
        // Sand over the disc, settling grain by grain; the eyes stay out.
        vec3 sand = sandLitAt(vWorld, timeS());
        sand *= 0.9 + 0.14 * max(dot(n, normalize(F.sun.xyz)), 0.0);   // the mound of the body under it
        sand *= 1.0 - 0.18 * smoothstep(0.7, 1.0, vAux.w) * cover;      // a soft trough at the outline
        float grains = fbm(vWorld * 0.9 + vSeed * 11.0);
        float covered = smoothstep(grains - 0.12, grains + 0.12, cover * 1.15);
        covered *= 1.0 - 0.8 * (1.0 - smoothstep(0.9, 1.8, eye));
        lit = mix(lit, sand, covered);
        aa = mix(aa, 0.45, cover);
    }
    float alpha = 1.0 - smoothstep(1.0 - max(fwidth(vAux.w) * 1.4, aa), 1.0, vAux.w);
    return finish(lit, alpha);
}

vec4 rayTail(Lit l, float caustic) {
    float u = vUv.x, v = vUv.y;
    vec3 n = toWorld(vec3(0.0, v * 0.8, sqrt(max(1.0 - v * v * 0.6, 0.1))));
    vec3 color = srgb(vec3(0.11, 0.12, 0.15));
    // The pale venomous spine on top, just behind the root.
    float spine = (1.0 - smoothstep(0.15, 0.35, abs(v))) * smoothstep(0.015, 0.03, u) * (1.0 - smoothstep(0.07, 0.1, u));
    color = mix(color, srgb(vec3(0.62, 0.58, 0.52)), spine * 0.8);
    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    vec3 lit = color * (l.sun * diffuse + l.ambient) + l.sun * pow(max(dot(n, l.H), 0.0), 50.0) * 0.25;
    lit = mix(lit, sandLitAt(vWorld, timeS()), vAux.x * smoothstep(0.2, 0.8, fbm(vWorld * 0.9)));
    return finish(lit, edgeAlpha(vAux.w));
}

// ------------------------------------------------------------------ octopus

vec4 octopus(Lit l, float caustic) {
    float camo = vAux.x, alarm = vAux.y;
    float u = vUv.x, v = vUv.y;
    vec2 p = vWorld;
    // Skin in world space, so the pattern runs on across arms, web and mantle.
    float blotch = fbm(p * 0.55 + vSeed * 13.0);
    vec3 fine = cells(p * 2.6 + vSeed * 5.0);
    vec3 color = mix(srgb(vec3(0.78, 0.42, 0.22)), srgb(vec3(0.44, 0.16, 0.08)), smoothstep(0.42, 0.62, blotch) * 0.8);
    color = mix(color, srgb(vec3(0.92, 0.8, 0.62)), (1.0 - smoothstep(0.1, 0.18, fine.x)) * 0.45 * smoothstep(0.3, 0.7, fine.z));
    // Dark waves passing over it, the way a hunting octopus flickers.
    float wave = 0.5 + 0.5 * sin(dot(p, vec2(0.45, 0.3)) - timeS() * 2.6 + vSeed * 6.0);
    color *= 1.0 - 0.22 * pow(wave, 4.0) * (1.0 - camo);

    // Papillae: small raised bumps that come up with the camouflage.
    float bump = (1.0 - smoothstep(0.0, 0.35, fine.x)) * camo;
    vec3 nl;
    if (vPart == 0) nl = vec3(0.0, v * 0.8, sqrt(max(1.0 - v * v * 0.6, 0.1)));
    else if (vPart == 2) nl = vec3(-(u - 0.6) * 0.9, v * 0.9, sqrt(max(1.0 - v * v * 0.8, 0.1)));
    else nl = vec3(0.0, 0.0, 1.0);
    nl.xy += (fine.xy - 0.2) * bump * 0.6;
    vec3 n = toWorld(normalize(nl));

    // The pale rims of the suckers along the edges of the arms.
    float sucker = 0.0;
    if (vPart == 0) {
        float rows = fract(u * 34.0) - 0.5;
        float ring = length(vec2(rows, (abs(v) - 0.72) * 3.0));
        sucker = (1.0 - smoothstep(0.28, 0.38, ring)) * smoothstep(0.15, 0.3, ring) * smoothstep(0.98, 0.6, u);
    }

    // Camouflage: the sand's colours, a little darker in the hollows of the skin.
    vec3 sand = sandAlbedo(p) * (0.85 + 0.3 * vnoise(p * 1.7 + 3.0));
    color = mix(color, sand * (0.88 + 0.2 * bump), camo * 0.92);
    // Fright: dark red-brown, the suckers standing out pale.
    color = mix(color, srgb(vec3(0.3, 0.07, 0.05)), alarm * 0.85);
    color = mix(color, srgb(vec3(0.9, 0.82, 0.74)), sucker * (0.35 + 0.5 * alarm) * (1.0 - camo));

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = color * (l.sun * diffuse * (0.85 + 0.35 * caustic) + l.ambient) + l.sun * pow(ndh, 40.0) * 0.18;

    if (vPart == 3) {
        // The eye: a golden ring, a dark bar of a pupil; the lid takes the skin's colour around it.
        float r = length(vUv);
        float pupil = 1.0 - smoothstep(0.14, 0.22, abs(vUv.x)) * 1.0;
        pupil *= 1.0 - smoothstep(0.55, 0.65, abs(vUv.y));
        vec3 iris = mix(srgb(vec3(0.8, 0.66, 0.3)), srgb(vec3(0.03, 0.03, 0.03)), pupil);
        iris = mix(iris, color, smoothstep(0.62, 0.95, r) * 0.9);
        lit = iris * (l.sun * 0.8 + l.ambient) + smoothstep(0.25, 0.0, length(vUv - vec2(-0.3, -0.3))) * 0.9;
        return finish(lit, 1.0 - smoothstep(0.85, 1.0, r));
    }
    float alpha = vPart == 1 ? (1.0 - smoothstep(0.8, 1.0, vAux.w)) * 0.96 : edgeAlpha(vAux.w);
    // Flat on the sand and camouflaged, its outline melts away.
    alpha *= 1.0 - 0.25 * camo * smoothstep(0.5, 1.0, vAux.w);
    return finish(lit, alpha);
}

// ------------------------------------------------------------------ hermit crab

vec4 crabShell(Lit l, float caustic) {
    vec2 q = vUv;
    float rho = vAux.w;
    vec3 n = toWorld(normalize(vec3(q.x * 0.9, q.y * 1.2, sqrt(max(1.0 - rho * rho * 0.85, 0.05)))));
    // Whorls winding out from the spire at the back: a logarithmic spiral.
    vec2 a = (q - vec2(-0.42, 0.06)) * vec2(1.0, 1.15);
    float r = max(length(a), 1e-3);
    float phi = atan(a.y, a.x);
    float w = log(r) * 1.35 - phi / TAU;
    float whorl = fract(w);
    float suture = 1.0 - smoothstep(0.0, 0.06, min(whorl, 1.0 - whorl));
    // A tapestry turban: chestnut with cream and dark spiral bands, and tent-shaped flames.
    vec3 chestnut = srgb(vec3(0.66, 0.4, 0.2));
    vec3 cream = srgb(vec3(0.9, 0.83, 0.68));
    vec3 dark = srgb(vec3(0.32, 0.2, 0.1));
    vec3 color = chestnut;
    color = mix(color, cream, smoothstep(0.14, 0.17, whorl) * (1.0 - smoothstep(0.23, 0.26, whorl)) * 0.85);
    color = mix(color, dark, smoothstep(0.58, 0.62, whorl) * (1.0 - smoothstep(0.7, 0.74, whorl)) * 0.8);
    float tent = abs(fract(phi * 2.2 + w * 1.5 + vSeed * 3.0) - 0.5) * 2.0;
    color = mix(color, cream, smoothstep(0.75, 0.9, tent) * 0.6 * (1.0 - smoothstep(0.1, 0.2, abs(whorl - 0.45))));
    color = mix(color, dark, suture * 0.75);
    color *= 0.9 + 0.2 * vnoise(q * 14.0 + vSeed * 9.0);
    // A few crusts of pink coralline algae; the lip of the opening pale.
    color = mix(color, srgb(vec3(0.86, 0.56, 0.48)), smoothstep(0.86, 0.92, vnoise(q * 9.0 + vSeed * 21.0)) * 0.35);
    color = mix(color, srgb(vec3(0.95, 0.88, 0.8)), smoothstep(0.78, 0.92, q.x) * (1.0 - smoothstep(0.35, 0.6, abs(q.y))) * 0.7);
    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    // Glossy: a sharp highlight and a broad sheen.
    vec3 lit = color * (l.sun * diffuse + l.ambient) + l.sun * (pow(ndh, 160.0) * 0.9 + pow(ndh, 14.0) * 0.06) * (1.0 - suture * 0.6);
    float alpha = 1.0 - smoothstep(1.0 - max(fwidth(rho) * 1.5, 0.02 + 0.2 * vZ / F.tank.z), 1.0, rho);
    return finish(lit, alpha);
}

vec4 crabLimb(Lit l, float caustic) {
    float u = vUv.x, v = vUv.y, av = abs(v);
    vec3 n = toWorld(vec3(0.0, v * 0.85, sqrt(max(1.0 - v * v * 0.7, 0.1))));
    vec3 red = srgb(vec3(0.78, 0.2, 0.1));
    vec3 color = red * (0.9 + 0.2 * vnoise(vec2(u * 20.0, v * 3.0) + vSeed * 5.0));
    float alpha = edgeAlpha(av);
    if (vPart != 5) {
        // White spots ringed in black.
        // Roughly as many cells along as the part is long for its width, so the spots come out round.
        vec2 sp = vPart == 2 ? vec2(u * 9.0, v * 2.1) : vec2(u * 11.0, v * 0.95);
        vec3 c = cells(sp + vSeed * 19.0 + float(vPart) * 3.0);
        float spot = 1.0 - smoothstep(0.13, 0.18, c.x);
        float ringd = (1.0 - smoothstep(0.18, 0.24, c.x)) - spot;
        color = mix(color, srgb(vec3(0.05, 0.03, 0.03)), ringd * 0.85);
        color = mix(color, srgb(vec3(0.95, 0.93, 0.88)), spot * 0.95);
    }
    if (vPart == 1) {
        // Bristly legs with pale joints and dark tips.
        color = mix(color, srgb(vec3(0.95, 0.85, 0.7)), (1.0 - smoothstep(0.0, 0.035, abs(u - 0.5))) * 0.7);
        color = mix(color, srgb(vec3(0.08, 0.05, 0.04)), smoothstep(0.85, 0.95, u));
        float hair = step(0.45, hash12(vec2(floor(u * 70.0), sign(v)) + vSeed));
        alpha *= mix(1.0, hair, smoothstep(0.7, 0.95, av));
        color = mix(color, srgb(vec3(0.25, 0.12, 0.08)), smoothstep(0.6, 0.95, av) * 0.5);
    } else if (vPart == 2) {
        // The fingers: a gape down the middle, dark tips.
        color = mix(color, srgb(vec3(0.1, 0.06, 0.05)), smoothstep(0.82, 0.95, u) * 0.9);
        float gape = (1.0 - smoothstep(0.0, 0.08, av)) * smoothstep(0.7, 0.76, u);
        alpha *= 1.0 - gape;
    } else if (vPart == 4) {
        // Eyes on the tips of the stalks: dark and shining.
        float eye = smoothstep(0.6, 0.72, u);
        color = mix(color, srgb(vec3(0.85, 0.75, 0.35)), (1.0 - smoothstep(0.0, 0.05, abs(u - 0.62))) * 0.7);
        color = mix(color, srgb(vec3(0.02, 0.03, 0.05)), eye);
    } else if (vPart == 5) {
        color = mix(red, srgb(vec3(0.95, 0.8, 0.6)), step(0.5, fract(u * 18.0)) * 0.35);
        alpha = (1.0 - smoothstep(0.5, 1.0, av)) * mix(0.95, 0.5, u);
    }
    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = color * (l.sun * diffuse + l.ambient) + l.sun * pow(ndh, vPart == 4 ? 120.0 : 40.0) * (vPart == 4 ? 1.2 : 0.2);
    return finish(lit, alpha);
}

// ------------------------------------------------------------------ clouds

vec4 cloud(Lit l, float caustic) {
    float opacity = vAux.x, age = vAux.y;
    vec2 q = vUv * 2.2 + vSeed * 17.0;
    float n = fbm(q + vec2(age * 0.8, -age * 0.6));
    float d = length(vUv) * 1.3 + (n - 0.5) * 0.75;
    float body = 1.0 - smoothstep(0.3, 1.0, d);
    vec3 color;
    float alpha;
    if (vPart == 1) {
        // Ink: dark brown-black, in wisps that spread as it ages.
        float wisps = fbm(q * 2.1 - age * 1.5);
        alpha = opacity * body * mix(0.55, 1.0, wisps);
        color = srgb(vec3(0.06, 0.04, 0.035)) * (l.ambient + l.sun * 0.25);
    } else {
        alpha = opacity * body * 0.8;
        color = sandAlbedo(vWorld) * (l.sun * 0.8 * (0.85 + 0.4 * caustic) + l.ambient);
    }
    return finish(color, alpha);
}

void main() {
    float depth = vZ / F.tank.z;
    float caustic = causticsAt(vWorld, timeS(), 1) * (1.0 - 0.55 * depth);
    Lit l = lighting(caustic);
    switch (vVisual) {
        case 2: outColor = food(l, caustic); break;
        case 1: outColor = vPart == 10 ? turtleShell(l, caustic) : turtleSkin(l, caustic); break;
        case 3: outColor = vPart == 0 ? rayDisc(l, caustic) : rayTail(l, caustic); break;
        case 4: outColor = octopus(l, caustic); break;
        case 5: outColor = vPart == 0 ? crabShell(l, caustic) : crabLimb(l, caustic); break;
        case 6: outColor = cloud(l, caustic); break;
        default: outColor = vPart == 0 ? chromisBody(l, caustic) : chromisFin(l, caustic); break;
    }
}
