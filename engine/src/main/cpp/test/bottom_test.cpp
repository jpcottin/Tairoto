// Host tests for the bottom dwellers: the eagle ray, the octopus and the hermit crab.
#include <gtest/gtest.h>

#include <cmath>

#include "sim/world.h"

using namespace dt;

namespace {

constexpr float kDt = 1.0f / 60.0f;

World lagoon(uint64_t seed = 11) {
    World w;
    w.setTank({200, 300, 55});
    w.setSeed(seed);
    return w;
}

void run(World& w, float seconds) {
    for (int i = 0, n = (int)(seconds / kDt); i < n; i++) w.step(kDt);
}

const Creature& only(const World& w, int64_t id) {
    const Creature* c = w.find(id);
    EXPECT_NE(nullptr, c);
    return *c;
}

void spawnRay(World& w, int64_t id = 1, Vec3 at = Vec3(100, 150, 25), uint32_t seed = 5) {
    w.spawn(id, Visual::EagleRay, Locomotion::Glide, SpeciesParams::eagleRay(), 32, seed, at);
}
void spawnOctopus(World& w, int64_t id = 2, Vec3 at = Vec3(100, 150, 55), uint32_t seed = 6) {
    w.spawn(id, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 34, seed, at);
}
void spawnCrab(World& w, int64_t id = 3, Vec3 at = Vec3(100, 150, 55), uint32_t seed = 7) {
    w.spawn(id, Visual::HermitCrab, Locomotion::Walk, SpeciesParams::hermitCrab(), 15, seed, at);
}

bool finite(Vec2 v) { return std::isfinite(v.x) && std::isfinite(v.y); }

}  // namespace

// ---------------------------------------------------------------- ray

TEST(Ray, GlidesBuriesItselfInTheSandAndComesBackOut) {
    World w = lagoon();
    spawnRay(w);
    bool buried = false, outAgain = false;
    float lowest = 0;
    for (int s = 0; s < 60 * 400; s++) {
        w.step(kDt);
        const Creature& c = only(w, 1);
        ASSERT_TRUE(std::isfinite(c.pos.x) && std::isfinite(c.pos.z) && std::isfinite(c.cover));
        if (c.cover > 0.98f) {
            buried = true;
            lowest = std::max(lowest, c.pos.z);
        }
        if (buried && c.behavior == Behavior::Cruise && c.cover < 0.02f) {
            outAgain = true;
            break;
        }
    }
    EXPECT_TRUE(buried) << "the ray never hid in the sand";
    EXPECT_GT(lowest, 55 - 2.0f) << "buried, it lies on the sand";
    EXPECT_TRUE(outAgain) << "the ray never came back out";
}

TEST(Ray, BuryingThrowsUpSand) {
    World w = lagoon();
    spawnRay(w);
    int maxClouds = 0;
    for (int s = 0; s < 60 * 400 && maxClouds < 5; s++) {
        w.step(kDt);
        if (only(w, 1).behavior == Behavior::Hide) maxClouds = std::max(maxClouds, (int)w.clouds().size());
    }
    EXPECT_GE(maxClouds, 5);
    for (auto& k : w.clouds()) EXPECT_EQ(CloudKind::Sand, k.kind);
}

TEST(Ray, ATapBurstsItOutOfTheSand) {
    World w = lagoon();
    spawnRay(w);
    auto& c = w.creaturesForTest()[0];
    c.behavior = Behavior::Hide;
    c.cover = 1;
    c.pos.z = 54;
    c.stateTimer = 100;
    w.step(kDt);
    size_t before = w.clouds().size();
    w.startle(c.pos.xy());
    run(w, 0.3f);
    const Creature& r = only(w, 1);
    EXPECT_EQ(Behavior::Flee, r.behavior);
    EXPECT_GE(w.clouds().size(), before + 10) << "a burst of sand";
    run(w, 1.5f);
    EXPECT_LT(only(w, 1).cover, 0.05f) << "the sand slides off";
    EXPECT_LT(only(w, 1).pos.z, 54.0f) << "and it lifts off";
}

TEST(Ray, TheTailKeepsItsLength) {
    World w = lagoon();
    spawnRay(w);
    w.startle({90, 140});
    for (int s = 0; s < 60 * 60; s++) {
        w.step(kDt);
        const Creature& c = only(w, 1);
        for (int i = 1; i < Creature::kJoints; i++)
            ASSERT_NEAR(c.segLen, length(c.joint[i] - c.joint[i - 1]), c.segLen * 0.02f);
    }
}

TEST(Ray, StaysInsideTheLagoonWithItsWings) {
    World w = lagoon(3);
    spawnRay(w, 1, Vec3(30, 30, 20));
    const float half = 32 * SpeciesParams::eagleRay().bodyWidth * 0.45f;
    for (int s = 0; s < 60 * 300; s++) {
        w.step(kDt);
        const Creature& c = only(w, 1);
        ASSERT_GE(c.pos.x, half - 0.01f);
        ASSERT_LE(c.pos.x, 200 - half + 0.01f);
        ASSERT_GE(c.pos.z, 1.0f);
        ASSERT_LE(c.pos.z, 54.0f + 1e-3f);
    }
}

// ---------------------------------------------------------------- octopus

TEST(Octopus, FrightMakesItInkAndJetAway) {
    World w = lagoon();
    spawnOctopus(w);
    run(w, 2);
    Vec2 tap = only(w, 2).pos.xy() + Vec2(4, 0);
    w.startle(tap);
    run(w, 0.2f);
    const Creature& c = only(w, 2);
    EXPECT_EQ(Behavior::Flee, c.behavior);
    EXPECT_GT(c.alarm, 0.8f) << "a dark flash";
    EXPECT_GE(w.inkClouds(), 4);
    run(w, 1.5f);
    EXPECT_GT(length(only(w, 2).pos.xy() - tap), 25.0f) << "jetted away";
    EXPECT_LT(only(w, 2).pos.z, 50.0f) << "off the sand";
}

TEST(Octopus, InkIsNotEndless) {
    World w = lagoon();
    spawnOctopus(w);
    run(w, 1);
    w.startle(only(w, 2).pos.xy());
    run(w, 0.2f);
    int first = w.inkClouds();
    run(w, 4);
    w.startle(only(w, 2).pos.xy());
    run(w, 0.2f);
    EXPECT_LE(w.inkClouds(), first) << "no second cloud so soon";
    EXPECT_EQ(Behavior::Flee, only(w, 2).behavior) << "but it still flees";
}

TEST(Octopus, LandsAndCamouflagesAfterJetting) {
    World w = lagoon();
    spawnOctopus(w);
    w.startle(only(w, 2).pos.xy());
    run(w, 14);
    const Creature& c = only(w, 2);
    EXPECT_EQ(Behavior::Hide, c.behavior);
    EXPECT_GT(c.pos.z, 52.0f);
    EXPECT_GT(c.cover, 0.9f) << "the colours of the sand";
    EXPECT_LT(c.alarm, 0.05f);
}

TEST(Octopus, ArmsKeepTheirLengthAndStayAttached) {
    World w = lagoon(4);
    spawnOctopus(w);
    for (int s = 0; s < 60 * 120; s++) {
        w.step(kDt);
        if (s == 60 * 30 || s == 60 * 80) w.startle(only(w, 2).pos.xy());
        const Creature& c = only(w, 2);
        for (int k = 0; k < Creature::kArms; k++) {
            ASSERT_LT(length(c.arm[k][0] - c.pos.xy()), 0.07f * c.length) << "arm " << k << " at the crown";
            for (int j = 1; j < Creature::kArmJoints; j++) {
                ASSERT_TRUE(finite(c.arm[k][j]));
                float seg = length(c.arm[k][j] - c.arm[k][j - 1]);
                ASSERT_LT(seg, c.armLen[k] * 1.25f / (Creature::kArmJoints - 1)) << "arm " << k;
            }
        }
    }
}

TEST(Octopus, ReachesOutAnArmForFoodOnTheSand) {
    World w = lagoon();
    spawnOctopus(w);
    auto& c = w.creaturesForTest()[0];
    c.hunger = 0.9f;
    Vec2 at = c.pos.xy() + Vec2(16, 0);
    w.dropFood(at, FoodKind::Pellet, 1);
    bool grabbed = false;
    for (int s = 0; s < 60 * 40 && only(w, 2).eatenMg <= 0; s++) {
        w.step(kDt);
        grabbed = grabbed || only(w, 2).grabArm >= 0;
    }
    EXPECT_TRUE(grabbed) << "an arm went for it";
    EXPECT_GT(only(w, 2).eatenMg, 0.0f);
}

TEST(Octopus, ArmsMoveSmoothlyWithoutSnapping) {
    // Walking, resting, hiding, jetting off and landing: no joint of any arm
    // ever jerks. The second difference of each position over three steps
    // (its acceleration) stays small, the way a soft arm moves in water.
    World w = lagoon(12);
    spawnOctopus(w);
    auto& o = w.creaturesForTest()[0];
    o.behavior = Behavior::Cruise;
    o.target = Vec3(100, 260, 54);
    o.stateTimer = 100;
    Vec2 p0[Creature::kArms][Creature::kArmJoints], p1[Creature::kArms][Creature::kArmJoints];
    float worst = 0;
    Vec2 start = o.pos.xy();
    for (int s = 0; s < 60 * 40; s++) {
        if (s == 60 * 25) w.startle(only(w, 2).pos.xy());
        w.step(kDt);
        const Creature& c = only(w, 2);
        if (s >= 2 && c.behavior != Behavior::Flee) {
            for (int k = 0; k < Creature::kArms; k++)
                for (int j = 1; j < Creature::kArmJoints; j++) {
                    // Relative to the crown, so the body's own motion does not count.
                    Vec2 a = p0[k][j], b = p1[k][j], d = c.arm[k][j] - c.pos.xy();
                    worst = std::max(worst, length(d - b * 2.0f + a));
                }
        }
        for (int k = 0; k < Creature::kArms; k++)
            for (int j = 0; j < Creature::kArmJoints; j++) {
                p0[k][j] = p1[k][j];
                p1[k][j] = c.arm[k][j] - c.pos.xy();
            }
        if (s == 60 * 20) {
            EXPECT_GT(length(c.pos.xy() - start), 15.0f) << "it walked";
        }
    }
    EXPECT_LT(worst, 0.25f) << "mm per frame per frame";
}

TEST(Octopus, WalkingArmsSweepInTurn) {
    // While it walks, its arms reach forward and pull back, neighbours out of step.
    World w = lagoon(12);
    spawnOctopus(w);
    auto& o = w.creaturesForTest()[0];
    o.behavior = Behavior::Cruise;
    o.target = Vec3(100, 280, 54);
    o.stateTimer = 100;
    run(w, 2);
    float lo[2] = {1e9f, 1e9f}, hi[2] = {-1e9f, -1e9f};
    for (int s = 0; s < 60 * 8; s++) {
        w.step(kDt);
        const Creature& c = only(w, 2);
        Vec2 fwd = fromAngle(c.heading);
        float f0 = dot(c.arm[1][5] - c.pos.xy(), fwd), f1 = dot(c.arm[2][5] - c.pos.xy(), fwd);
        lo[0] = std::min(lo[0], f0); hi[0] = std::max(hi[0], f0);
        lo[1] = std::min(lo[1], f1); hi[1] = std::max(hi[1], f1);
    }
    EXPECT_GT(hi[0] - lo[0], 3.0f) << "arm 1 sweeps";
    EXPECT_GT(hi[1] - lo[1], 3.0f) << "arm 2 sweeps";
}

TEST(Octopus, ArmsStreamBehindWhenItJets) {
    World w = lagoon();
    spawnOctopus(w);
    run(w, 2);
    w.startle(only(w, 2).pos.xy() + Vec2(0, -5));
    run(w, 1.2f);
    const Creature& c = only(w, 2);
    ASSERT_EQ(Behavior::Flee, c.behavior);
    Vec2 fwd = fromAngle(c.heading);
    int behind = 0;
    for (int k = 0; k < Creature::kArms; k++) behind += dot(c.arm[k][Creature::kArmJoints - 1] - c.pos.xy(), fwd) < 0;
    EXPECT_GE(behind, 7) << "the arms trail behind the mantle";
}

TEST(Octopus, ArmsLyingOnTheSandStayPut) {
    // Hiding, it barely moves: the arms lie still on the sand, only the tips stirring.
    World w = lagoon();
    spawnOctopus(w);
    auto& o = w.creaturesForTest()[0];
    o.behavior = Behavior::Hide;
    o.stateTimer = 100;
    run(w, 5);
    Vec2 mid[Creature::kArms];
    for (int k = 0; k < Creature::kArms; k++) mid[k] = only(w, 2).arm[k][4];
    run(w, 1);
    float moved = 0;
    for (int k = 0; k < Creature::kArms; k++) moved += length(only(w, 2).arm[k][4] - mid[k]);
    EXPECT_LT(moved / Creature::kArms, 1.0f) << "mm per second, on average";
}

// ---------------------------------------------------------------- hermit crab

TEST(Crab, WalksOnTheSandOnly) {
    World w = lagoon();
    spawnCrab(w);
    Vec2 start = only(w, 3).pos.xy();
    float travelled = 0;
    Vec2 last = start;
    for (int s = 0; s < 60 * 120; s++) {
        w.step(kDt);
        const Creature& c = only(w, 3);
        ASSERT_NEAR(55 - 0.2f * 15, c.pos.z, 0.01f);
        travelled += length(c.pos.xy() - last);
        last = c.pos.xy();
    }
    EXPECT_GT(travelled, 30.0f) << "it gets around";
}

TEST(Crab, PlantedFeetDoNotSlide) {
    World w = lagoon(9);
    spawnCrab(w);
    Vec2 prev[Creature::kLegs];
    float prevSwing[Creature::kLegs];
    for (int s = 0; s < 60 * 60; s++) {
        const Creature& c = only(w, 3);
        for (int l = 0; l < Creature::kLegs; l++) {
            prev[l] = c.foot[l];
            prevSwing[l] = c.swing[l];
        }
        float coverBefore = c.cover;
        w.step(kDt);
        const Creature& n = only(w, 3);
        if (coverBefore > 0 || n.cover > 0) continue;
        for (int l = 0; l < Creature::kLegs; l++)
            if (prevSwing[l] < 0 && n.swing[l] < 0) {
                ASSERT_LT(length(n.foot[l] - prev[l]), 1e-4f) << "leg " << l;
            }
    }
}

TEST(Crab, AlternatesItsLegs) {
    World w = lagoon(9);
    spawnCrab(w);
    int steps = 0;
    for (int s = 0; s < 60 * 60; s++) {
        w.step(kDt);
        const Creature& c = only(w, 3);
        bool group0 = c.swing[0] >= 0 || c.swing[3] >= 0;
        bool group1 = c.swing[1] >= 0 || c.swing[2] >= 0;
        ASSERT_FALSE(group0 && group1) << "both groups in the air at once";
        steps += group0 || group1;
    }
    EXPECT_GT(steps, 100) << "it took steps";
}

TEST(Crab, ATapSendsItIntoItsShellAndItComesBackOut) {
    World w = lagoon();
    spawnCrab(w);
    run(w, 3);
    w.startle(only(w, 3).pos.xy() + Vec2(10, 0));
    run(w, 0.25f);
    const Creature& c = only(w, 3);
    EXPECT_EQ(Behavior::Hide, c.behavior);
    EXPECT_GT(c.cover, 0.95f) << "in its shell at once";
    Vec2 at = c.pos.xy();
    run(w, 2);
    EXPECT_LT(length(only(w, 3).pos.xy() - at), 0.01f) << "and still";
    bool peeked = false;
    for (int s = 0; s < 60 * 15 && only(w, 3).cover > 0.01f; s++) {
        w.step(kDt);
        float cv = only(w, 3).cover;
        peeked = peeked || (cv > 0.3f && cv < 0.7f && only(w, 3).behavior == Behavior::Emerge);
    }
    EXPECT_TRUE(peeked) << "eyes out first";
    EXPECT_LT(only(w, 3).cover, 0.01f) << "out again";
}

TEST(Crab, HidesFromTheOctopusButNotFromChromis) {
    World w = lagoon();
    spawnCrab(w);
    for (int i = 0; i < 6; i++)
        w.spawn(100 + i, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 15, 40u + (uint32_t)i,
                Vec3(100 + (float)i, 150, 50));
    run(w, 2);
    EXPECT_NE(Behavior::Hide, only(w, 3).behavior) << "small fish are no danger";
    Vec2 at = only(w, 3).pos.xy();
    spawnOctopus(w, 2, Vec3(at.x + 12, at.y, 55));
    w.creaturesForTest().back().cover = 0;
    run(w, 0.3f);
    EXPECT_EQ(Behavior::Hide, only(w, 3).behavior);
}

// ---------------------------------------------------------------- clouds and the lagoon

TEST(Clouds, GrowFadeAndGo) {
    World w = lagoon();
    ASSERT_TRUE(w.puff(CloudKind::Ink, Vec3(50, 50, 30), Vec2(5, 0), 3, 0.9f, 4));
    run(w, 1);
    ASSERT_EQ(1u, w.clouds().size());
    EXPECT_GT(w.clouds()[0].radius, 3.0f);
    EXPECT_GT(w.clouds()[0].pos.x, 50.0f);
    EXPECT_LT(w.clouds()[0].fade(), 1.0f);
    run(w, 3.5f);
    EXPECT_TRUE(w.clouds().empty());
}

TEST(Clouds, SandSettlesAndTheCountIsCapped) {
    World w = lagoon();
    for (int i = 0; i < CloudConstants::kMax + 50; i++) w.puff(CloudKind::Sand, Vec3(100, 100, 40), Vec2(), 2, 0.5f, 30);
    EXPECT_EQ((size_t)CloudConstants::kMax, w.clouds().size());
    run(w, 5);
    for (auto& k : w.clouds()) EXPECT_GT(k.pos.z, 40.0f);
}

TEST(Lagoon, TheWholeLagoonIsDeterministic) {
    auto make = [] {
        World w = lagoon(21);
        spawnRay(w);
        spawnOctopus(w, 2, Vec3(60, 80, 55));
        spawnCrab(w, 3, Vec3(140, 220, 55));
        w.spawn(4, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 54, 9, Vec3(100, 100, 30));
        w.dropFood({100, 200}, FoodKind::Flake, 12);
        return w;
    };
    World a = make(), b = make();
    for (int s = 0; s < 60 * 90; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s == 600) {
            a.startle({60, 80});
            b.startle({60, 80});
        }
    }
    for (size_t i = 0; i < a.creatures().size(); i++) {
        EXPECT_EQ(a.creatures()[i].pos.x, b.creatures()[i].pos.x);
        EXPECT_EQ(a.creatures()[i].pos.y, b.creatures()[i].pos.y);
        EXPECT_EQ(a.creatures()[i].cover, b.creatures()[i].cover);
    }
    EXPECT_EQ(a.clouds().size(), b.clouds().size());
}

TEST(Lagoon, FoodIsAccountedForWithEveryoneEating) {
    World w = lagoon(8);
    spawnRay(w);
    spawnOctopus(w);
    spawnCrab(w, 3, Vec3(80, 120, 55));
    spawnCrab(w, 4, Vec3(120, 180, 55), 17);
    for (auto& c : w.creaturesForTest()) c.hunger = 0.9f;
    for (int i = 0; i < 6; i++) w.dropFood({90.0f + (float)i * 5, 150}, i % 2 ? FoodKind::Pellet : FoodKind::Flake, 3);
    run(w, 120);
    const FoodLedger& l = w.ledger();
    EXPECT_NEAR(l.dropped, l.eaten + l.decomposed + w.foodMass(), 1e-3);
    float eaten = 0;
    for (auto& c : w.creatures()) eaten += c.eatenMg;
    EXPECT_GT(eaten, 1.0f) << "the bottom dwellers found the food";
}

TEST(Lagoon, SwimmersPassOverWhatLiesOnTheSandAndAreDrawnAboveIt) {
    // A ray skimming the sand, chromis feeding low and a turtle settling, over a
    // field of crabs and an octopus: none of them sinks into a bottom dweller,
    // so the painter's order by tops is always right.
    World w = lagoon(31);
    int64_t id = 10;
    for (int x = 0; x < 4; x++)
        for (int y = 0; y < 6; y++)
            spawnCrab(w, id++, Vec3(40.0f + (float)x * 40, 50.0f + (float)y * 40, 55), (uint32_t)(x * 7 + y));
    spawnOctopus(w, 2, Vec3(100, 150, 55));
    spawnRay(w, 1, Vec3(100, 150, 45));
    w.spawn(3, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 54, 9, Vec3(80, 200, 40));
    for (int i = 0; i < 12; i++)
        w.spawn(100 + i, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 15, 40u + (uint32_t)i,
                Vec3(60.0f + (float)i * 6, 150, 48));
    for (auto& c : w.creaturesForTest()) {
        if (c.loco == Locomotion::Glide) {
            c.behavior = Behavior::Cruise;
            c.stateTimer = 1000;
            c.target = Vec3(160, 250, 54);  // as low as it can go
        }
    }
    int overlaps = 0;
    for (int s = 0; s < 60 * 60; s++) {
        w.step(kDt);
        if (s < 60) continue;  // let them settle out of their spawn
        const auto& cs = w.creatures();
        for (const Creature& sw : cs) {
            if (sw.loco == Locomotion::Walk || sw.loco == Locomotion::Crawl) continue;
            if (sw.loco == Locomotion::Glide && sw.behavior == Behavior::Hide) continue;
            if (sw.loco == Locomotion::Paddle && sw.rest > 0.5f) continue;
            for (const Creature& o : cs) {
                if (o.loco != Locomotion::Walk) continue;
                float r = sw.loco == Locomotion::Glide ? 0.4f * sw.length * sw.sp.bodyWidth : 0.3f * sw.length;
                if (length(sw.pos.xy() - o.pos.xy()) > r + 0.4f * o.length) continue;
                overlaps++;
                ASSERT_LT(World::topOf(sw), World::topOf(o)) << "step " << s << ": " << (int)sw.loco << " under a crab";
                ASSERT_LT(sw.pos.z, World::topOf(o)) << "step " << s << ": " << (int)sw.loco << " inside a crab";
            }
        }
    }
    EXPECT_GT(overlaps, 50) << "they did pass over the crabs";
}
