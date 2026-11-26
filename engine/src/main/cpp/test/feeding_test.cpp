// Host tests for food, feeding and the water current.
#include <gtest/gtest.h>

#include <cmath>

#include "sim/world.h"

using namespace dt;

namespace {
World tank(uint64_t seed = 5) {
    World w;
    w.setTank({200, 300, 55});
    w.setSeed(seed);
    return w;
}

void school(World& w, int n, Vec2 at, float hunger, uint64_t seed = 9) {
    Rng r(seed);
    for (int i = 0; i < n; i++) {
        w.spawn(100 + i, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), r.range(13.5f, 17.5f),
                r.next(), Vec3(at.x + r.range(-15, 15), at.y + r.range(-15, 15), r.range(12, 30)));
    }
    for (auto& c : w.creaturesForTest()) c.hunger = hunger;
}

void run(World& w, float seconds) {
    int n = (int)(seconds * 60.0f + 0.5f);
    for (int i = 0; i < n; i++) w.step(1.0f / 60.0f);
}

double inWater(const World& w) { return w.foodMass(); }
}  // namespace

TEST(Food, FlakesFloatThenSinkThenRestOnTheSand) {
    World w = tank();
    ASSERT_EQ(5, w.dropFood({100, 150}, FoodKind::Flake, 5));
    for (auto& f : w.food()) {
        EXPECT_EQ(FoodState::Floating, f.state);
        EXPECT_EQ(0.0f, f.pos.z);
    }
    run(w, 1.0f);
    for (auto& f : w.food()) EXPECT_EQ(FoodState::Floating, f.state) << "flakes float a few seconds";
    run(w, 40.0f);
    for (auto& f : w.food()) {
        EXPECT_EQ(FoodState::Resting, f.state);
        EXPECT_NEAR(w.tank().depth, f.pos.z, 0.5f);
    }
}

TEST(Food, PelletsSinkFasterThanFlakes) {
    World w = tank();
    w.dropFood({60, 150}, FoodKind::Pellet, 1);
    w.dropFood({140, 150}, FoodKind::Flake, 1);
    run(w, 4.0f);
    const Food& pellet = w.food()[0];
    const Food& flake = w.food()[1];
    EXPECT_GT(pellet.pos.z, flake.pos.z + 5.0f);
}

TEST(Food, FoodOnTheSandDecomposesAway) {
    World w = tank();
    w.dropFood({100, 150}, FoodKind::Flake, 10);
    run(w, 600.0f);
    EXPECT_TRUE(w.food().empty()) << w.food().size() << " pieces left after ten minutes";
    EXPECT_NEAR(w.ledger().dropped, w.ledger().decomposed, 1e-3);
}

TEST(Food, ItGoesOffBeforeItGoes) {
    World w = tank();
    w.dropFood({100, 150}, FoodKind::Pellet, 1);
    run(w, 60.0f);
    ASSERT_FALSE(w.food().empty());
    EXPECT_LT(w.food()[0].freshness, 0.8f);
}

TEST(Food, TheCapIsRespected) {
    World w = tank();
    int made = 0;
    for (int i = 0; i < 400; i++) made += w.dropFood({100, 150}, FoodKind::Flake, 5);
    EXPECT_EQ(FoodConstants::kMaxParticles, made);
    EXPECT_EQ(0, w.dropFood({100, 150}, FoodKind::Flake, 1));
}

TEST(Feeding, HungryFishFindTheFoodAndEatIt) {
    World w = tank();
    school(w, 12, {100, 230}, 0.9f);
    run(w, 1.0f);
    w.dropFood({100, 120}, FoodKind::Flake, 12);
    run(w, 25.0f);
    EXPECT_GT(w.ledger().eaten, 5.0) << "eaten " << w.ledger().eaten << " mg";
    float hunger = 0;
    for (auto& c : w.creatures()) hunger += c.hunger;
    EXPECT_LT(hunger / w.creatures().size(), 0.85f);
}

TEST(Feeding, FullFishIgnoreFood) {
    World w = tank();
    school(w, 12, {100, 230}, 0.0f);
    for (auto& c : w.creaturesForTest()) c.sp.hungerPerS = 0;
    w.dropFood({100, 120}, FoodKind::Flake, 12);
    run(w, 20.0f);
    EXPECT_EQ(0.0, w.ledger().eaten);
}

TEST(Feeding, MoreFoodIsSmelledFromFurtherAway) {
    Scent small, big;
    small.waterMass = 1.6f;
    big.waterMass = 30.0f;
    EXPECT_GT(Scent::radiusFor(big.strength(false)), 3.0f * Scent::radiusFor(small.strength(false)));
}

TEST(Feeding, FeedersDrawMoreFeeders) {
    Scent quiet, frenzy;
    quiet.waterMass = frenzy.waterMass = 4.0f;
    frenzy.eaters = 8;
    frenzy.activity = 5.0f;
    EXPECT_GT(frenzy.strength(false), 3.0f * quiet.strength(false));
}

TEST(Feeding, OnlyBottomFeedersSmellFoodOnTheSand) {
    Scent s;
    s.sandMass = 5.0f;
    EXPECT_EQ(0.0f, s.strength(false));
    EXPECT_GT(s.strength(true), 0.0f);
}

TEST(Feeding, AFishFarAwayComesForABigPileButNotForOneFlake) {
    auto attempt = [](int flakes) {
        World w = tank(7);
        school(w, 1, {100, 260}, 0.6f);
        auto& c = w.creaturesForTest()[0];
        c.sp.schooling = 0;
        c.sp.hungerPerS = 0;
        w.dropFood({100, 140}, FoodKind::Flake, flakes);
        // 120 mm away: watch whether it heads for the food at all in the first seconds.
        bool chased = false;
        for (int i = 0; i < 4 * 60; i++) {
            w.step(1.0f / 60.0f);
            if (w.creatures()[0].foodTarget) chased = true;
        }
        return chased;
    };
    EXPECT_FALSE(attempt(1));
    EXPECT_TRUE(attempt(30));
}

TEST(Feeding, MessyBitesMakeCrumbs) {
    World w = tank();
    school(w, 10, {100, 160}, 1.0f);
    w.dropFood({100, 150}, FoodKind::Pellet, 4);
    bool crumbs = false;
    for (int i = 0; i < 20 * 60 && !crumbs; i++) {
        w.step(1.0f / 60.0f);
        for (auto& f : w.food())
            if (f.kind == FoodKind::Crumb) crumbs = true;
    }
    EXPECT_TRUE(crumbs);
}

TEST(Feeding, CrumbsAreSmallerThanWhatTheyCameFrom) {
    World w = tank();
    school(w, 10, {100, 160}, 1.0f);
    w.dropFood({100, 150}, FoodKind::Pellet, 4);
    for (int i = 0; i < 20 * 60; i++) {
        w.step(1.0f / 60.0f);
        for (auto& f : w.food())
            if (f.kind == FoodKind::Crumb) {
                ASSERT_LT(f.mass, FoodConstants::kPelletMg * 1.15f * 0.6f);
            }
    }
}

TEST(Feeding, TheTurtleEatsPelletsFromTheSand) {
    World w = tank(3);
    w.spawn(1, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 55, 4, Vec3(100, 220, 30));
    w.creaturesForTest()[0].hunger = 0.9f;
    w.dropFood({100, 120}, FoodKind::Pellet, 6);
    run(w, 90.0f);
    EXPECT_GT(w.creatures()[0].eatenMg, 3.0f);
}

TEST(Feeding, EveryMilligramIsAccountedFor) {
    // The ledger and the food in the water always add up, through drops,
    // bites, crumbs, dust and decomposition.
    World w = tank(11);
    school(w, 24, {100, 150}, 0.8f);
    w.spawn(1, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 55, 4, Vec3(100, 150, 30));
    Rng r(2);
    for (int s = 0; s < 5 * 60 * 60; s++) {
        if (s % 300 == 0) w.dropFood({r.range(20, 180), r.range(20, 280)}, r.chance(0.3f) ? FoodKind::Pellet : FoodKind::Flake, 1 + (int)(r.uniform() * 8));
        if (s % 1000 == 0) w.startle({r.range(20, 180), r.range(20, 280)});
        w.step(1.0f / 60.0f);
        if (s % 120 == 0) {
            const FoodLedger& l = w.ledger();
            double total = l.eaten + l.decomposed + inWater(w);
            ASSERT_NEAR(l.dropped, total, 1e-3 * std::max(1.0, l.dropped)) << "at step " << s;
            for (auto& f : w.food()) {
                ASSERT_GT(f.mass, 0.0f);
                ASSERT_TRUE(std::isfinite(f.pos.x) && std::isfinite(f.pos.y) && std::isfinite(f.pos.z));
            }
        }
    }
    EXPECT_GT(w.ledger().eaten, 0.0);
    EXPECT_GT(w.ledger().decomposed, 0.0);
}

TEST(Current, ATapPushesWaterOutwardsAndItDiesDown) {
    World w = tank();
    w.startle({100, 150});
    float right = w.current().stirredAt({112, 150}).x;
    float left = w.current().stirredAt({88, 150}).x;
    EXPECT_GT(right, 1.0f);
    EXPECT_LT(left, -1.0f);
    run(w, 15.0f);
    EXPECT_LT(w.current().maxStirred(), 1.0f);
}

TEST(Current, FoodDriftsWithTheWater) {
    World w = tank();
    w.dropFood({100, 150}, FoodKind::Flake, 1);
    Food& f = w.foodForTest()[0];
    f.timer = 100;  // keep it floating
    f.vel = Vec2();
    Vec2 start = f.pos.xy();
    w.currentForTest().push({100, 150}, {30, 0}, 30);
    run(w, 1.0f);
    EXPECT_GT(w.food()[0].pos.x, start.x + 3.0f);
}

TEST(Current, TheFieldStaysBoundedUnderStirring) {
    World w = tank();
    Rng r(1);
    for (int s = 0; s < 3000; s++) {
        w.currentForTest().push({r.range(0, 200), r.range(0, 300)}, {r.range(-50, 50), r.range(-50, 50)}, 15);
        w.step(1.0f / 60.0f);
    }
    float m = w.current().maxStirred();
    EXPECT_TRUE(std::isfinite(m));
    EXPECT_LT(m, 400.0f);
}

TEST(Current, NoFlowThroughTheGlass) {
    World w = tank();
    w.currentForTest().push({1, 150}, {-40, 0}, 12);
    w.step(1.0f / 60.0f);
    EXPECT_GE(w.current().stirredAt({0, 150}).x, 0.0f);
}
