#pragma once
// The tank simulation: schooling fish and a turtle, stepped at a fixed rate so
// a seed and a sequence of inputs always give the same tank. No rendering and
// no platform code here, so the host tests run it directly.
#include <cstdint>
#include <vector>

#include "core/math.h"
#include "sim/creature.h"

namespace dt {

struct Tank {
    float width = 200, height = 300, depth = 110;
};

/** A uniform grid over the tank for neighbour queries, rebuilt every step. */
class SpatialGrid {
public:
    void rebuild(const std::vector<Creature>& cs, const Tank& tank, float cell);
    /** Calls f(index) for every creature in the cells touching the circle. */
    template <typename F> void query(Vec2 p, float radius, F&& f) const {
        if (cols_ == 0) return;
        int x0 = clamp((int)((p.x - radius) / cell_), 0, cols_ - 1);
        int x1 = clamp((int)((p.x + radius) / cell_), 0, cols_ - 1);
        int y0 = clamp((int)((p.y - radius) / cell_), 0, rows_ - 1);
        int y1 = clamp((int)((p.y + radius) / cell_), 0, rows_ - 1);
        for (int y = y0; y <= y1; y++)
            for (int x = x0; x <= x1; x++) {
                int c = y * cols_ + x;
                for (int k = start_[c]; k < start_[c + 1]; k++) f(items_[k]);
            }
    }

private:
    float cell_ = 1;
    int cols_ = 0, rows_ = 0;
    std::vector<int> start_;
    std::vector<int> items_;
    std::vector<int> cellOf_;
};

class World {
public:
    static constexpr float kStep = 1.0f / 120.0f;
    static constexpr int kMaxSubsteps = 12;

    void setTank(const Tank& t);
    const Tank& tank() const { return tank_; }
    void setSeed(uint64_t seed) { rng_.reseed(seed); }

    /** Adds a creature at a point; false if the id is taken. */
    bool spawn(int64_t id, Visual visual, Locomotion loco, const SpeciesParams& sp, float length,
               uint32_t seed, Vec3 at);
    bool remove(int64_t id);
    void clear() { creatures_.clear(); }

    /** Advances by dt seconds in fixed steps (any remainder carries to the next call). */
    void step(float dt);

    /** A tap on the glass: nearby fish dart away, a wave of fright spreading outwards. */
    void startle(Vec2 at, float strength = 1.0f);

    const std::vector<Creature>& creatures() const { return creatures_; }
    std::vector<Creature>& creaturesForTest() { return creatures_; }
    const Creature* find(int64_t id) const;
    float time() const { return time_; }
    int count(Visual v) const;

    /** Where a school of this look is heading at time t: a slow loop over the tank. */
    Vec2 schoolAttractor(Visual v, float t) const;

private:
    void stepFixed(float h);
    void stepFish(Creature& c, float h);
    void stepTurtle(Creature& c, float h);
    void chooseTurtleBehavior(Creature& c);
    Vec2 wallAvoidance(Vec2 p, Vec2 fwd, float lookAhead, float margin) const;
    void clampInside(Creature& c, float margin) const;
    static void updateSpine(Creature& c, float h);

    Tank tank_;
    std::vector<Creature> creatures_;
    SpatialGrid grid_;
    Rng rng_{1};
    float time_ = 0;
    float accumulator_ = 0;
};

}  // namespace dt
