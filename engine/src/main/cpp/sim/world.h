#pragma once
// The tank simulation: schooling fish and a turtle, stepped at a fixed rate so
// a seed and a sequence of inputs always give the same tank. No rendering and
// no platform code here, so the host tests run it directly.
#include <cstdint>
#include <vector>

#include <unordered_map>

#include "core/math.h"
#include "sim/cloud.h"
#include "sim/creature.h"
#include "sim/current.h"
#include "sim/decor.h"
#include "sim/food.h"

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

    // ---- food
    /** Drops food at a point of the surface; returns how many pieces fitted under the cap. */
    int dropFood(Vec2 at, FoodKind kind, int count);
    const std::vector<Food>& food() const { return food_; }
    std::vector<Food>& foodForTest() { return food_; }
    const FoodLedger& ledger() const { return ledger_; }
    const std::vector<Scent>& scents() const { return scents_; }
    /** Mass of all the food still in the tank (mg). */
    double foodMass() const;
    const CurrentField& current() const { return current_; }
    CurrentField& currentForTest() { return current_; }
    const Food* findFood(uint32_t id) const;

    // ---- clouds of sand and ink
    /** Adds a cloud; false when the cap is reached. */
    bool puff(CloudKind kind, Vec3 at, Vec2 vel, float radius, float density, float life);
    const std::vector<Cloud>& clouds() const { return clouds_; }
    int inkClouds() const;

    // ---- bottom dwellers
    /** Where the ray's mouth, the octopus's arm tip or the crab's claws take food. */
    static Vec2 octopusFront(const Creature& c) { return fromAngle(c.mantleAngle + kPi); }
    /** Where a creature is drawn around its position, for culling and hit tests (mm). */
    static float extentOf(const Creature& c);
    /**
     * The highest point of a creature (the smallest z): a crab's shell, an
     * octopus's mantle, a turtle's carapace. Things are drawn in this order.
     */
    static float topOf(const Creature& c);
    /**
     * The deepest a swimmer may go where it is: just above anything lying on
     * the sand beneath it (a crab, an octopus, a resting turtle, a buried ray).
     */
    float floorUnder(const Creature& c) const;
    /** Rises smoothly (not in a jump) to stay above what lies on the sand beneath. */
    float riseAbove(const Creature& c, float z, float h) const {
        float limit = floorUnder(c);
        return z > limit ? std::max(limit, z - 30.0f * h) : z;
    }
    /** The rest point of a hermit crab's foot, on the sand. */
    static Vec2 crabFootRest(const Creature& c, int leg);
    /** The root of a hermit crab's leg, at the shell's opening. */
    static Vec2 crabLegRoot(const Creature& c, int leg);

    // ---- decorations: coral heads, branching coral, giant clams, stones
    /** Puts a decoration on the sand; false if the id is taken. */
    bool placeDecor(int64_t id, DecorKind kind, uint32_t seed, Vec2 at, float angle, float radius, float height);
    bool moveDecor(int64_t id, Vec2 at, float angle);
    bool removeDecor(int64_t id);
    void clearDecor() { decor_.clear(); }
    const std::vector<Decor>& decor() const { return decor_; }
    std::vector<Decor>& decorForTest() { return decor_; }
    const Decor* findDecor(int64_t id) const;
    /** How high the decorations stand above the sand at p (the highest of them; 0 on open sand). */
    float decorHeightAt(Vec2 p) const;
    /** True if p (padded by pad mm) is on the footprint of a decoration. */
    bool onDecor(Vec2 p, float pad) const;
    /** May this creature slip in among the branches of d? Only small reef fish do. */
    static bool shelters(const Decor& d, const Creature& c);
    /** How far below its centre a creature's body reaches (mm), for clearances. */
    static float bodyBelow(const Creature& c);

private:
    void stepFixed(float h);
    void stepFish(Creature& c, float h);
    void stepTurtle(Creature& c, float h);
    void chooseTurtleBehavior(Creature& c);
    void stepRay(Creature& c, float h);
    void chooseRayBehavior(Creature& c);
    void stepOctopus(Creature& c, float h);
    void updateArms(Creature& c, float h, float spreadK, float curlK, float muscle, Vec2 grabAt);
    void stepCrab(Creature& c, float h);
    void stepLegs(Creature& c, float h, Vec2 travel);
    /** Something big and dangerous close to a crab, or 0. */
    float crabThreat(const Creature& c) const;
    void sandBurst(Vec2 at, float radius, int count, float strength);
    void stepClouds(float h);
    void initBottomDweller(Creature& c);
    Vec2 wallAvoidance(Vec2 p, Vec2 fwd, float lookAhead, float margin) const;
    void clampInside(Creature& c, float margin) const;
    static void updateSpine(Creature& c, float h);
    /** A push away from decorations ahead that stand taller than the creature swims. */
    Vec2 decorAvoidance(const Creature& c, Vec2 fwd, float lookAhead, float pad) const;
    /** Moves a creature out of any decoration it has got into (sideways, never up or down). */
    void keepOutOfDecor(Creature& c, float pad) const;
    /** The nearest decoration of a kind within maxDist of p (edge to p), or null. */
    const Decor* nearestDecor(Vec2 p, float maxDist, bool (*accept)(const Decor&)) const;
    /** A random point of open sand at least margin from the walls and clear of decorations. */
    Vec2 openSand(float margin, float pad);
    /** Where an octopus would tuck itself in against a coral head or a stone, if one is near. */
    bool denFor(const Creature& c, Vec2& at) const;
    void stepDecor(float h);

    void stepFood(float h);
    void settleFood();
    void buildScents(float h);
    /** Picks food to chase; true with its position when the creature is feeding. */
    bool forage(Creature& c, Vec2 mouth, float h, Vec3& foodAt);
    /** Eats from the food it is chasing if the mouth reaches it. */
    void tryBite(Creature& c, Vec2 mouth, float mouthZ, float reach);
    void bite(Creature& c, Food& f);
    int scentCellOf(Vec2 p) const;
    Food* findFoodMutable(uint32_t id);

    Tank tank_;
    std::vector<Creature> creatures_;
    SpatialGrid grid_;
    Rng rng_{1};
    float time_ = 0;
    float accumulator_ = 0;

    std::vector<Food> food_;
    std::vector<Food> pendingFood_;  // crumbs made during a step, added at its end
    std::unordered_map<uint32_t, size_t> foodIndex_;
    FoodLedger ledger_;
    uint32_t nextFoodId_ = 1;
    std::vector<Scent> scents_;
    std::vector<int> scentOfCell_;
    std::vector<float> activity_;
    std::vector<int> foodCellStart_, foodCellItems_;
    int scentCols_ = 0, scentRows_ = 0;
    CurrentField current_;
    std::vector<Cloud> clouds_;
    std::vector<int> grounded_;  // creatures on the sand, rebuilt every step
    std::vector<Decor> decor_;
};

}  // namespace dt
