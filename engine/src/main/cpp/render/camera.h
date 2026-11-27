#pragma once
// The top-down projection, the same formula as Camera.kt and the shaders:
//   screen = outSize/2 + R⁻¹((p - origin) * s(z) + (origin - center)) * pxPerMm
//   s(z)   = H / (H + z)
// where R turns a vector on the glass onto the table, for a screen lying
// turned by quarter turns clockwise (Turns in Geometry.kt).
#include "core/math.h"

namespace dt {

struct CameraParams {
    Vec2 center{100, 150};
    float pxPerMm = 16.5f;
    float camHeight = 180.0f;
    Vec2 origin{100, 150};   // parallax origin; equals center on a device alone
    Vec2 outSize{1080, 2400};
    int turns = 0;           // quarter turns clockwise of the screen on the table

    float turnCos() const { int t = ((turns % 4) + 4) % 4; return t == 0 ? 1.0f : (t == 2 ? -1.0f : 0.0f); }
    float turnSin() const { int t = ((turns % 4) + 4) % 4; return t == 1 ? 1.0f : (t == 3 ? -1.0f : 0.0f); }
    /** A vector on the table as the glass sees it. */
    Vec2 toScreen(Vec2 v) const { float c = turnCos(), s = turnSin(); return Vec2(c * v.x + s * v.y, -s * v.x + c * v.y); }
    /** A vector on the glass as it lies on the table. */
    Vec2 toTable(Vec2 v) const { float c = turnCos(), s = turnSin(); return Vec2(c * v.x - s * v.y, s * v.x + c * v.y); }
};

inline float depthScale(float camHeight, float z) { return camHeight / (camHeight + (z > 0 ? z : 0)); }

inline Vec2 worldToScreen(const CameraParams& c, Vec2 p, float z) {
    float s = depthScale(c.camHeight, z);
    return c.outSize * 0.5f + c.toScreen((p - c.origin) * s + (c.origin - c.center)) * c.pxPerMm;
}

inline Vec2 screenToWorld(const CameraParams& c, Vec2 screen, float z) {
    float s = depthScale(c.camHeight, z);
    return c.origin + (c.toTable((screen - c.outSize * 0.5f) / c.pxPerMm) - (c.origin - c.center)) / s;
}

/** The light comes from above, a little from the top left: shadows fall down and right. */
struct Sun {
    static constexpr float kDirX = -0.2f, kDirY = -0.26f, kDirZ = 0.944f;  // toward the sun
    /** Where a point at depth z casts its shadow on sand at depth floorZ. */
    static Vec2 shadowOffset(float z, float floorZ) {
        float h = floorZ - z;
        if (h < 0) h = 0;
        return Vec2(-kDirX / kDirZ, -kDirY / kDirZ) * h;
    }
};

}  // namespace dt
