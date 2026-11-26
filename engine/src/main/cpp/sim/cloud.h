#pragma once
// Clouds in the water: sand thrown up by a ray burying itself or leaving the
// sand, by a crab or a turtle; ink from a frightened octopus. Each one grows,
// drifts with the current and fades; sand settles back down.
#include <cstdint>

#include "core/math.h"

namespace dt {

enum class CloudKind : int32_t { Sand = 0, Ink = 1 };

struct Cloud {
    CloudKind kind = CloudKind::Sand;
    uint32_t seed = 0;
    Vec3 pos;
    Vec2 vel;          // mm/s, slowed by the water
    float vz = 0;      // mm/s, positive is down
    float radius = 2;  // mm
    float growth = 4;  // mm/s at birth, slowing
    float density = 1; // opacity at the core when young
    float age = 0;
    float life = 3;    // seconds
    float spin = 0;

    float fade() const {
        float t = age / life;
        return t >= 1 ? 0.0f : (1.0f - t) * (1.0f - t);
    }
};

struct CloudConstants {
    static constexpr int kMax = 360;
};

}  // namespace dt
