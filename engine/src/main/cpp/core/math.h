#pragma once
// Small vector maths and a deterministic random generator, shared by the
// simulation, the mesh builder and the host tests. No platform headers.
#include <cmath>
#include <cstdint>

namespace dt {

constexpr float kPi = 3.14159265358979f;
constexpr float kTau = 6.28318530717959f;

struct Vec2 {
    float x = 0, y = 0;
    Vec2() = default;
    constexpr Vec2(float x_, float y_) : x(x_), y(y_) {}
    Vec2 operator+(Vec2 o) const { return {x + o.x, y + o.y}; }
    Vec2 operator-(Vec2 o) const { return {x - o.x, y - o.y}; }
    Vec2 operator*(float k) const { return {x * k, y * k}; }
    Vec2 operator/(float k) const { return {x / k, y / k}; }
    Vec2 operator-() const { return {-x, -y}; }
    Vec2& operator+=(Vec2 o) { x += o.x; y += o.y; return *this; }
    Vec2& operator-=(Vec2 o) { x -= o.x; y -= o.y; return *this; }
    Vec2& operator*=(float k) { x *= k; y *= k; return *this; }
};

inline float dot(Vec2 a, Vec2 b) { return a.x * b.x + a.y * b.y; }
inline float cross(Vec2 a, Vec2 b) { return a.x * b.y - a.y * b.x; }
inline float length(Vec2 a) { return std::sqrt(dot(a, a)); }
inline float length2(Vec2 a) { return dot(a, a); }
inline Vec2 normalize(Vec2 a, Vec2 fallback = {1, 0}) {
    float l = length(a);
    return l > 1e-6f ? a / l : fallback;
}
/** Rotated a quarter turn: (x, y) -> (-y, x). With y down this points to the right of the heading. */
inline Vec2 perp(Vec2 a) { return {-a.y, a.x}; }
inline Vec2 fromAngle(float a) { return {std::cos(a), std::sin(a)}; }
inline float angleOf(Vec2 a) { return std::atan2(a.y, a.x); }
inline Vec2 rotate(Vec2 v, float a) {
    float c = std::cos(a), s = std::sin(a);
    return {v.x * c - v.y * s, v.x * s + v.y * c};
}

struct Vec3 {
    float x = 0, y = 0, z = 0;
    Vec3() = default;
    constexpr Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}
    Vec2 xy() const { return {x, y}; }
};

template <typename T> inline T clamp(T v, T lo, T hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float saturate(float v) { return clamp(v, 0.0f, 1.0f); }
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline Vec2 lerp(Vec2 a, Vec2 b, float t) { return a + (b - a) * t; }
inline float smoothstep(float e0, float e1, float x) {
    float t = saturate((x - e0) / (e1 - e0));
    return t * t * (3.0f - 2.0f * t);
}
/** Wraps an angle to (-pi, pi]. */
inline float wrapAngle(float a) {
    a = std::fmod(a + kPi, kTau);
    if (a < 0) a += kTau;
    return a - kPi;
}
/** Moves angle a toward b by at most maxStep, the short way round. */
inline float approachAngle(float a, float b, float maxStep) {
    float d = wrapAngle(b - a);
    if (d > maxStep) d = maxStep;
    if (d < -maxStep) d = -maxStep;
    return wrapAngle(a + d);
}
inline float approach(float v, float target, float maxStep) {
    if (v < target) return v + std::fmin(maxStep, target - v);
    return v - std::fmin(maxStep, v - target);
}
/** Frame-rate independent exponential smoothing: fraction kept after dt at a given rate. */
inline float damp(float current, float target, float rate, float dt) {
    return lerp(target, current, std::exp(-rate * dt));
}

/** PCG32: small, fast and the same on every platform, so a seed gives the same tank everywhere. */
class Rng {
public:
    explicit Rng(uint64_t seed = 0x853c49e6748fea9bULL) { reseed(seed); }
    void reseed(uint64_t seed) {
        state_ = 0;
        next();
        state_ += seed;
        next();
    }
    uint32_t next() {
        uint64_t old = state_;
        state_ = old * 6364136223846793005ULL + 1442695040888963407ULL;
        uint32_t xorshifted = (uint32_t)(((old >> 18u) ^ old) >> 27u);
        uint32_t rot = (uint32_t)(old >> 59u);
        return (xorshifted >> rot) | (xorshifted << ((-rot) & 31));
    }
    /** Uniform in [0, 1). */
    float uniform() { return (next() >> 8) * (1.0f / 16777216.0f); }
    float range(float lo, float hi) { return lo + (hi - lo) * uniform(); }
    bool chance(float p) { return uniform() < p; }

private:
    uint64_t state_ = 0;
};

/** A stateless hash to [0, 1), for per-creature constants derived from a seed. */
inline float hash01(uint32_t x) {
    x ^= x >> 16; x *= 0x7feb352dU;
    x ^= x >> 15; x *= 0x846ca68bU;
    x ^= x >> 16;
    return (x >> 8) * (1.0f / 16777216.0f);
}

/** Smooth 1D value noise in [-1, 1], for wandering. */
inline float noise1(float t, uint32_t seed) {
    float i = std::floor(t);
    float f = t - i;
    auto h = [&](float k) { return hash01((uint32_t)(int32_t)k * 2654435761u ^ seed) * 2.0f - 1.0f; };
    float u = f * f * (3.0f - 2.0f * f);
    return lerp(h(i), h(i + 1.0f), u);
}

}  // namespace dt
