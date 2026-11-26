#version 450
#extension GL_GOOGLE_include_directive : require
#include "common.glsl"

// The water surface between the scene and the eye: it bends what lies below
// (refraction), throws sparkles of sun, rings where the glass was tapped,
// then tone mapping, a gentle grade, vignette and dither to the screen.

layout(set = 1, binding = 0) uniform sampler2D scene;
layout(location = 0) out vec4 outColor;

// Gradient of the surface height at p (mm), as d(height)/dx, d(height)/dy.
vec2 surfaceSlope(vec2 p, float t) {
    vec2 g = vec2(0.0);
    // A few long swells.
    const vec3 waves[5] = vec3[5](
        vec3(0.8, 0.6, 38.0), vec3(-0.5, 0.86, 23.0), vec3(0.95, -0.3, 15.0),
        vec3(-0.2, -0.98, 9.5), vec3(0.6, 0.8, 6.3));
    for (int i = 0; i < 5; i++) {
        vec2 d = normalize(waves[i].xy);
        float k = TAU / waves[i].z;
        float w = sqrt(9.8 * k * 120.0) * 0.06;  // slow, deep-water-like dispersion
        float a = 0.018 * waves[i].z / 38.0 + 0.004;
        g += d * (a * k * cos(dot(d, p) * k - w * t + float(i) * 1.7));
    }
    // Fine capillary ripples, for the sparkles.
    vec2 q = p * 0.42 + vec2(t * 0.55, -t * 0.4);
    float e = 0.5;
    float n0 = vnoise(q), nx = vnoise(q + vec2(e, 0.0)), ny = vnoise(q + vec2(0.0, e));
    g += vec2(nx - n0, ny - n0) / e * 0.42 * 0.09;
    // Rings from taps on the glass.
    for (int i = 0; i < 8; i++) {
        vec4 r = F.ripples[i];
        if (r.w <= 0.0) continue;
        float age = t - r.z;
        if (age < 0.0 || age > 4.0) continue;
        vec2 d = p - r.xy;
        float dist = length(d);
        float front = 70.0 * age;
        float x = dist - front;
        float envelope = exp(-x * x / (2.0 * 6.0 * 6.0)) * exp(-age * 1.1) * r.w;
        float k = TAU / 5.5;
        float slope = envelope * k * cos(x * k) * 0.22;
        if (dist > 1e-3) g += d / dist * slope;
    }
    return g;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    vec2 px = gl_FragCoord.xy;
    vec2 uv = px / F.outSize.xy;
    float t = timeS();
    vec2 p = screenToWorld(px, 0.0);

    vec2 slope = surfaceSlope(p, t);
    vec3 n = normalize(vec3(-slope, 1.0));

    vec3 color;
    bool refract = F.misc.z > 0.0;
    if (refract) {
        // Bend the view by the surface slope; through deeper water the shift is larger.
        vec2 shift = n.xy * F.misc.z * F.cam.z / F.outSize.xy;
        if (ultra()) {
            color = vec3(texture(scene, uv + shift * 0.94).r,
                         texture(scene, uv + shift).g,
                         texture(scene, uv + shift * 1.08).b);
        } else {
            color = texture(scene, uv + shift).rgb;
        }
    } else {
        color = texture(scene, uv).rgb;
    }

    // Sun glints on the ripples, in clusters.
    vec3 L = normalize(F.sun.xyz);
    vec3 H = normalize(L + vec3(0.0, 0.0, 1.0));
    float ndh = max(dot(n, H), 0.0);
    float cluster = smoothstep(0.45, 0.8, vnoise(p * 0.05 + t * 0.07));
    float glint = pow(ndh, 1400.0) * 9.0 * (0.3 + cluster);
    color += srgb(vec3(1.0, 0.97, 0.88)) * glint * F.sun.w;

    // A faint reflection of the sky, stronger where the surface tilts.
    float fresnel = 0.02 + 0.6 * pow(1.0 - n.z, 3.0);
    color += srgb(vec3(0.72, 0.86, 1.0)) * fresnel * 0.35;

    // Tone map, then a gentle grade: cool shadows, warm light.
    color *= F.misc.w;
    color = aces(color);
    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(color, color * vec3(0.92, 1.02, 1.06), 1.0 - smoothstep(0.0, 0.5, luma));
    color = mix(vec3(luma), color, 1.12);
    // A gentle S-curve for depth.
    color = mix(color, color * color * (3.0 - 2.0 * color), 0.35);

    float vig = length((uv - 0.5) * vec2(1.0, F.outSize.y / F.outSize.x * 0.6));
    color *= mix(1.0, 0.62, smoothstep(0.3, 1.0, vig));

    color = pow(max(color, 0.0), vec3(1.0 / 2.2));
    color += (hash12(px + fract(t) * 91.0) - 0.5) / 255.0;
    outColor = vec4(color, 1.0);
}
