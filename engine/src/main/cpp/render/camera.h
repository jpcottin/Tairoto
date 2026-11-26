#pragma once
// The top-down projection, the same formula as Camera.kt and the shaders:
//   screen = outSize/2 + ((p - origin) * s(z) + (origin - center)) * pxPerMm
//   s(z)   = H / (H + z)
#include "core/math.h"

namespace dt {

struct CameraParams {
    Vec2 center{100, 150};
    float pxPerMm = 16.5f;
    float camHeight = 180.0f;
    Vec2 origin{100, 150};   // parallax origin; equals center on a device alone
    Vec2 outSize{1080, 2400};
};

inline float depthScale(float camHeight, float z) { return camHeight / (camHeight + (z > 0 ? z : 0)); }

inline Vec2 worldToScreen(const CameraParams& c, Vec2 p, float z) {
    float s = depthScale(c.camHeight, z);
    return c.outSize * 0.5f + ((p - c.origin) * s + (c.origin - c.center)) * c.pxPerMm;
}

inline Vec2 screenToWorld(const CameraParams& c, Vec2 screen, float z) {
    float s = depthScale(c.camHeight, z);
    return c.origin + ((screen - c.outSize * 0.5f) / c.pxPerMm - (c.origin - c.center)) / s;
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
