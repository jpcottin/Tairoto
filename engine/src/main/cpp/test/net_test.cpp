// Host tests for a lagoon shared by several devices, as each device's
// simulation sees it: two Worlds in one process stand for two devices, and
// the test carries their words across at 15 Hz, as the network would.
#include <gtest/gtest.h>

#include <cmath>

#include "sim/world.h"

using namespace dt;

namespace {

constexpr float kDt = 1.0f / 60.0f;
constexpr float kDepth = 55.0f;
constexpr int kStepsPerWord = 4;  // 60 Hz simulation, 15 Hz network

World lagoon(uint64_t seed) {
    World w;
    w.setTank({300, 200, kDepth});
    w.setSeed(seed);
    return w;
}

const Creature& only(const World& w, int64_t id) {
    const Creature* c = w.find(id);
    EXPECT_NE(c, nullptr);
    return *c;
}

Creature& mut(World& w, int64_t id) {
    for (Creature& c : w.creaturesForTest())
        if (c.id == id) return c;
    ADD_FAILURE() << "no creature " << id;
    return w.creaturesForTest()[0];
}

void chromis(World& w, int64_t id, Vec3 at, bool ghost) {
    w.spawn(id, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 1000 + (uint32_t)id, at, ghost);
}

/** Every owned creature of [from] told to [to], as a States message would. */
void tell(const World& from, World& to, float age = 0.03f) {
    for (const Creature& c : from.creatures())
        if (!c.ghost) to.ghostUpdate(World::stateOf(c), age);
}

/** Every owned piece of food of [from] told to [to], as a Foods message would. */
void tellFood(const World& from, World& to, float age = 0.03f) {
    for (const Food& f : from.food())
        if (!f.remote && f.mass > 0) to.ghostFood(f, age);
}

void balanced(const World& w, const char* who) {
    const FoodLedger& l = w.ledger();
    double in = l.dropped + l.received;
    double out = l.eaten + l.decomposed + l.sent + w.foodMass();
    EXPECT_NEAR(in, out, 1e-3) << who << ": dropped " << l.dropped << " received " << l.received << " eaten " << l.eaten
                               << " decomposed " << l.decomposed << " sent " << l.sent << " in the water " << w.foodMass();
}

}  // namespace

TEST(Shared, AGhostFollowsItsOwnerClosely) {
    World a = lagoon(1), b = lagoon(2);
    for (int i = 0; i < 8; i++) {
        chromis(a, i + 1, Vec3(100.0f + 6.0f * (float)i, 100, 20), false);
        chromis(b, i + 1, Vec3(100.0f + 6.0f * (float)i, 100, 20), true);
    }
    float worst = 0;
    for (int s = 0; s < 60 * 30; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) tell(a, b);
        if (s > 60 * 2) {
            for (const Creature& c : a.creatures()) worst = std::max(worst, length(c.pos.xy() - only(b, c.id).pos.xy()));
        }
    }
    // Within a third of a body length of where the owner has it.
    EXPECT_LT(worst, 22.0f * 0.35f);
}

TEST(Shared, AGhostMovesSmoothlyBetweenWords) {
    // Words come 15 times a second, a little late and uneven: the ghost's head
    // never jumps between frames, it glides on.
    World a = lagoon(3), b = lagoon(4);
    chromis(a, 1, Vec3(150, 100, 20), false);
    chromis(b, 1, Vec3(150, 100, 20), true);
    Rng jitter(5);
    Vec2 p0, p1;
    float worst = 0;
    int next = 0;
    for (int s = 0; s < 60 * 30; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s >= next) {
            tell(a, b, jitter.range(0.01f, 0.08f));
            next = s + kStepsPerWord + (int)jitter.range(-1.0f, 2.0f);
        }
        Vec2 p = only(b, 1).pos.xy();
        if (s > 60) worst = std::max(worst, length(p - p1 * 2.0f + p0));
        p0 = p1;
        p1 = p;
    }
    EXPECT_LT(worst, 0.6f) << "mm per frame per frame";
}

TEST(Shared, OctopusArmsOnAGhostStaySoft) {
    // The arms of a ghost octopus are animated here from its motion: they
    // move as smoothly as on its owner's screen.
    World a = lagoon(12), b = lagoon(13);
    a.spawn(2, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 60, 6, Vec3(100, 100, kDepth));
    b.spawn(2, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 60, 6, Vec3(100, 100, kDepth), true);
    auto& o = mut(a, 2);
    o.behavior = Behavior::Cruise;
    o.target = Vec3(220, 150, 54);
    o.stateTimer = 100;
    Vec2 q0[Creature::kArms][Creature::kArmJoints], q1[Creature::kArms][Creature::kArmJoints];
    float worst = 0;
    for (int s = 0; s < 60 * 30; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) tell(a, b);
        const Creature& c = only(b, 2);
        if (s > 60 * 2) {
            for (int k = 0; k < Creature::kArms; k++)
                for (int j = 1; j < Creature::kArmJoints; j++) {
                    Vec2 d = c.arm[k][j] - c.pos.xy();
                    worst = std::max(worst, length(d - q1[k][j] * 2.0f + q0[k][j]));
                }
        }
        for (int k = 0; k < Creature::kArms; k++)
            for (int j = 0; j < Creature::kArmJoints; j++) {
                q0[k][j] = q1[k][j];
                q1[k][j] = c.arm[k][j] - c.pos.xy();
            }
    }
    EXPECT_GT(length(only(b, 2).pos.xy() - Vec2(100, 100)), 30.0f) << "the ghost walked with its owner";
    EXPECT_LT(worst, 0.3f) << "mm per frame per frame";
}

TEST(Shared, AGhostNeverEats) {
    World b = lagoon(6);
    chromis(b, 1, Vec3(150, 100, 3), true);
    mut(b, 1).hunger = 1.0f;
    b.dropFood(Vec2(150, 100), FoodKind::Flake, 20);
    for (int s = 0; s < 60 * 10; s++) b.step(kDt);
    EXPECT_EQ(b.ledger().eaten, 0.0);
    EXPECT_TRUE(b.takeWants().empty());
    EXPECT_EQ(only(b, 1).eatenMg, 0.0f);
}

TEST(Shared, TakingOverCarriesOnWithoutAJump) {
    World a = lagoon(7), b = lagoon(8);
    chromis(a, 1, Vec3(150, 100, 20), false);
    chromis(b, 1, Vec3(150, 100, 20), true);
    mut(a, 1).hunger = 0.83f;
    mut(a, 1).eatenMg = 2.5f;
    for (int s = 0; s < 60 * 5; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) tell(a, b);
    }
    NetState last;
    ASSERT_TRUE(a.release(1, &last));
    EXPECT_FALSE(a.release(1, &last)) << "released once";
    EXPECT_TRUE(only(a, 1).ghost);
    Vec2 before = only(b, 1).pos.xy();
    ASSERT_TRUE(b.takeOver(1, &last));
    const Creature& c = only(b, 1);
    EXPECT_FALSE(c.ghost);
    EXPECT_LT(length(c.pos.xy() - before), 0.01f) << "kept the pose it had here";
    EXPECT_NEAR(c.eatenMg, 2.5f, 1e-4f);
    EXPECT_GT(c.hunger, 0.8f);
    // It swims on, on its own now, and the old owner's ghost follows it.
    Vec2 p0 = c.pos.xy();
    for (int s = 0; s < 60 * 3; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) tell(b, a);
    }
    EXPECT_GT(length(only(b, 1).pos.xy() - p0), 10.0f);
    EXPECT_LT(length(only(b, 1).pos.xy() - only(a, 1).pos.xy()), 10.0f);
}

TEST(Shared, ABiteAtAnotherDevicesFoodIsAskedForAndDecidedThere) {
    World a = lagoon(9), b = lagoon(10);  // a owns the food, b the fish
    a.setFoodSlot(1);
    b.setFoodSlot(2);
    chromis(b, 1, Vec3(150, 100, 2), false);
    mut(b, 1).hunger = 1.0f;
    a.dropFood(Vec2(150, 100), FoodKind::Pellet, 1);
    ASSERT_EQ(a.food().size(), 1u);
    uint32_t id = a.food()[0].id;
    EXPECT_EQ(id >> 24, 1u) << "the id carries the slot of the device that made it";
    float credited = 0;
    double granted = 0;
    for (int s = 0; s < 60 * 20 && a.food().size() > 0; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) {
            tellFood(a, b);
            for (const BiteWant& w : b.takeWants()) {
                EXPECT_EQ(w.creature, 1);
                float got = a.remoteBite(w.food, w.wantMg, w.messiness);
                granted += got;
                b.credit(w.creature, got);
                credited += got;
            }
        }
    }
    EXPECT_GT(granted, 0.5) << "the fish got some";
    EXPECT_NEAR(a.ledger().eaten, granted, 1e-4) << "counted eaten where the food was";
    EXPECT_EQ(b.ledger().eaten, 0.0);
    EXPECT_NEAR(only(b, 1).eatenMg, credited, 1e-4);
    EXPECT_LT(only(b, 1).hunger, 1.0f);
    balanced(a, "a");
    balanced(b, "b");
}

TEST(Shared, ABiteAtFoodThatIsGoneGetsNothing) {
    World a = lagoon(11);
    EXPECT_EQ(a.remoteBite(12345, 1.0f, 0.3f), 0.0f);
    a.dropFood(Vec2(100, 100), FoodKind::Flake, 1);
    uint32_t id = a.food()[0].id;
    Food last;
    ASSERT_TRUE(a.releaseFood(id, &last));
    EXPECT_EQ(a.remoteBite(id, 1.0f, 0.3f), 0.0f) << "handed over: not this device's to give";
}

TEST(Shared, FoodHandedOverIsNeverLostOrCountedTwice) {
    World a = lagoon(14), b = lagoon(15);
    a.setFoodSlot(0);
    b.setFoodSlot(1);
    a.dropFood(Vec2(100, 100), FoodKind::Flake, 30);
    b.dropFood(Vec2(200, 100), FoodKind::Pellet, 3);
    for (int s = 0; s < 60 * 4; s++) {
        a.step(kDt);
        b.step(kDt);
    }
    // Half of a's food drifts onto b's screen.
    std::vector<uint32_t> ids;
    for (const Food& f : a.food())
        if (ids.size() < a.food().size() / 2) ids.push_back(f.id);
    for (uint32_t id : ids) {
        Food last;
        ASSERT_TRUE(a.releaseFood(id, &last));
        b.adoptFood(last);
        b.adoptFood(last);  // a repeat must not count twice
    }
    for (int s = 0; s < 60 * 20; s++) {
        a.step(kDt);
        b.step(kDt);
        if (s % kStepsPerWord == 0) {
            tellFood(b, a);
            tellFood(a, b);
        }
    }
    EXPECT_NEAR(a.ledger().sent, b.ledger().received, 1e-3);
    balanced(a, "a");
    balanced(b, "b");
    double total = a.ledger().dropped + b.ledger().dropped;
    double accounted = a.ledger().eaten + b.ledger().eaten + a.ledger().decomposed + b.ledger().decomposed + a.foodMass() + b.foodMass();
    EXPECT_NEAR(total, accounted, 1e-3);
}

TEST(Shared, AnotherDevicesFoodIsForgottenWhenNobodySpeaksOfIt) {
    World a = lagoon(16), b = lagoon(17);
    a.setFoodSlot(1);
    a.dropFood(Vec2(100, 100), FoodKind::Flake, 5);
    tellFood(a, b);
    EXPECT_EQ(b.food().size(), 5u);
    EXPECT_EQ(b.foodMass(), 0.0) << "not b's to account for";
    for (int s = 0; s < 60 * 2; s++) b.step(kDt);
    EXPECT_TRUE(b.food().empty());
    EXPECT_EQ(b.ledger().decomposed, 0.0);
}

TEST(Shared, FishFollowTheScentOfFoodOnAnotherScreen) {
    World b = lagoon(18);
    for (int i = 0; i < 6; i++) {
        chromis(b, i + 1, Vec3(60.0f + 4.0f * (float)i, 100, 15), false);
        mut(b, i + 1).hunger = 0.95f;
    }
    Scent s;
    s.at = Vec2(250, 100);
    s.z = 5;
    s.waterMass = 60;
    s.eaters = 6;
    s.activity = 4;
    b.setRemoteScents({s});
    float start = 0, end = 0;
    for (const Creature& c : b.creatures()) start += length(c.pos.xy() - s.at);
    for (int st = 0; st < 60 * 8; st++) b.step(kDt);
    for (const Creature& c : b.creatures()) end += length(c.pos.xy() - s.at);
    EXPECT_LT(end, start * 0.5f) << "they went for it";
    EXPECT_TRUE(b.takeWants().empty()) << "nothing here to bite";
}

TEST(Shared, OnlyThisDevicesOwnFoodIsSentAsScent) {
    World a = lagoon(19), b = lagoon(20);
    a.setFoodSlot(1);
    b.setFoodSlot(2);
    a.dropFood(Vec2(100, 100), FoodKind::Flake, 6);
    b.dropFood(Vec2(30, 30), FoodKind::Flake, 6);
    tellFood(a, b);
    b.step(kDt);
    auto scents = b.ownScents();
    ASSERT_FALSE(scents.empty());
    for (const Scent& s : scents) EXPECT_LT(length(s.at - Vec2(30, 30)), 12.0f);
}
