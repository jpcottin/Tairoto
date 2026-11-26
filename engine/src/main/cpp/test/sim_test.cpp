// Host tests for the simulation: run with scripts/host-tests.sh (or ./gradlew nativeHostTest).
#include <gtest/gtest.h>

#include <cmath>
#include <set>

#include "sim/world.h"

using namespace dt;

namespace {

World reef(int fish = 24, bool turtle = true, uint64_t seed = 7) {
    World w;
    w.setTank({220, 330, 110});
    w.setSeed(seed);
    Rng r(seed);
    for (int i = 0; i < fish; i++) {
        w.spawn(i + 1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), r.range(20, 27),
                r.next(), Vec3(r.range(60, 160), r.range(80, 250), r.range(20, 60)));
    }
    if (turtle)
        w.spawn(1000, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 110, 99,
                Vec3(110, 165, 60));
    return w;
}

bool finite(const Creature& c) {
    if (!std::isfinite(c.pos.x) || !std::isfinite(c.pos.y) || !std::isfinite(c.pos.z)) return false;
    if (!std::isfinite(c.heading) || !std::isfinite(c.speed)) return false;
    for (auto& j : c.joint)
        if (!std::isfinite(j.x) || !std::isfinite(j.y)) return false;
    return true;
}

}  // namespace

TEST(World, SpawnRefusesADuplicateId) {
    World w;
    w.setTank({200, 200, 100});
    EXPECT_TRUE(w.spawn(5, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 1, Vec3(50, 50, 30)));
    EXPECT_FALSE(w.spawn(5, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 1, Vec3(50, 50, 30)));
    EXPECT_EQ(1u, w.creatures().size());
}

TEST(World, RemoveTakesOnlyThatCreature) {
    World w = reef(5, true);
    EXPECT_TRUE(w.remove(3));
    EXPECT_FALSE(w.remove(3));
    EXPECT_EQ(nullptr, w.find(3));
    EXPECT_NE(nullptr, w.find(4));
    EXPECT_EQ(5u, w.creatures().size());
}

TEST(World, ASpawnOutsideTheTankIsPulledIn) {
    World w;
    w.setTank({100, 100, 50});
    w.spawn(1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 1, Vec3(-40, 500, 300));
    const Creature& c = w.creatures()[0];
    EXPECT_GT(c.pos.x, 0);
    EXPECT_LT(c.pos.y, 100);
    EXPECT_LT(c.pos.z, 50);
}

TEST(World, EveryoneStaysInsideTheTankForTenMinutes) {
    World w = reef();
    for (int s = 0; s < 600 * 60; s++) {
        w.step(1.0f / 60.0f);
        if (s % 600 == 0) {
            for (auto& c : w.creatures()) {
                ASSERT_TRUE(finite(c)) << "creature " << c.id << " at step " << s;
                ASSERT_GE(c.pos.x, 0) << c.id;
                ASSERT_LE(c.pos.x, w.tank().width) << c.id;
                ASSERT_GE(c.pos.y, 0) << c.id;
                ASSERT_LE(c.pos.y, w.tank().height) << c.id;
                ASSERT_GE(c.pos.z, 0) << c.id;
                ASSERT_LE(c.pos.z, w.tank().depth) << c.id;
            }
        }
    }
}

TEST(World, TheSpineKeepsItsLength) {
    World w = reef(10, false);
    for (int s = 0; s < 3000; s++) w.step(1.0f / 60.0f);
    for (auto& c : w.creatures()) {
        for (int i = 1; i < Creature::kJoints; i++) {
            EXPECT_NEAR(c.segLen, length(c.joint[i] - c.joint[i - 1]), c.segLen * 1e-3f) << c.id << " joint " << i;
        }
        EXPECT_NEAR(0.0f, length(c.joint[0] - c.pos.xy()), 1e-4f);
    }
}

TEST(World, ASwimmingBodyIsSmoothNotKinked) {
    World w = reef(16, false);
    for (int s = 0; s < 1800; s++) {
        w.step(1.0f / 60.0f);
        if (s % 120) continue;
        for (auto& c : w.creatures()) {
            // Neighbouring segments never fold back on each other...
            for (int i = 2; i < Creature::kJoints; i++) {
                Vec2 a = normalize(c.joint[i - 2] - c.joint[i - 1]);
                Vec2 b = normalize(c.joint[i - 1] - c.joint[i]);
                ASSERT_GT(dot(a, b), 0.7f) << "fish " << c.id << " kinked at joint " << i << " step " << s;
            }
            // ...and the body stays nearly its full length from snout to peduncle.
            float span = length(c.joint[0] - c.joint[Creature::kJoints - 1]);
            ASSERT_GT(span, c.segLen * (Creature::kJoints - 1) * 0.75f) << "fish " << c.id << " curled up";
        }
    }
}

TEST(World, TheBodyBendsIntoATurn) {
    World w;
    w.setTank({400, 400, 100});
    w.spawn(1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 25, 1, Vec3(200, 200, 40));
    auto& c = w.creaturesForTest()[0];
    for (int s = 0; s < 120; s++) w.step(1.0f / 60.0f);
    c.heading = wrapAngle(c.heading + 1.2f);  // a sudden turn: the head leads
    w.step(1.0f / 60.0f);
    Vec2 head = normalize(c.joint[0] - c.joint[1]);
    Vec2 tail = normalize(c.joint[Creature::kJoints - 2] - c.joint[Creature::kJoints - 1]);
    EXPECT_GT(std::acos(clamp(dot(head, tail), -1.0f, 1.0f)), 0.3f) << "head and tail still point the same way";
}

TEST(World, FishSchoolTogether) {
    World w = reef(24, false);
    for (int s = 0; s < 60 * 60; s++) w.step(1.0f / 60.0f);
    // Mean distance to the nearest neighbour stays within a few body lengths.
    float sum = 0;
    for (auto& a : w.creatures()) {
        float best = 1e9f;
        for (auto& b : w.creatures())
            if (a.id != b.id) best = std::fmin(best, length(a.pos.xy() - b.pos.xy()));
        sum += best;
    }
    float mean = sum / w.creatures().size();
    EXPECT_LT(mean, 3.0f * 27.0f);
    EXPECT_GT(mean, 0.4f * 20.0f) << "fish piled on top of each other";
}

TEST(World, FishMostlyFaceTheSameWay) {
    World w = reef(24, false);
    for (int s = 0; s < 45 * 60; s++) w.step(1.0f / 60.0f);
    Vec2 sum;
    for (auto& c : w.creatures()) sum += fromAngle(c.heading);
    float polarization = length(sum) / w.creatures().size();
    EXPECT_GT(polarization, 0.4f);
}

TEST(World, FishStayInTheirDepthBand) {
    World w = reef(24, false);
    for (int s = 0; s < 120 * 60; s++) w.step(1.0f / 60.0f);
    auto sp = SpeciesParams::greenChromis();
    for (auto& c : w.creatures()) {
        EXPECT_GT(c.pos.z, w.tank().depth * sp.depthMin * 0.6f) << c.id;
        EXPECT_LT(c.pos.z, w.tank().depth * sp.depthMax * 1.2f) << c.id;
    }
}

TEST(World, ATapScattersNearbyFish) {
    World w = reef(24, false);
    for (int s = 0; s < 600; s++) w.step(1.0f / 60.0f);
    Vec2 centre;
    for (auto& c : w.creatures()) centre += c.pos.xy();
    centre = centre / (float)w.creatures().size();
    auto meanDist = [&]() {
        float d = 0;
        for (auto& c : w.creatures()) d += length(c.pos.xy() - centre);
        return d / w.creatures().size();
    };
    float before = meanDist();
    w.startle(centre);
    for (int s = 0; s < 30; s++) w.step(1.0f / 60.0f);
    EXPECT_GT(meanDist(), before * 1.15f);
    for (auto& c : w.creatures()) EXPECT_GT(c.speed, 0.0f);
}

TEST(World, AStartleSpreadsAsAWave) {
    World w;
    w.setTank({400, 400, 100});
    w.spawn(1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 1, Vec3(200, 200, 40));
    w.spawn(2, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 2, Vec3(235, 200, 40));
    w.startle({200, 200});
    EXPECT_LT(w.find(1)->startleDelay, w.find(2)->startleDelay);
}

TEST(World, FishKeepClearOfTheTurtle) {
    World w = reef(24, true);
    int close = 0, samples = 0;
    for (int s = 0; s < 90 * 60; s++) {
        w.step(1.0f / 60.0f);
        if (s % 30) continue;
        const Creature* t = w.find(1000);
        for (auto& c : w.creatures()) {
            if (c.loco != Locomotion::Swim) continue;
            samples++;
            // Fish may pass over or under the turtle, as they would: only count a collision.
            float dz = c.pos.z - t->pos.z;
            if (std::sqrt(length2(c.pos.xy() - t->pos.xy()) + dz * dz) < t->length * 0.25f) close++;
        }
    }
    EXPECT_LT((float)close / samples, 0.01f);
}

TEST(World, TheSameSeedGivesTheSameTank) {
    World a = reef(12, true, 3), b = reef(12, true, 3);
    for (int s = 0; s < 1200; s++) {
        a.step(1.0f / 60.0f);
        b.step(1.0f / 60.0f);
    }
    for (size_t i = 0; i < a.creatures().size(); i++) {
        EXPECT_EQ(a.creatures()[i].pos.x, b.creatures()[i].pos.x);
        EXPECT_EQ(a.creatures()[i].pos.z, b.creatures()[i].pos.z);
    }
}

TEST(World, StepsAreFixedWhateverTheFrameRate) {
    World a = reef(6, true, 5), b = reef(6, true, 5);
    for (int s = 0; s < 240; s++) a.step(1.0f / 120.0f);
    for (int s = 0; s < 120; s++) b.step(1.0f / 60.0f);
    EXPECT_FLOAT_EQ(a.time(), b.time());
    for (size_t i = 0; i < a.creatures().size(); i++) EXPECT_FLOAT_EQ(a.creatures()[i].pos.x, b.creatures()[i].pos.x);
}

TEST(World, ALongStallDoesNotFastForward) {
    World w = reef(4, false);
    w.step(5.0f);  // the app came back from the background
    EXPECT_LE(w.time(), World::kStep * World::kMaxSubsteps + 1e-5f);
}

TEST(World, ANonsenseStepIsIgnored) {
    World w = reef(4, false);
    w.step(-1.0f);
    w.step(NAN);
    w.step(0.0f);
    EXPECT_EQ(0.0f, w.time());
}

// ------------------------------------------------------------------ turtle

namespace {
World turtleAlone(uint64_t seed) {
    World w;
    w.setTank({300, 420, 110});
    w.setSeed(seed);
    w.spawn(1, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 110, (uint32_t)seed,
            Vec3(150, 210, 60));
    return w;
}
}  // namespace

TEST(Turtle, RestsOnTheSandAndBreathesAtTheSurface) {
    bool rested = false, surfaced = false;
    for (uint64_t seed = 1; seed <= 4 && !(rested && surfaced); seed++) {
        World w = turtleAlone(seed);
        for (int s = 0; s < 8 * 60 * 60; s++) {  // eight minutes
            w.step(1.0f / 60.0f);
            const Creature& t = w.creatures()[0];
            if (t.behavior == Behavior::Rest && t.rest > 0.9f) {
                rested = true;
                EXPECT_GT(t.pos.z, w.tank().depth - t.length * 0.15f) << "resting off the sand";
                EXPECT_LT(t.speed, 2.0f) << "resting but moving";
            }
            if (t.pos.z < t.length * 0.07f) surfaced = true;
        }
    }
    EXPECT_TRUE(rested);
    EXPECT_TRUE(surfaced);
}

TEST(Turtle, MovesInSurgesWithItsStrokes) {
    World w = turtleAlone(2);
    auto& t = w.creaturesForTest()[0];
    t.behavior = Behavior::Cruise;
    t.stateTimer = 1000;
    float lo = 1e9f, hi = 0;
    for (int s = 0; s < 30 * 60; s++) {
        w.step(1.0f / 60.0f);
        if (s > 10 * 60) {
            lo = std::fmin(lo, t.speed);
            hi = std::fmax(hi, t.speed);
        }
    }
    EXPECT_GT(hi, lo * 1.15f) << "no surge";
    EXPECT_GT(hi, t.sp.cruiseBl * t.length * 0.6f);
}

TEST(Turtle, ATapCloseByMakesItFleeEvenFromRest) {
    World w = turtleAlone(1);
    auto& t = w.creaturesForTest()[0];
    t.behavior = Behavior::Rest;
    t.rest = 1;
    t.stateTimer = 100;
    w.step(1.0f / 60.0f);
    w.startle(t.pos.xy() + Vec2(10, 0));
    for (int s = 0; s < 120; s++) w.step(1.0f / 60.0f);
    EXPECT_EQ(Behavior::Flee, t.behavior);
    EXPECT_GT(t.speed, 5.0f);
}

TEST(Turtle, ATapFarAwayLeavesItAlone) {
    World w = turtleAlone(1);
    auto& t = w.creaturesForTest()[0];
    w.startle(t.pos.xy() + Vec2(t.length * 1.5f, 0));
    for (int s = 0; s < 60; s++) w.step(1.0f / 60.0f);
    EXPECT_NE(Behavior::Flee, t.behavior);
}

TEST(Grid, FindsEveryNeighbourInRange) {
    World w = reef(40, true, 11);
    for (int s = 0; s < 300; s++) w.step(1.0f / 60.0f);
    SpatialGrid g;
    g.rebuild(w.creatures(), w.tank(), 30);
    const auto& cs = w.creatures();
    for (size_t i = 0; i < cs.size(); i++) {
        std::set<int> found;
        g.query(cs[i].pos.xy(), 50, [&](int k) { found.insert(k); });
        for (size_t k = 0; k < cs.size(); k++)
            if (length(cs[k].pos.xy() - cs[i].pos.xy()) <= 50) {
                EXPECT_TRUE(found.count((int)k)) << i << " misses " << k;
            }
    }
}

TEST(Math, AnglesWrapAndApproachTheShortWay) {
    EXPECT_NEAR(0.0f, wrapAngle(kTau), 1e-5f);
    EXPECT_NEAR(-kPi + 0.1f, wrapAngle(kPi + 0.1f), 1e-5f);
    EXPECT_NEAR(kPi - 0.2f + 0.1f, approachAngle(kPi - 0.2f, -kPi + 0.2f, 0.1f), 1e-5f);
    EXPECT_NEAR(1.0f, approach(0.5f, 1.0f, 2.0f), 1e-6f);
    EXPECT_NEAR(0.7f, approach(0.5f, 1.0f, 0.2f), 1e-6f);
}

TEST(Math, RngIsDeterministicAndUniform) {
    Rng a(42), b(42);
    double sum = 0;
    for (int i = 0; i < 10000; i++) {
        float x = a.uniform();
        EXPECT_EQ(x, b.uniform());
        EXPECT_GE(x, 0.0f);
        EXPECT_LT(x, 1.0f);
        sum += x;
    }
    EXPECT_NEAR(0.5, sum / 10000, 0.02);
}

TEST(Math, NoiseIsSmoothAndBounded) {
    float prev = noise1(0, 3);
    for (int i = 1; i < 1000; i++) {
        float v = noise1(i * 0.01f, 3);
        EXPECT_LE(std::fabs(v), 1.0f);
        EXPECT_LT(std::fabs(v - prev), 0.1f);
        prev = v;
    }
}
