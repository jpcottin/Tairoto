#pragma once
// The water's movement on the table plane: a coarse velocity grid stirred by
// swimming fish, strokes and taps, that spreads, carries itself along and dies
// down, over a slow gyre that never stops. Food drifts with it.
#include <vector>

#include "core/math.h"

namespace dt {

struct Tank;

class CurrentField {
public:
    static constexpr float kCell = 6.0f;  // mm

    void resize(float width, float height);
    /** Velocity (mm/s) at a point: the gyre plus whatever has been stirred up. */
    Vec2 at(Vec2 p) const;
    /** Stirred-up velocity only, without the gyre. */
    Vec2 stirredAt(Vec2 p) const;
    /** Adds dv (mm/s) around p, fading out over radius (mm). */
    void push(Vec2 p, Vec2 dv, float radius);
    void step(float dt);
    float maxStirred() const;
    int columns() const { return nx_; }
    int rows() const { return ny_; }

private:
    Vec2 sample(const std::vector<Vec2>& f, Vec2 p) const;
    Vec2 gyre(Vec2 p) const;
    int nx_ = 0, ny_ = 0;
    float width_ = 0, height_ = 0;
    float time_ = 0;
    std::vector<Vec2> v_, tmp_;
};

}  // namespace dt
