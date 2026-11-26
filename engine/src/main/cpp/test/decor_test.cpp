// Host tests for the decorations of the lagoon floor and how the creatures
// live with them: coral heads and stones are solid, branching coral shelters
// the chromis, the giant clam shuts, the octopus makes its den.
#include <gtest/gtest.h>

#include <cmath>

#include "render/camera.h"
#include "render/mesh.h"
#include "sim/world.h"

using namespace dt;

namespace {

constexpr float kDt = 1.0f / 60.0f;
constexpr float kDepth = 55.0f;

World lagoon(uint64_t seed = 21) {
    World w;
    w.setTank({200, 300, kDepth});
    w.setSeed(seed);
    return w;
}

void run(World& w, float seconds) {
    for (int i = 0, n = (int)(seconds / kDt); i < n; i++) w.step(kDt);
}

// Sizes as in the Kotlin catalogue (Decor.kt).
void coralHead(World& w, int64_t id, Vec2 at, uint32_t seed = 3) {
    w.placeDecor(id, DecorKind::CoralHead, seed, at, 0.3f, 28, 26);
}
void branching(World& w, int64_t id, Vec2 at, uint32_t seed = 4) {
    w.placeDecor(id, DecorKind::BranchingCoral, seed, at, 0.0f, 30, 22);
}
void clam(World& w, int64_t id, Vec2 at, uint32_t seed = 5) {
    w.placeDecor(id, DecorKind::GiantClam, seed, at, 0.8f, 17, 6);
}
void stone(World& w, int64_t id, Vec2 at, uint32_t seed = 6) {
    w.placeDecor(id, DecorKind::Stone, seed, at, 1.1f, 18, 10);
}

/** A whole lagoon: schools, a turtle, the bottom dwellers, and one of every decoration. */
World busyLagoon(uint64_t seed) {
    World w = lagoon(seed);
    coralHead(w, 900, {60, 80});
    branching(w, 901, {140, 110});
    clam(w, 902, {150, 220});
    stone(w, 903, {60, 210});
    stone(w, 904, {110, 260}, 9);
    Rng r((uint32_t)seed);
    int id = 1;
    for (int i = 0; i < 16; i++)
        w.spawn(id++, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), r.range(20, 26), r.next(),
                Vec3(r.range(80, 120), r.range(130, 170), r.range(15, 30)));
    for (int i = 0; i < 6; i++)
        w.spawn(id++, Visual::YellowstripeGoatfish, Locomotion::Swim, SpeciesParams::yellowstripeGoatfish(), r.range(24, 30),
                r.next(), Vec3(r.range(80, 120), r.range(130, 170), r.range(40, 50)));
    w.spawn(id++, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 110, 99, Vec3(100, 150, 30));
    w.spawn(id++, Visual::EagleRay, Locomotion::Glide, SpeciesParams::eagleRay(), 32, 7, Vec3(100, 150, 40));
    w.spawn(id++, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 34, 8, Vec3(100, 200, kDepth));
    w.spawn(id++, Visual::HermitCrab, Locomotion::Walk, SpeciesParams::hermitCrab(), 15, 9, Vec3(90, 120, kDepth));
    w.spawn(id++, Visual::HermitCrab, Locomotion::Walk, SpeciesParams::hermitCrab(), 15, 10, Vec3(120, 180, kDepth));
    return w;
}

/** How far a creature's body has sunk into the decorations under its centre (mm, 0 if not at all). */
float sunkInto(const World& w, const Creature& c) {
    float worst = 0;
    for (const Decor& d : w.decor()) {
        if (World::shelters(d, c) || d.heightAt(c.pos.xy()) <= 0.0f) continue;
        float surface = kDepth - d.heightAt(c.pos.xy());
        worst = std::max(worst, c.pos.z + World::bodyBelow(c) - surface);
    }
    return worst;
}

int partOf(const Vertex& v) { return (int)std::floor(v.kind) % 16; }
int visualOf(const Vertex& v) { return (int)std::floor(v.kind) / 16; }

CameraParams cameraOver(const World& w) {
    CameraParams c;
    c.center = {w.tank().width / 2, w.tank().height / 2};
    c.origin = c.center;
    c.pxPerMm = 4;  // everything in view
    c.outSize = {1080, 2400};
    return c;
}

}  // namespace

// ---------------------------------------------------------------- the decorations themselves

TEST(Decor, PlacesMovesAndRemovesByIdOnTheSand) {
    World w = lagoon();
    coralHead(w, 1, {50, 60});
    EXPECT_FALSE(w.placeDecor(1, DecorKind::Stone, 1, {10, 10}, 0, 10, 5)) << "the id is taken";
    stone(w, 2, {500, -40});  // outside: kept on the sand
    ASSERT_EQ(2u, w.decor().size());
    EXPECT_FLOAT_EQ(200.0f, w.findDecor(2)->pos.x);
    EXPECT_FLOAT_EQ(0.0f, w.findDecor(2)->pos.y);
    EXPECT_TRUE(w.moveDecor(1, {120, 130}, 2.0f));
    EXPECT_FLOAT_EQ(120.0f, w.findDecor(1)->pos.x);
    EXPECT_FLOAT_EQ(2.0f, w.findDecor(1)->angle);
    EXPECT_TRUE(w.removeDecor(1));
    EXPECT_FALSE(w.removeDecor(1));
    EXPECT_EQ(nullptr, w.findDecor(1));
    EXPECT_FALSE(w.moveDecor(1, {0, 0}, 0));
}

TEST(Decor, StandsHighestInTheMiddleAndFallsToTheSandAtItsEdge) {
    World w = lagoon();
    coralHead(w, 1, {50, 60});
    branching(w, 2, {150, 60});
    clam(w, 3, {50, 200});
    stone(w, 4, {150, 200});
    for (const Decor& d : w.decor()) {
        EXPECT_NEAR(d.height, d.heightAt(d.pos), 1e-3f) << (int)d.kind;
        float last = d.height + 1e-3f;
        for (int i = 0; i <= 20; i++) {
            float rho = (float)i / 20.0f;
            float hgt = d.heightAtRho(rho);
            EXPECT_LE(hgt, last) << "kind " << (int)d.kind << " rho " << rho;
            last = hgt;
        }
        EXPECT_EQ(0.0f, d.heightAtRho(1.0f));
        // Off its outline, in every direction, there is only sand.
        for (int k = 0; k < 16; k++) {
            float a = kTau * (float)k / 16.0f;
            Vec2 out = d.pos + fromAngle(a + d.angle) * (d.radiusAt(a) * 1.02f);
            EXPECT_EQ(0.0f, d.heightAt(out)) << (int)d.kind;
        }
    }
    EXPECT_NEAR(26.0f, w.decorHeightAt({50, 60}), 1e-3f);
    EXPECT_EQ(0.0f, w.decorHeightAt({100, 130}));
}

TEST(Decor, AClamIsLongAlongItsHinge) {
    World w = lagoon();
    clam(w, 1, {100, 100});
    const Decor& d = *w.findDecor(1);
    EXPECT_NEAR(17.0f, d.radiusAt(0.0f), 1e-3f);
    EXPECT_LT(d.radiusAt(kPi / 2), 0.6f * 17.0f);
}

// ---------------------------------------------------------------- living with them

TEST(Decor, NothingEverSwimsOrWalksIntoACoralHeadOrAStone) {
    // Ten minutes of a full lagoon, frightened now and then: no body ever sinks
    // into a decoration's surface (chromis among branching coral excepted).
    World w = busyLagoon(31);
    float worst = 0;
    for (int s = 0; s < 60 * 600; s++) {
        w.step(kDt);
        if (s % 1200 == 600) w.startle({100.0f + (float)(s % 7) * 10.0f, 150});
        for (const Creature& c : w.creatures()) {
            ASSERT_TRUE(std::isfinite(c.pos.x) && std::isfinite(c.pos.y) && std::isfinite(c.pos.z));
            float sunk = sunkInto(w, c);
            worst = std::max(worst, sunk);
            ASSERT_LT(sunk, 1.0f) << "step " << s << " creature " << c.id << " (" << (int)c.visual << ") at "
                                  << c.pos.x << "," << c.pos.y << "," << c.pos.z;
        }
    }
    SUCCEED() << "deepest " << worst;
}

TEST(Decor, FishSwimOverALowStoneButAroundATallCoralHead) {
    // Goatfish swim low, near the sand: over a stone their path is lifted, a
    // coral head they go round. Chromis, higher up, pass over both.
    World w = lagoon(5);
    stone(w, 1, {70, 150});
    coralHead(w, 2, {140, 150});
    Rng r(5);
    for (int i = 0; i < 12; i++)
        w.spawn(i + 1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, r.next(),
                Vec3(r.range(60, 150), r.range(100, 200), r.range(12, 20)));
    int chromisOverCoral = 0;
    for (int s = 0; s < 60 * 240; s++) {
        w.step(kDt);
        for (const Creature& c : w.creatures())
            if (w.findDecor(2)->heightAt(c.pos.xy()) > 10.0f) chromisOverCoral++;
    }
    EXPECT_GT(chromisOverCoral, 0) << "chromis swim high enough to pass over the coral head";
}

TEST(Decor, FrightenedChromisDiveIntoBranchingCoral) {
    World w = lagoon(7);
    branching(w, 1, {100, 150});
    Rng r(7);
    for (int i = 0; i < 12; i++)
        w.spawn(i + 1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, r.next(),
                Vec3(r.range(110, 150), r.range(140, 180), r.range(14, 24)));
    run(w, 0.3f);
    auto among = [&]() {
        int n = 0;
        const Decor& d = *w.findDecor(1);
        for (const Creature& c : w.creatures())
            if (d.rhoOf(c.pos.xy()) < 0.9f && c.pos.z > kDepth - d.height - 2.0f) n++;
        return n;
    };
    int before = among();
    // A knock on the far side of the school from the coral.
    Vec2 centre;
    for (const Creature& c : w.creatures()) centre += c.pos.xy() / 12.0f;
    w.startle(centre + normalize(centre - Vec2(100, 150)) * 15.0f);
    int most = 0;
    for (int s = 0; s < 150; s++) {
        w.step(kDt);
        most = std::max(most, among());
    }
    EXPECT_GE(most, 7) << "most of the school hides among the branches (" << before << " before)";
    // And they come out again once the fright has passed.
    run(w, 25.0f);
    EXPECT_LT(among(), most);
}

TEST(Decor, TheOctopusMakesItsDenAgainstACoralHead) {
    World w = lagoon(3);
    coralHead(w, 1, {100, 150});
    w.spawn(2, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 34, 6, Vec3(60, 210, kDepth));
    bool denned = false;
    for (int s = 0; s < 60 * 600 && !denned; s++) {
        w.step(kDt);
        const Creature& c = *w.find(2);
        if (c.behavior == Behavior::Hide && c.den && c.cover > 0.8f) {
            const Decor& d = *w.findDecor(1);
            float rho = d.rhoOf(c.pos.xy());
            EXPECT_LT(rho, 1.0f + 0.6f * 34.0f / d.radius) << "tucked in against its side";
            denned = true;
        }
    }
    EXPECT_TRUE(denned);
}

TEST(Decor, TheGiantClamShutsAtATapAndOpensAgainSlowly) {
    World w = lagoon();
    clam(w, 1, {100, 150});
    run(w, 1.0f);
    EXPECT_FLOAT_EQ(1.0f, w.findDecor(1)->open);
    size_t cloudsBefore = w.clouds().size();
    w.startle({110, 155});
    run(w, 0.4f);
    EXPECT_LT(w.findDecor(1)->open, 0.05f);
    EXPECT_GT(w.clouds().size(), cloudsBefore) << "it blows out a puff as it shuts";
    run(w, 3.0f);
    EXPECT_LT(w.findDecor(1)->open, 0.5f) << "it waits before opening";
    run(w, 12.0f);
    EXPECT_GT(w.findDecor(1)->open, 0.95f);
}

TEST(Decor, TheGiantClamShutsWhenATurtleSwimsOverIt) {
    World w = lagoon();
    clam(w, 1, {100, 150});
    w.spawn(2, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 110, 3, Vec3(100, 150, 20));
    run(w, 0.5f);
    EXPECT_LT(w.findDecor(1)->open, 0.2f);
}

TEST(Decor, AFarTapLeavesTheClamOpen) {
    World w = lagoon();
    clam(w, 1, {50, 50});
    run(w, 0.5f);
    w.startle({170, 270});
    run(w, 0.5f);
    EXPECT_FLOAT_EQ(1.0f, w.findDecor(1)->open);
}

TEST(Decor, FoodLandsOnTopOfACoralHead) {
    World w = lagoon();
    coralHead(w, 1, {100, 150});
    w.dropFood({100, 150}, FoodKind::Pellet, 1);
    run(w, 6.0f);
    ASSERT_FALSE(w.food().empty());
    const Food& f = w.food().front();
    EXPECT_EQ(FoodState::Resting, f.state);
    EXPECT_NEAR(kDepth - w.decorHeightAt(f.pos.xy()), f.pos.z, 0.5f);
    EXPECT_LT(f.pos.z, kDepth - 15.0f);
}

TEST(Decor, ALagoonWithDecorationsIsDeterministic) {
    World a = busyLagoon(9), b = busyLagoon(9);
    for (int s = 0; s < 60 * 30; s++) {
        a.step(kDt);
        b.step(kDt);
    }
    ASSERT_EQ(a.creatures().size(), b.creatures().size());
    for (size_t i = 0; i < a.creatures().size(); i++) {
        EXPECT_EQ(a.creatures()[i].pos.x, b.creatures()[i].pos.x);
        EXPECT_EQ(a.creatures()[i].pos.z, b.creatures()[i].pos.z);
    }
    EXPECT_EQ(a.decor()[2].open, b.decor()[2].open);
}

// ---------------------------------------------------------------- drawing them

TEST(DecorMesh, EveryKindIsDrawnWithItsOwnLook) {
    World w = lagoon();
    coralHead(w, 1, {50, 60});
    branching(w, 2, {150, 60});
    clam(w, 3, {50, 200});
    stone(w, 4, {150, 200});
    Mesh m;
    MeshBuilder b;
    b.build(w, cameraOver(w), m);
    EXPECT_EQ(4, m.decorDrawn);
    bool seen[4] = {false, false, false, false};
    bool tips = false;
    for (const Vertex& v : m.vertices) {
        int k = visualOf(v) - kVisualDecor;
        ASSERT_TRUE(k >= 0 && k < 4);
        if (k >= 0 && k < 4) seen[k] = true;
        if (k == 1 && partOf(v) == kPartCoralTip) tips = true;
        EXPECT_GE(v.z, kDepth - 26.0f - 1e-3f);
        EXPECT_LE(v.z, kDepth + 1e-3f);
        for (float a : v.aux) ASSERT_TRUE(std::isfinite(a));
    }
    for (bool s : seen) EXPECT_TRUE(s);
    EXPECT_TRUE(tips) << "branching coral shows the ends of its upright branches";
}

TEST(DecorMesh, TrianglesStayTheSizeOfTheDecoration) {
    World w = lagoon();
    coralHead(w, 1, {50, 60});
    branching(w, 2, {150, 60});
    clam(w, 3, {50, 200});
    stone(w, 4, {150, 200});
    Mesh m;
    MeshBuilder b;
    b.build(w, cameraOver(w), m);
    for (size_t k = 0; k < m.indices.size(); k += 3) {
        const Vertex* v[3] = {&m.vertices[m.indices[k]], &m.vertices[m.indices[k + 1]], &m.vertices[m.indices[k + 2]]};
        for (int e = 0; e < 3; e++)
            ASSERT_LT(length(Vec2(v[e]->x, v[e]->y) - Vec2(v[(e + 1) % 3]->x, v[(e + 1) % 3]->y)), 12.0f);
    }
}

TEST(DecorMesh, ABranchingCoralIsTheSameEveryTimeAndEachIsItsOwn) {
    World w = lagoon();
    branching(w, 1, {60, 100}, 4);
    Mesh a, b2, c;
    MeshBuilder b;
    b.build(w, cameraOver(w), a);
    b.build(w, cameraOver(w), b2);
    ASSERT_EQ(a.vertices.size(), b2.vertices.size());
    for (size_t i = 0; i < a.vertices.size(); i++) EXPECT_EQ(a.vertices[i].x, b2.vertices[i].x);
    w.removeDecor(1);
    branching(w, 1, {60, 100}, 77);
    b.build(w, cameraOver(w), c);
    bool differs = c.vertices.size() != a.vertices.size();
    for (size_t i = 0; !differs && i < a.vertices.size(); i++) differs = a.vertices[i].x != c.vertices[i].x;
    EXPECT_TRUE(differs);
}

TEST(DecorMesh, DrawnAfterWhatIsBesideItAndBeforeWhatSwimsOverIt) {
    World w = lagoon();
    coralHead(w, 1, {100, 150});
    branching(w, 2, {40, 60});
    w.spawn(10, Visual::HermitCrab, Locomotion::Walk, SpeciesParams::hermitCrab(), 15, 3, Vec3(100, 150 + 34 + 10, kDepth));
    w.spawn(11, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 4, Vec3(100, 150, 10));
    w.spawn(12, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, 5, Vec3(40, 60, kDepth - 8));
    Mesh m;
    MeshBuilder b;
    b.build(w, cameraOver(w), m);
    // The first vertex of each thing, in drawing order.
    auto firstOf = [&](int visual, float minZ, float maxZ) {
        for (size_t i = 0; i < m.vertices.size(); i++)
            if (visualOf(m.vertices[i]) == visual && m.vertices[i].z >= minZ && m.vertices[i].z <= maxZ) return (int)i;
        return -1;
    };
    int crab = firstOf((int)Visual::HermitCrab, 0, 100);
    int coral = firstOf(kVisualDecor + (int)DecorKind::CoralHead, 0, 100);
    int branches = firstOf(kVisualDecor + (int)DecorKind::BranchingCoral, 0, 100);
    int above = firstOf((int)Visual::GreenChromis, 0, 15);
    int among = firstOf((int)Visual::GreenChromis, 40, 100);
    ASSERT_GE(crab, 0);
    ASSERT_GE(above, 0);
    ASSERT_GE(among, 0);
    EXPECT_LT(crab, coral) << "the crab beside the coral head is drawn first";
    EXPECT_LT(coral, above) << "the chromis over it is drawn after";
    EXPECT_LT(among, branches) << "a chromis among the branches shows between them";
}

TEST(Decor, ASchoolOfSmallFishOverheadLeavesTheClamOpen) {
    World w = lagoon();
    clam(w, 1, {100, 150});
    Rng r(3);
    for (int i = 0; i < 10; i++)
        w.spawn(i + 2, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 22, r.next(),
                Vec3(r.range(90, 110), r.range(140, 160), r.range(12, 20)));
    for (int s = 0; s < 60 * 3; s++) {
        w.step(kDt);
        for (Creature& c : w.creaturesForTest()) c.pos.z = std::min(c.pos.z, 24.0f);  // they stay up in the water
    }
    EXPECT_FLOAT_EQ(1.0f, w.findDecor(1)->open);
}
