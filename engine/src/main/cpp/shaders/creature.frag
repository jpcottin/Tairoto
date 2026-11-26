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
//   Decorations: Toʻa the coral head, Toʻapū the branching coral, Pahua the
//   giant clam, ʻŌfaʻi the stone.

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

/**
 * A schooling fish's look from above, by species (vVisual):
 *   0  ʻAtoti, green chromis: apple green to blue, iridescent.
 *   7  Manini, convict surgeonfish: pale olive-white, six narrow black bars.
 *   8  Taape, bluestripe snapper: bright yellow, electric-blue lines along the sides.
 *   9  ʻOuma, yellowstripe goatfish: grey-olive back, white sides, a yellow stripe.
 *   10 Mamo, scissortail sergeant: silvery green-white, five broad black bars.
 */
struct FishLook {
    vec3 dorsum;    // along the back
    vec3 flank;     // the sides
    float iri;      // how iridescent
    float scales;   // how much the scale rows show
};

FishLook fishLook() {
    FishLook f;
    if (vVisual == 7) {
        f.dorsum = srgb(vec3(0.6, 0.66, 0.55)); f.flank = srgb(vec3(0.86, 0.9, 0.84)); f.iri = 0.08; f.scales = 0.5;
    } else if (vVisual == 8) {
        f.dorsum = srgb(vec3(0.86, 0.7, 0.16)); f.flank = srgb(vec3(0.98, 0.84, 0.26)); f.iri = 0.05; f.scales = 0.8;
    } else if (vVisual == 9) {
        f.dorsum = srgb(vec3(0.5, 0.52, 0.46)); f.flank = srgb(vec3(0.9, 0.9, 0.86)); f.iri = 0.1; f.scales = 1.0;
    } else if (vVisual == 10) {
        f.dorsum = srgb(vec3(0.62, 0.7, 0.55)); f.flank = srgb(vec3(0.88, 0.92, 0.86)); f.iri = 0.15; f.scales = 0.8;
    } else {
        f.dorsum = srgb(vec3(0.27, 0.55, 0.5)); f.flank = srgb(vec3(0.52, 0.86, 0.78)); f.iri = 1.0; f.scales = 1.0;
    }
    return f;
}

/** Dark bars across the back at these places along the body (0 snout .. 1 peduncle). */
float bars(float u, float v, int visual) {
    float d = 1.0;
    if (visual == 7) {
        // Manini: a bar through the eye, four across the body, one on the peduncle; narrow.
        const float P[6] = float[6](0.085, 0.25, 0.42, 0.58, 0.74, 0.9);
        for (int i = 0; i < 6; i++) d = min(d, abs(u - P[i] - 0.015 * v * v) / (i == 0 ? 0.02 : 0.028));
        return 1.0 - smoothstep(0.7, 1.0, d);
    }
    if (visual == 10) {
        // Mamo: five broad bars, the first just behind the head.
        const float P[5] = float[5](0.22, 0.38, 0.54, 0.7, 0.86);
        for (int i = 0; i < 5; i++) d = min(d, abs(u - P[i] + 0.02 * v * v) / 0.05);
        return 1.0 - smoothstep(0.75, 1.0, d);
    }
    return 0.0;
}

vec4 fishBody(Lit l, float caustic) {
    float u = vUv.x / 0.78;   // 0 snout .. 1 peduncle
    float v = vUv.y;
    float av = abs(v);
    vec3 n = toWorld(vec3(u < 0.08 ? 0.5 * (0.08 - u) / 0.08 : 0.0, v * 0.92, sqrt(max(1.0 - v * v * 0.85, 0.05))));
    FishLook f = fishLook();

    vec3 base = mix(f.dorsum, f.flank, smoothstep(0.12, 0.85, av));
    base = mix(base, f.dorsum * 0.75, smoothstep(0.08, 0.0, u) * 0.6);  // darker snout

    float ndh = max(dot(n, l.H), 0.0);
    // Iridescence: thin-film colour that changes with the angle to the light.
    float film = 0.5 + 0.5 * sin(ndh * 7.0 + u * 4.0 + vSeed * TAU + timeS() * 0.4 + F.misc.x * 3.0);
    vec3 iri = mix(srgb(vec3(0.3, 0.62, 0.98)), srgb(vec3(0.45, 0.98, 0.58)), film);
    base = mix(base, iri, (0.35 * smoothstep(0.25, 0.9, av) + 0.12) * f.iri);

    if (vVisual == 8) {
        // Taape: four electric-blue lines along each side, edged darker.
        float lines = 0.0;
        for (int i = 0; i < 4; i++) {
            float at = 0.42 + 0.15 * float(i) + 0.05 * u;
            float d = abs(av - at) / 0.035;
            lines = max(lines, 1.0 - smoothstep(0.6, 1.0, d));
        }
        lines *= smoothstep(0.04, 0.12, u) * (1.0 - smoothstep(0.9, 1.0, u));
        base = mix(base, srgb(vec3(0.15, 0.45, 0.95)), lines * 0.9);
    } else if (vVisual == 9) {
        // ʻOuma: a bright yellow stripe from the eye to the tail.
        float stripe = 1.0 - smoothstep(0.06, 0.1, abs(av - 0.66));
        base = mix(base, srgb(vec3(0.98, 0.82, 0.2)), stripe * smoothstep(0.05, 0.12, u) * 0.95);
    }
    base = mix(base, srgb(vec3(0.04, 0.05, 0.05)), bars(u, av, vVisual) * 0.92);

    // Scales: overlapping rows that catch the light one by one.
    vec2 sc = vec2(u * 46.0, v * 4.6 + floor(u * 46.0) * 0.5);
    vec2 cell = fract(sc) - 0.5;
    float rim = smoothstep(0.32, 0.5, length(cell));
    base *= 1.0 - rim * 0.12 * f.scales;
    float glitter = step(0.86, hash12(floor(sc) + vSeed * 13.0)) * pow(ndh, 24.0) * 1.6 * (1.0 - smoothstep(0.22, 0.42, length(cell)));

    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    vec3 color = base * (l.sun * diffuse + l.ambient);
    color += l.sun * (pow(ndh, 60.0) * 0.45 + glitter * (0.4 + 0.6 * f.iri)) * mix(vec3(1.0), iri, 0.5 * f.iri);
    // Light passing through the thin edge of the body.
    color += f.flank * l.sun * smoothstep(0.75, 1.0, av) * 0.18;

    // Eyes at the sides of the head: black pupil, a ring, a highlight.
    vec2 e = vec2((u - 0.085) / 0.04, (av - 0.62) / 0.3);
    float eye = length(e);
    if (eye < 1.0) {
        vec3 ring = vVisual == 8 ? srgb(vec3(0.9, 0.75, 0.2)) : (vVisual == 7 ? srgb(vec3(0.3, 0.3, 0.25)) : srgb(vec3(0.72, 0.6, 0.25)));
        vec3 irisC = mix(srgb(vec3(0.02, 0.03, 0.03)), ring, smoothstep(0.5, 0.85, eye));
        float hl = smoothstep(0.35, 0.0, length(e - vec2(-0.3, -0.35 * sign(v))));
        color = mix(color, irisC * (l.sun * 0.5 + l.ambient) + hl * 1.4, smoothstep(1.0, 0.85, eye));
    }
    return finish(color, edgeAlpha(av));
}

vec4 fishFin(Lit l, float caustic) {
    float u = vUv.x;
    float v = vUv.y;
    float av = abs(v);
    FishLook f = fishLook();
    vec3 tint = mix(f.flank, srgb(vec3(0.58, 0.88, 0.82)), vVisual == 0 ? 1.0 : 0.0);
    if (vVisual == 8) tint = srgb(vec3(0.98, 0.82, 0.25));          // yellow fins
    else if (vVisual == 9) tint = srgb(vec3(0.95, 0.88, 0.55));     // pale yellow tail
    else if (vVisual == 7) tint = srgb(vec3(0.85, 0.88, 0.75));
    float alpha;
    float rays;
    if (vPart == 4) {
        // Barbels: pale yellow feelers.
        vec3 c = srgb(vec3(0.95, 0.9, 0.6)) * (l.sun * 0.8 + l.ambient);
        return finish(c, edgeAlpha(av) * 0.95);
    }
    if (vPart == 1) {
        // The tail: deeply forked for the chromis, the sergeant and the goatfish, less so for the others.
        float fork = vVisual == 7 ? 0.82 : (vVisual == 8 ? 0.62 : 0.42);
        float limit = fork + (1.0 - fork) * pow(av, 0.85);
        float cut = 1.0 - smoothstep(limit - 0.06, limit, u);
        rays = 0.5 + 0.5 * cos(v * 26.0);
        alpha = cut * mix(0.72, 0.38, u) * (0.75 + 0.25 * rays);
        tint *= 0.9 + 0.15 * rays;
        // Mamo: a black stripe down each lobe, the "scissors".
        if (vVisual == 10) tint = mix(tint, srgb(vec3(0.05, 0.05, 0.05)), (1.0 - smoothstep(0.08, 0.16, abs(av - 0.62))) * smoothstep(0.15, 0.4, u));
        if (vVisual == 10 || vVisual == 8) alpha = max(alpha, cut * 0.55);
    } else if (vPart == 2) {
        rays = 0.5 + 0.5 * cos(v * 14.0 + u * 2.0);
        alpha = mix(0.45, 0.2, u) * (0.8 + 0.2 * rays) * (1.0 - smoothstep(0.75, 1.0, u));
    } else {
        rays = 0.5 + 0.5 * cos(u * 60.0);
        tint = f.dorsum * 0.85;
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


// ------------------------------------------------------------------ decorations
// In the decoration's own frame: vUv on its unit disc (x along its angle),
// vAux.xy the slope of its surface in world axes, vAux.w the distance to its
// outline. So the patterns move with it when it is dragged or turned.

/** A vector in the decoration's frame, turned into world axes. */
vec2 frameToWorld(vec2 v) {
    vec2 f = vec2(cos(vFrame), sin(vFrame));
    return f * v.x + vec2(-f.y, f.x) * v.y;
}

/** The surface normal from the dome's slope plus a bump's slope (in the decoration's frame). */
vec3 decorNormal(vec2 bumpSlope) {
    vec2 s = vAux.xy + frameToWorld(bumpSlope);
    return normalize(vec3(-s, 1.0));
}

/**
 * Rounded lumps on a cellular pattern: x = distance to the nearest centre,
 * y = distance to the cell's edge, zw = from here toward the centre; id the cell.
 */
vec4 lumps(vec2 p, out float id) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float f1 = 8.0, f2 = 8.0;
    vec2 to = vec2(0.0), cid = vec2(0.0);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(x, y);
            vec2 r = g + 0.15 + 0.7 * hash22(i + g) - f;
            float d = dot(r, r);
            if (d < f1) {
                f2 = f1;
                f1 = d;
                to = r;
                cid = i + g;
            } else if (d < f2) {
                f2 = d;
            }
        }
    }
    id = hash12(cid);
    return vec4(sqrt(f1), sqrt(f2) - sqrt(f1), to);
}

/** Where a decoration meets the sand: its colour blends into the sand's, its edge is soft. */
vec3 intoSand(vec3 albedo, float rho) {
    return mix(albedo, sandAlbedo(vWorld), smoothstep(0.86, 1.0, rho) * 0.85);
}

float decorAlpha(float rho) {
    float aa = max(fwidth(rho) * 1.5, 0.03);
    return 1.0 - smoothstep(1.0 - aa, 1.0, rho);
}

vec3 shade(vec3 albedo, vec3 n, Lit l, float ao, float gloss, float sheen) {
    float diffuse = max(dot(n, normalize(F.sun.xyz)), 0.0);
    float ndh = max(dot(n, l.H), 0.0);
    return albedo * (l.sun * diffuse + l.ambient) * ao + l.sun * pow(ndh, gloss) * sheen;
}

/**
 * Toʻa, a Porites coral head: knobbly lobes divided by grooves, the surface
 * pitted with tiny polyps, here and there the coloured spirals of Christmas
 * tree worms; dark turf algae around its foot.
 */
/** Distance to the nearest of a scatter of points: 0 on a hummock's crown. */
float hummock(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    float d1 = 8.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) {
            vec2 g = vec2(x, y);
            vec2 r = g + 0.2 + 0.6 * hash22(i + g) - f;
            d1 = min(d1, dot(r, r));
        }
    return sqrt(d1);
}

/** The knobbly surface of a Porites head: rounded hummocks of two sizes, creased where they meet. */
float poritesHeight(vec2 q) {
    float big = 1.0 - smoothstep(0.0, 0.85, hummock(q * 5.5 + vSeed * 7.0));
    float small = 1.0 - smoothstep(0.0, 0.8, hummock(q * 13.0 + vSeed * 13.0));
    return sqrt(big) * 0.75 + small * 0.25;
}

vec4 coralHead(Lit l, float caustic) {
    vec2 q = vUv;
    float rho = vAux.w;
    const float e = 0.012;
    float h0 = poritesHeight(q);
    vec2 g = vec2(poritesHeight(q + vec2(e, 0.0)) - poritesHeight(q - vec2(e, 0.0)),
                  poritesHeight(q + vec2(0.0, e)) - poritesHeight(q - vec2(0.0, e))) / (2.0 * e);
    vec3 n = decorNormal(g * 0.12 * (1.0 - smoothstep(0.8, 0.98, rho)));
    float crease = 1.0 - smoothstep(0.05, 0.3, h0);
    float id = hash12(floor(q * 3.2 + vSeed * 7.0));

    float k = fract(vSeed * 7.13);
    vec3 base = k < 0.35 ? srgb(vec3(0.86, 0.78, 0.6))      // cream
              : k < 0.65 ? srgb(vec3(0.78, 0.64, 0.42))     // golden brown
              : k < 0.85 ? srgb(vec3(0.74, 0.68, 0.84))     // lavender
              : srgb(vec3(0.82, 0.72, 0.52));               // sand-yellow
    vec3 albedo = base * (0.92 + 0.14 * fbm(q * 4.0 + vSeed * 5.0)) * (0.95 + 0.1 * vnoise(q * 20.0 + vSeed * 3.0));
    albedo *= 1.0 - 0.25 * crease;

    // Polyps: tiny pits scattered over it, fading out when too small to see.
    vec2 pp = q * 70.0 + vSeed * 41.0;
    vec2 pc = floor(pp);
    float pit = 1.0 - smoothstep(0.1, 0.26, length(fract(pp) - 0.5 - (hash22(pc) - 0.5) * 0.5));
    pit *= step(0.25, hash12(pc + 3.1)) * (1.0 - smoothstep(0.3, 0.7, fwidth(pp.x)));
    albedo *= 1.0 - 0.14 * pit;

    // Christmas tree worms: here and there a pair of bright spiral plumes.
    vec2 wc = q * 5.0 + vSeed * 19.0;
    float wid = hash12(floor(wc));
    if (wid > 0.84) {
        vec2 c = fract(wc) - 0.5 - (hash22(floor(wc)) - 0.5) * 0.4;
        vec3 wormC = wid > 0.97 ? srgb(vec3(0.95, 0.95, 0.9))
                   : wid > 0.93 ? srgb(vec3(0.95, 0.55, 0.08))
                   : wid > 0.89 ? srgb(vec3(0.1, 0.35, 0.95))
                   : srgb(vec3(0.85, 0.12, 0.12));
        for (int s = 0; s < 2; s++) {
            vec2 d = c - vec2(s == 0 ? 0.07 : -0.07, 0.0);
            float r = length(d) / 0.075;
            if (r < 1.0) {
                float a = atan(d.y, d.x);
                float bands = fract(a / TAU * 3.0 + r * 2.2 + (s == 0 ? 0.0 : 0.5));
                vec3 plume = mix(wormC, wormC * 0.45 + 0.55 * srgb(vec3(1.0)), smoothstep(0.55, 0.75, bands) * 0.5);
                albedo = mix(albedo, plume, 1.0 - smoothstep(0.75, 1.0, r));
            }
        }
    }

    // A fine dusting of sand on the flat top; dark turf algae round the foot.
    albedo = mix(albedo, sandAlbedo(vWorld), smoothstep(0.97, 1.0, n.z) * 0.15);
    float foot = smoothstep(0.78, 0.94, rho) * (0.6 + 0.4 * fbm(q * 9.0 + vSeed * 5.0));
    albedo = mix(albedo, srgb(vec3(0.36, 0.34, 0.22)), foot * 0.6);
    albedo = intoSand(albedo, rho);

    // Lit as a solid: wrapped diffuse so the shaded side is not black, darker in the creases and at the foot.
    vec3 L = normalize(F.sun.xyz);
    float diffuse = max(dot(n, L) * 0.8 + 0.2, 0.0);
    float ao = (1.0 - crease * 0.3) * mix(1.0, 0.65, smoothstep(0.75, 1.0, rho));
    float ndh = max(dot(n, l.H), 0.0);
    vec3 lit = albedo * (l.sun * diffuse * 1.3 + l.ambient * 0.9) * ao + l.sun * pow(ndh, 24.0) * 0.04;
    return finish(lit, decorAlpha(rho));
}

/** The colours of a branching coral: along the branch, and at its growing tips. */
void branchPalette(out vec3 base, out vec3 tip) {
    float k = fract(vSeed * 5.31);
    if (k < 0.3) {
        base = srgb(vec3(0.6, 0.48, 0.32));
        tip = srgb(vec3(0.66, 0.48, 0.82));  // tan with violet tips
    } else if (k < 0.55) {
        base = srgb(vec3(0.8, 0.74, 0.58));
        tip = srgb(vec3(0.52, 0.74, 0.92));  // cream with sky-blue tips
    } else if (k < 0.8) {
        base = srgb(vec3(0.5, 0.56, 0.36));
        tip = srgb(vec3(0.84, 0.88, 0.5));   // olive with lime tips
    } else {
        base = srgb(vec3(0.78, 0.55, 0.6));
        tip = srgb(vec3(0.96, 0.82, 0.88));  // pink
    }
}

/** How much light gets down into the thicket: the bases of the branches are in shade. */
float thicketLight() {
    float above = F.tank.z - vZ;
    return mix(0.32, 1.0, smoothstep(2.0, 20.0, above));
}

/**
 * Toʻapū, branching coral: Acropora branches studded with little cup-shaped
 * corallites, darker toward the shaded base, pale at the growing tip.
 */
vec4 coralBranch(Lit l, float caustic) {
    float u = vUv.x, v = vUv.y, av = abs(v);
    float along = vAux.x, hw = max(vAux.y, 0.2);
    vec3 n = toWorld(vec3(0.0, v * 0.9, sqrt(max(1.0 - v * v * 0.8, 0.1))));
    vec3 base, tip;
    branchPalette(base, tip);
    // Corallites as far apart along the branch as across it, so they stay round.
    vec3 c = cells(vec2(along / (hw * 0.62), v * 1.6) + vSeed * 13.0);
    float cup = 1.0 - smoothstep(0.16, 0.34, c.x);
    float pore = 1.0 - smoothstep(0.04, 0.11, c.x);
    vec3 albedo = mix(base, tip, smoothstep(0.5, 1.0, u));
    albedo *= 0.86 + 0.28 * cup;
    albedo *= 1.0 - 0.4 * pore;
    // The axial corallite at the very tip: white.
    albedo = mix(albedo, srgb(vec3(0.96, 0.94, 0.9)), smoothstep(0.9, 0.99, u) * (1.0 - av) * 0.85);
    vec3 lit = shade(albedo, n, l, thicketLight(), 40.0, 0.08);
    return finish(lit, edgeAlpha(av));
}

/** A branch growing straight up, seen end on: a round tip with a white axial corallite. */
vec4 coralTip(Lit l, float caustic) {
    float rho = length(vUv);
    vec3 n = toWorld(vec3(vUv * 0.75, sqrt(max(1.0 - rho * rho * 0.56, 0.1))));
    vec3 base, tip;
    branchPalette(base, tip);
    if (vAux.x > 0.5) {
        // The heart of the thicket: old dark coral and the shadowed bases of the branches.
        vec3 c = cells(vUv * 5.0 + vSeed * 9.0);
        vec3 albedo = base * 0.5 * (0.8 + 0.4 * (1.0 - smoothstep(0.1, 0.3, c.x)));
        vec3 lit = shade(albedo, n, l, 0.45, 20.0, 0.0);
        return finish(lit, edgeAlpha(rho));
    }
    float a = atan(vUv.y, vUv.x);
    float radial = 0.5 + 0.5 * cos(a * 7.0 + vSeed * 20.0);
    vec3 albedo = mix(tip, mix(base, tip, 0.5), smoothstep(0.3, 1.0, rho));
    albedo *= 0.9 + 0.16 * radial * smoothstep(0.4, 0.8, rho);
    albedo = mix(albedo, srgb(vec3(0.92, 0.9, 0.86)), (1.0 - smoothstep(0.1, 0.24, rho)) * 0.5);
    albedo *= 1.0 - 0.35 * (1.0 - smoothstep(0.0, 0.07, rho));  // its tiny mouth
    vec3 lit = shade(albedo, n, l, thicketLight(), 40.0, 0.1);
    return finish(lit, edgeAlpha(rho));
}

/**
 * Pahua, the giant clam (Tridacna maxima), from above: two fluted valves,
 * their scalloped lips meeting in a zigzag, and between them the mantle in
 * vivid blues, greens or browns, spotted, shimmering, with its siphons. It
 * draws the mantle in and closes the gape when frightened.
 */
vec4 giantClam(Lit l, float caustic) {
    vec2 q = vUv;  // x along the hinge, y across
    float rho = vAux.w, open = vAux.z;
    float x = q.x, ay = abs(q.y);
    float span = sqrt(max(1.0 - x * x, 0.0));
    // The valves' lips are scalloped where the shell's folds end.
    float fold = abs(fract(x * 2.6 + 0.25) - 0.5) * 2.0;
    float lip = (0.05 + 0.1 * fold) * span;
    float frill = open * (0.035 * sin(x * 30.0 + vSeed * 20.0 + timeS() * 0.9) + 0.02 * sin(x * 71.0 + vSeed * 9.0));
    float mantleW = mix(lip, 0.9 * span, open * open * (3.0 - 2.0 * open)) + frill;
    float inMantle = 1.0 - smoothstep(mantleW - 0.025, mantleW + 0.005, ay);

    // The shell: grey-cream folds, growth lines parallel to the lips, a film of algae.
    float rib = cos(x * 2.6 * TAU);
    vec2 shellBump = vec2(-sin(x * 2.6 * TAU) * 0.9, 0.0);
    float growth = smoothstep(0.75, 0.95, fract((ay - lip) * 22.0 + 0.3 * rib));
    vec3 shellC = srgb(vec3(0.82, 0.8, 0.72)) * (0.82 + 0.18 * rib) * (1.0 - 0.15 * growth);
    shellC = mix(shellC, srgb(vec3(0.45, 0.5, 0.32)), smoothstep(0.55, 0.85, fbm(q * 6.0 + vSeed * 7.0)) * 0.45);

    // The mantle.
    float k = fract(vSeed * 3.77);
    vec3 m0, m1, rim;
    if (k < 0.3) {
        m0 = srgb(vec3(0.08, 0.32, 0.85)); m1 = srgb(vec3(0.02, 0.1, 0.42)); rim = srgb(vec3(0.35, 0.9, 0.95));
    } else if (k < 0.55) {
        m0 = srgb(vec3(0.08, 0.58, 0.52)); m1 = srgb(vec3(0.95, 0.8, 0.3)); rim = srgb(vec3(0.55, 0.95, 0.72));
    } else if (k < 0.8) {
        m0 = srgb(vec3(0.42, 0.3, 0.17)); m1 = srgb(vec3(0.22, 0.55, 0.95)); rim = srgb(vec3(0.9, 0.76, 0.42));
    } else {
        m0 = srgb(vec3(0.32, 0.18, 0.68)); m1 = srgb(vec3(0.85, 0.6, 0.95)); rim = srgb(vec3(0.42, 0.8, 1.0));
    }
    float sid;
    vec4 sp = lumps(vec2(q.x * 9.0, q.y * 14.0) + vSeed * 17.0, sid);
    float spot = 1.0 - smoothstep(0.18, 0.3, sp.x);
    vec3 mantle = mix(m0, m1, spot * 0.85);
    // Wavy lines running along the mantle, and a bright band at its frilled edge.
    mantle *= 0.88 + 0.12 * sin((ay / max(mantleW, 0.05)) * 18.0 + vnoise(q * 8.0) * 4.0);
    mantle = mix(mantle, rim, smoothstep(0.72, 0.95, ay / max(mantleW, 0.05)) * 0.8);
    // Iridophores: pinpoints that shimmer as the light moves.
    vec2 ip = q * vec2(38.0, 52.0);
    float glint = (1.0 - smoothstep(0.12, 0.3, length(fract(ip) - 0.5))) * step(0.72, hash12(floor(ip) + vSeed));
    glint *= 0.5 + 0.5 * sin(timeS() * 2.3 + hash12(floor(ip)) * 40.0);
    mantle += rim * glint * 0.6 * open;
    // The incurrent siphon at one end, frilled; the excurrent one a small raised tube.
    vec2 si = (q - vec2(0.52, 0.0)) / vec2(0.13, 0.08);
    float sd = length(si);
    float ring = smoothstep(0.55, 0.7, sd) * (1.0 - smoothstep(0.9, 1.05, sd));
    float tentacles = step(0.5, fract(atan(si.y, si.x) / TAU * 16.0));
    mantle = mix(mantle, rim * 0.8, ring * tentacles * open);
    mantle = mix(mantle, srgb(vec3(0.02, 0.03, 0.05)), (1.0 - smoothstep(0.5, 0.6, sd)) * open);
    float ed = length((q - vec2(-0.12, 0.0)) / vec2(0.06, 0.05));
    mantle = mix(mantle, mantle * 1.5 + 0.05, smoothstep(0.55, 0.75, ed) * (1.0 - smoothstep(0.85, 1.0, ed)) * open);
    mantle = mix(mantle, m1 * 0.3, (1.0 - smoothstep(0.4, 0.55, ed)) * open);
    // Barely open, only the dark inner edge of the mantle shows in the gape.
    mantle = mix(m1 * 0.25, mantle, smoothstep(0.1, 0.5, open));

    vec2 bump = mix(shellBump, vec2(0.0, sign(q.y) * 0.35), inMantle);
    vec3 n = decorNormal(bump);
    vec3 albedo = intoSand(mix(shellC, mantle, inMantle), rho);
    vec3 lit = shade(albedo, n, l, 1.0, mix(20.0, 70.0, inMantle), mix(0.04, 0.35, inMantle));
    return finish(lit, decorAlpha(rho));
}

/**
 * ʻŌfaʻi, a stone of the lagoon: rough dark basalt with lighter patches and
 * pits, crusts of pink coralline algae on its sides, green-brown turf on top.
 */
float stoneHeight(vec2 q) { return fbm(q * 5.0 + vSeed * 11.0) * 0.6 + vnoise(q * 16.0 + vSeed * 3.0) * 0.25; }

vec4 stone(Lit l, float caustic) {
    vec2 q = vUv;
    float rho = vAux.w;
    const float e = 0.015;
    vec2 g = vec2(stoneHeight(q + vec2(e, 0.0)) - stoneHeight(q - vec2(e, 0.0)),
                  stoneHeight(q + vec2(0.0, e)) - stoneHeight(q - vec2(0.0, e))) / (2.0 * e);
    vec3 n = decorNormal(g * 0.18 * (1.0 - smoothstep(0.8, 1.0, rho)));
    float patches = fbm(q * 3.2 + vSeed * 4.0);
    vec3 albedo = mix(srgb(vec3(0.42, 0.39, 0.35)), srgb(vec3(0.6, 0.56, 0.5)), smoothstep(0.35, 0.7, patches));
    albedo *= 0.9 + 0.2 * vnoise(q * 24.0 + vSeed);
    // Small pits left by gas bubbles in the lava, few and irregular.
    vec3 c = cells(q * 9.0 + vSeed * 9.0);
    albedo *= 1.0 - 0.3 * (1.0 - smoothstep(0.05, 0.1, c.x)) * step(0.55, c.z);
    // Pink coralline crusts in small blotches, on the sides more than the top.
    float crust = smoothstep(0.62, 0.7, fbm(q * 6.0 + vSeed * 23.0) + 0.1 * smoothstep(0.5, 0.9, rho));
    albedo = mix(albedo, srgb(vec3(0.8, 0.6, 0.6)) * (0.88 + 0.24 * vnoise(q * 30.0)), crust * 0.7);
    // A soft fuzz of turf algae where the light falls.
    float turf = smoothstep(0.85, 0.97, n.z) * smoothstep(0.5, 0.65, fbm(q * 5.0 + vSeed * 2.0 + 5.0));
    albedo = mix(albedo, srgb(vec3(0.42, 0.46, 0.26)) * (0.9 + 0.2 * vnoise(q * 40.0)), turf * 0.6);
    albedo = intoSand(albedo, rho);
    vec3 lit = shade(albedo, n, l, mix(1.0, 0.75, smoothstep(0.8, 1.0, rho)), 24.0, 0.04);
    return finish(lit, decorAlpha(rho));
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
        case 11: outColor = coralHead(l, caustic); break;
        case 12: outColor = vPart == 1 ? coralTip(l, caustic) : coralBranch(l, caustic); break;
        case 13: outColor = giantClam(l, caustic); break;
        case 14: outColor = stone(l, caustic); break;
        default: outColor = vPart == 0 ? fishBody(l, caustic) : fishFin(l, caustic); break;  // the schooling fish
    }
}
