#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// The sand at the bottom: wind-ripple dunes, grains, pebbles and bits of
// shell, lit by the sun through the moving net of caustics, seen through the
// whole depth of water.

layout(location = 0) out vec4 outColor;

float rippleHeight(vec2 q0) {
    vec2 p = q0 * 2.0;  // features were drawn for a world twice as large
    // Ripples run across the tank and meander, with their strength in patches.
    vec2 warp = vec2(fbm(p * 0.018), fbm(p * 0.018 + 9.7)) - 0.5;
    vec2 q = p + warp * 26.0;
    float phase = dot(q, normalize(vec2(0.28, 1.0))) * (TAU / 8.5);
    float crest = pow(0.5 + 0.5 * sin(phase + 0.6 * sin(phase * 0.5)), 1.7);
    float strength = mix(0.25, 1.0, smoothstep(0.3, 0.7, fbm(p * 0.011 + 3.1)));
    return crest * 0.55 * strength + vnoise(p * 1.7) * 0.07;
}

struct Pebble {
    float h;      // height of the pebble surface (0 when none)
    vec3 color;
    float ao;     // contact darkening around it
    float mask;   // 1 on the pebble
};

Pebble pebbles(vec2 q0) {
    vec2 p = q0 * 2.0;
    Pebble r;
    r.h = 0.0;
    r.color = vec3(0.0);
    r.ao = 1.0;
    r.mask = 0.0;
    const float cell = 34.0;
    vec2 g = floor(p / cell);
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 c = g + vec2(x, y);
            float k = hash12(c * 1.37 + 0.11);
            if (k > 0.13) continue;
            vec2 at = (c + 0.15 + 0.7 * hash22(c)) * cell;
            float size = mix(1.2, 4.5, hash12(c + 5.3));
            // Slightly oval and turned at random.
            float ang = hash12(c + 2.2) * PI;
            mat2 rot = mat2(cos(ang), sin(ang), -sin(ang), cos(ang));
            vec2 d = rot * (p - at);
            d.x *= mix(0.75, 1.0, hash12(c + 8.1));
            float dist = length(d) / size;
            r.ao = min(r.ao, mix(0.78, 1.0, smoothstep(0.9, 1.6, dist)));
            if (dist < 1.0) {
                r.h = sqrt(1.0 - dist * dist) * size * 0.6;
                r.mask = smoothstep(1.0, 0.9, dist);
                float tint = hash12(c + 4.4);
                vec3 stone = mix(srgb(vec3(0.78, 0.74, 0.66)), srgb(vec3(0.6, 0.55, 0.47)), tint);
                vec3 shell = srgb(vec3(0.97, 0.93, 0.85));
                vec3 coral = srgb(vec3(0.93, 0.75, 0.7));
                r.color = tint > 0.8 ? coral : (tint > 0.5 ? shell : stone);
                r.color *= 0.85 + 0.3 * vnoise(d * 3.0 + c);
            }
        }
    }
    return r;
}

float heightAt(vec2 p) {
    Pebble pb = pebbles(p);
    return rippleHeight(p) + pb.h;
}

void main() {
    vec2 px = scenePixelToOutput(gl_FragCoord.xy);
    float D = F.tank.z;
    vec2 p = screenToWorld(px, D);
    float t = timeS();

    // Surface normal from the height field (y grows down the screen).
    float e = 0.18;
    float h0 = heightAt(p);
    float hx = heightAt(p + vec2(e, 0.0));
    float hy = heightAt(p + vec2(0.0, e));
    vec3 n = normalize(vec3(-(hx - h0) / e, -(hy - h0) / e, 1.0));

    Pebble pb = pebbles(p);

    // Sand: two tones in drifts, patches of green algae, grains light and dark.
    vec3 sandA = srgb(vec3(0.95, 0.88, 0.72));
    vec3 sandB = srgb(vec3(0.84, 0.75, 0.58));
    vec2 pf = p * 2.0;
    vec3 albedo = mix(sandA, sandB, smoothstep(0.35, 0.75, fbm(pf * 0.04)));
    float algae = smoothstep(0.6, 0.78, fbm(pf * 0.009 + 7.0));
    albedo = mix(albedo, srgb(vec3(0.42, 0.5, 0.33)), algae * 0.45);
    vec2 grain = floor(pf * 2.4);
    float gk = hash12(grain);
    albedo *= gk > 0.96 ? 0.8 : (gk < 0.03 ? 1.15 : 1.0);
    albedo *= 0.94 + 0.12 * vnoise(pf * 4.1);
    // Crests catch the light, troughs gather darker grains.
    albedo *= 0.9 + 0.18 * smoothstep(0.1, 0.5, rippleHeight(p));

    albedo = mix(albedo, pb.color, pb.mask);
    albedo *= pb.ao;

    vec3 L = normalize(F.sun.xyz);
    float diffuse = max(dot(n, L), 0.0);
    int layers = quality() < 0.5 ? 1 : 2;
    float c;
    vec3 caustic;
    if (ultra()) {
        // A little dispersion: the colours of the net split at its edges.
        caustic = vec3(causticsAt(p + vec2(0.25, 0.0), t, layers),
                       causticsAt(p, t, layers),
                       causticsAt(p - vec2(0.25, 0.0), t, layers));
        c = caustic.g;
    } else {
        c = causticsAt(p, t, layers);
        caustic = vec3(c);
    }
    // Big slow patches of brighter and darker water above.
    float cloud = mix(0.75, 1.15, fbm(pf * 0.006 + t * 0.012));

    vec3 sunLight = srgb(vec3(1.0, 0.97, 0.9)) * F.sun.w * cloud;
    vec3 light = sunLight * diffuse * (0.62 + 0.95 * caustic) + srgb(vec3(0.4, 0.62, 0.75)) * 0.3;
    vec3 color = albedo * light;

    // Darker in the corners where the glass meets the sand.
    float wall = min(min(p.x, p.y), min(F.tank.x - p.x, F.tank.y - p.y));
    color *= mix(0.35, 1.0, smoothstep(-2.0, 11.0, wall));

    outColor = vec4(throughWater(color, D), 1.0);
}
