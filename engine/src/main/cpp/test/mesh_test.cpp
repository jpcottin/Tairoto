#include <gtest/gtest.h>

#include <cmath>

#include "render/camera.h"
#include "render/mesh.h"

using namespace dt;

namespace {
CameraParams cameraOver(const World& w) {
    CameraParams c;
    c.center = {w.tank().width / 2, w.tank().height / 2};
    c.origin = c.center;
    c.pxPerMm = 420.0f / 25.4f;
    c.outSize = {1080, 2400};
    return c;
}

World tank() {
    World w;
    w.setTank({200, 300, 110});
    w.setSeed(4);
    for (int i = 0; i < 8; i++)
        w.spawn(i + 1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 24, i * 31u,
                Vec3(70.0f + i * 8, 150, 20.0f + i * 5));
    w.spawn(50, Visual::GreenSeaTurtle, Locomotion::Paddle, SpeciesParams::greenSeaTurtle(), 110, 5, Vec3(100, 150, 70));
    for (int s = 0; s < 120; s++) w.step(1.0f / 60.0f);
    return w;
}

int partOf(const Vertex& v) { return (int)std::floor(v.kind) % 16; }
int visualOf(const Vertex& v) { return (int)std::floor(v.kind) / 16; }
}  // namespace

TEST(Camera, MatchesTheKotlinFormula) {
    CameraParams c;
    c.center = {120, 200};
    c.origin = {90, 210};
    c.pxPerMm = 20;
    c.outSize = {1000, 2000};
    for (float z : {0.0f, 12.0f, 110.0f}) {
        Vec2 p(101, 187);
        Vec2 s = worldToScreen(c, p, z);
        Vec2 back = screenToWorld(c, s, z);
        EXPECT_NEAR(p.x, back.x, 1e-3f);
        EXPECT_NEAR(p.y, back.y, 1e-3f);
    }
    // At the surface with the origin at the centre, a mm is pxPerMm pixels.
    c.origin = c.center;
    Vec2 a = worldToScreen(c, c.center, 0), b = worldToScreen(c, c.center + Vec2(1, 0), 0);
    EXPECT_NEAR(20.0f, b.x - a.x, 1e-4f);
    EXPECT_NEAR(500.0f, a.x, 1e-4f);
}

TEST(Camera, DeeperShadowsFallFurtherOnlyForShallowerThings) {
    Vec2 shallow = Sun::shadowOffset(5, 110), deep = Sun::shadowOffset(100, 110), below = Sun::shadowOffset(120, 110);
    EXPECT_GT(length(shallow), length(deep));
    EXPECT_EQ(0.0f, length(below));
    EXPECT_GT(shallow.x, 0);  // the sun is up and to the left
    EXPECT_GT(shallow.y, 0);
}

TEST(Mesh, EveryCreatureInViewIsBuiltWithValidTriangles) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    EXPECT_EQ(9, m.creaturesDrawn);
    ASSERT_FALSE(m.vertices.empty());
    EXPECT_EQ(0u, m.indices.size() % 3);
    for (uint32_t i : m.indices) ASSERT_LT(i, m.vertices.size());
    for (auto& v : m.vertices) {
        ASSERT_TRUE(std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.u) && std::isfinite(v.v));
        ASSERT_GE(v.v, -1.001f);
        ASSERT_LE(v.v, 1.001f);
        ASSERT_GE(v.seed, 0.0f);
        ASSERT_LT(v.seed, 1.0f);
    }
}

TEST(Mesh, PaintersOrderDrawsTheDeepestFirst) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    float last = 1e9f;
    for (uint32_t k = 0; k < m.indices.size(); k += 3) {
        float z = m.vertices[m.indices[k]].z;
        EXPECT_LE(z, last + 1e-4f);
        last = z;
    }
}

TEST(Mesh, AFishHasABodyFinsAndADorsalRidge) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    int parts[16] = {0};
    for (auto& v : m.vertices)
        if (visualOf(v) == (int)Visual::GreenChromis) parts[partOf(v)]++;
    EXPECT_GT(parts[kPartBody], 0);
    EXPECT_GT(parts[kPartCaudalFin], 0);
    EXPECT_GT(parts[kPartPectoralFin], 0);
    EXPECT_GT(parts[kPartDorsalFin], 0);
}

TEST(Mesh, ATurtleHasAShellAHeadAndFourFlippers) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    int shell = 0, head = 0, flipperRows = 0;
    for (auto& v : m.vertices) {
        if (visualOf(v) != (int)Visual::GreenSeaTurtle) continue;
        shell += partOf(v) == kPartShell;
        head += partOf(v) == kPartHead;
        // The root row of each flipper ribbon has u == 0 and v == 0 at its centre.
        flipperRows += partOf(v) == kPartFlipper && v.u == 0.0f && v.v == 0.0f;
    }
    EXPECT_GT(shell, 100);
    EXPECT_GT(head, 0);
    EXPECT_EQ(4, flipperRows);
}

TEST(Mesh, TheShellOutlineRadiusRidesInTheKindFraction) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    float most = 0;
    for (auto& v : m.vertices) {
        if (partOf(v) != kPartShell) continue;
        float rho = (v.kind - std::floor(v.kind)) * 2.0f;
        EXPECT_GE(rho, 0.0f);
        EXPECT_LE(rho, 1.0f + 1e-4f);
        most = std::fmax(most, rho);
    }
    EXPECT_NEAR(1.0f, most, 1e-4f);
}

TEST(Mesh, ShellCoordinatesStayInsideTheUnitSquare) {
    World w = tank();
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    for (auto& v : m.vertices) {
        if (partOf(v) != kPartShell) continue;
        EXPECT_LE(std::fabs(v.u), 1.07f);
        EXPECT_LE(std::fabs(v.v), 1.06f);
    }
}

TEST(Mesh, CreaturesFarOffScreenAreSkipped) {
    World w = tank();
    CameraParams c = cameraOver(w);
    c.center = c.origin = {5000, 5000};
    Mesh m;
    MeshBuilder().build(w, c, m);
    EXPECT_EQ(0, m.creaturesDrawn);
    EXPECT_TRUE(m.vertices.empty());
}

TEST(Mesh, TheProfileIsBluntThenTapers) {
    EXPECT_LT(MeshBuilder::chromisProfile(0), 0.1f);
    EXPECT_GT(MeshBuilder::chromisProfile(0.05f), 0.4f);
    EXPECT_NEAR(1.0f, MeshBuilder::chromisProfile(0.32f), 0.01f);
    EXPECT_NEAR(0.2f, MeshBuilder::chromisProfile(1.0f), 0.01f);
    for (float t = 0.33f; t < 1; t += 0.05f) EXPECT_LE(MeshBuilder::chromisProfile(t + 0.05f), MeshBuilder::chromisProfile(t) + 1e-5f);
}

TEST(Mesh, TheBodyWidthMatchesTheSpecies) {
    World w;
    w.setTank({200, 300, 110});
    w.spawn(1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), 25, 1, Vec3(100, 150, 30));
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    float widest = 0;
    for (size_t i = 0; i + 2 < m.vertices.size(); i++) {
        auto& a = m.vertices[i];
        auto& c = m.vertices[i + 2];
        if (partOf(a) == kPartBody && a.v == -1.0f && c.v == 1.0f)
            widest = std::fmax(widest, length(Vec2(a.x, a.y) - Vec2(c.x, c.y)));
    }
    EXPECT_NEAR(25 * SpeciesParams::greenChromis().bodyWidth, widest, 0.6f);
}

TEST(Mesh, NoTriangleIsEverStretchedAcrossTheTank) {
    // A swimming school seen for two minutes: every chromis triangle stays fish-sized.
    World w;
    w.setTank({200, 300, 110});
    w.setSeed(3);
    Rng r(3);
    for (int i = 0; i < 30; i++)
        w.spawn(i + 1, Visual::GreenChromis, Locomotion::Swim, SpeciesParams::greenChromis(), r.range(27, 35), r.next(),
                Vec3(r.range(40, 160), r.range(40, 260), r.range(20, 60)));
    CameraParams cam = cameraOver(w);
    cam.pxPerMm = 4;  // everything in view
    Mesh m;
    MeshBuilder b;
    for (int s = 0; s < 120 * 60; s++) {
        w.step(1.0f / 60.0f);
        if (s % 5) continue;
        if (s % 600 == 0) w.startle({100, 150});
        b.build(w, cam, m);
        for (size_t k = 0; k < m.indices.size(); k += 3) {
            const Vertex* v[3] = {&m.vertices[m.indices[k]], &m.vertices[m.indices[k + 1]], &m.vertices[m.indices[k + 2]]};
            for (int e = 0; e < 3; e++) {
                float d = length(Vec2(v[e]->x, v[e]->y) - Vec2(v[(e + 1) % 3]->x, v[(e + 1) % 3]->y));
                ASSERT_LT(d, 20.0f) << "step " << s << " part " << partOf(*v[e]) << " u " << v[e]->u;
            }
        }
    }
}

// ---------------------------------------------------------------- bottom dwellers

namespace {
World bottomLagoon(uint64_t seed) {
    World w;
    w.setTank({200, 300, 55});
    w.setSeed(seed);
    w.spawn(1, Visual::EagleRay, Locomotion::Glide, SpeciesParams::eagleRay(), 32, 5, Vec3(100, 150, 25));
    w.spawn(2, Visual::Octopus, Locomotion::Crawl, SpeciesParams::octopus(), 34, 6, Vec3(60, 90, 55));
    w.spawn(3, Visual::HermitCrab, Locomotion::Walk, SpeciesParams::hermitCrab(), 15, 7, Vec3(140, 220, 55));
    return w;
}

int count(const Mesh& m, int visual, int part) {
    int n = 0;
    for (auto& v : m.vertices) n += visualOf(v) == visual && partOf(v) == part;
    return n;
}
}  // namespace

TEST(Mesh, EachBottomDwellerHasItsParts) {
    World w = bottomLagoon(2);
    for (int s = 0; s < 120; s++) w.step(1.0f / 60.0f);
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    EXPECT_GT(count(m, 3, kPartRayDisc), 300);
    EXPECT_GT(count(m, 3, kPartRayTail), 20);
    EXPECT_EQ(8 * 19 * 3, count(m, 4, kPartArm)) << "eight arms";
    EXPECT_GT(count(m, 4, kPartWeb), 10);
    EXPECT_GT(count(m, 4, kPartMantle), 20);
    EXPECT_EQ(2 * 13, count(m, 4, kPartOctopusEye));
    EXPECT_EQ(4 * 5 * 3, count(m, 5, kPartLeg)) << "four walking legs";
    EXPECT_EQ(2 * 7 * 3, count(m, 5, kPartClaw));
    EXPECT_GT(count(m, 5, kPartCrabShell), 100);
    EXPECT_GT(count(m, 5, kPartAntenna), 0);
}

TEST(Mesh, AWithdrawnCrabIsOnlyItsShell) {
    World w = bottomLagoon(2);
    w.startle({140, 220});
    for (int s = 0; s < 30; s++) w.step(1.0f / 60.0f);
    ASSERT_GT(w.find(3)->cover, 0.97f);
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    EXPECT_GT(count(m, 5, kPartCrabShell), 100);
    EXPECT_EQ(0, count(m, 5, kPartClaw));
    EXPECT_EQ(0, count(m, 5, kPartLeg));
    EXPECT_EQ(0, count(m, 5, kPartAntenna));
}

TEST(Mesh, TheRaySpanFollowsTheSpeciesAndWingsFold) {
    World w = bottomLagoon(2);
    w.step(1.0f / 60.0f);
    Mesh m;
    const Creature& c = *w.find(1);
    float semi = 0.5f * c.length * c.sp.bodyWidth;
    float widest = 0;
    for (int s = 0; s < 600; s++) {
        w.step(1.0f / 60.0f);
        MeshBuilder().build(w, cameraOver(w), m);
        Vec2 side = perp(fromAngle(w.find(1)->heading));
        for (auto& v : m.vertices) {
            if (visualOf(v) != 3 || partOf(v) != kPartRayDisc) continue;
            float y = std::fabs(dot(Vec2(v.x, v.y) - w.find(1)->pos.xy(), side));
            widest = std::max(widest, y);
            ASSERT_LE(y, semi * 1.001f);
            ASSERT_GE(v.aux[3], 0.0f);
            ASSERT_LE(v.aux[3], 1.0f);
        }
    }
    EXPECT_GT(widest, semi * 0.9f);
}

TEST(Mesh, CloudsAreDrawnAmongTheCreatures) {
    World w = bottomLagoon(2);
    w.puff(CloudKind::Ink, Vec3(100, 150, 40), Vec2(), 4, 0.9f, 8);
    w.puff(CloudKind::Sand, Vec3(110, 150, 54), Vec2(), 4, 0.6f, 3);
    w.step(1.0f / 60.0f);
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    EXPECT_EQ(2, m.cloudsDrawn);
    EXPECT_GT(count(m, kVisualCloud, kPartInkCloud), 0);
    EXPECT_GT(count(m, kVisualCloud, kPartSandCloud), 0);
}

TEST(Mesh, NoBottomDwellerTriangleIsEverStretched) {
    // Five minutes of ray, octopus and crab, frightened now and then: every
    // triangle stays creature-sized and every value is finite.
    World w = bottomLagoon(5);
    w.dropFood({100, 150}, FoodKind::Pellet, 3);
    CameraParams cam = cameraOver(w);
    cam.pxPerMm = 4;
    Mesh m;
    MeshBuilder b;
    for (int s = 0; s < 60 * 300; s++) {
        w.step(1.0f / 60.0f);
        if (s % 900 == 450) w.startle(w.find(2 + (s / 900) % 2)->pos.xy());
        if (s % 7) continue;
        b.build(w, cam, m);
        for (auto& v : m.vertices) {
            ASSERT_TRUE(std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z) && std::isfinite(v.frame));
            for (float a : v.aux) ASSERT_TRUE(std::isfinite(a));
        }
        for (size_t k = 0; k < m.indices.size(); k += 3) {
            const Vertex* v[3] = {&m.vertices[m.indices[k]], &m.vertices[m.indices[k + 1]], &m.vertices[m.indices[k + 2]]};
            if (visualOf(*v[0]) == kVisualCloud) continue;  // a cloud is one soft disc, as large as it has grown
            for (int e = 0; e < 3; e++) {
                float d = length(Vec2(v[e]->x, v[e]->y) - Vec2(v[(e + 1) % 3]->x, v[(e + 1) % 3]->y));
                ASSERT_LT(d, 25.0f) << "step " << s << " visual " << visualOf(*v[e]) << " part " << partOf(*v[e]);
            }
        }
    }
}

TEST(Mesh, EverySchoolingFishHasItsOwnShape) {
    const Visual fish[] = {Visual::GreenChromis, Visual::ConvictSurgeonfish, Visual::BluestripeSnapper,
                           Visual::YellowstripeGoatfish, Visual::ScissortailSergeant};
    for (Visual v : fish) {
        EXPECT_TRUE(isSchoolingFish(v));
        float widest = 0, at = 0;
        for (float t = 0; t <= 1.0f; t += 0.01f) {
            float p = MeshBuilder::fishProfile(v, t);
            ASSERT_GE(p, 0.0f);
            ASSERT_LE(p, 1.0f + 1e-5f);
            if (p > widest) { widest = p; at = t; }
        }
        EXPECT_GT(widest, 0.99f);
        EXPECT_GT(at, 0.25f);
        EXPECT_LT(at, 0.45f);
        EXPECT_LT(MeshBuilder::fishProfile(v, 1.0f), 0.25f) << "a narrow tail root";
    }
    // The goatfish is the most slender at its head, the surgeonfish the bluntest.
    EXPECT_LT(MeshBuilder::fishProfile(Visual::YellowstripeGoatfish, 0.1f), MeshBuilder::fishProfile(Visual::ConvictSurgeonfish, 0.1f));
    EXPECT_FALSE(isSchoolingFish(Visual::GreenSeaTurtle));
}

TEST(Mesh, OnlyTheGoatfishHasBarbels) {
    World w;
    w.setTank({200, 300, 55});
    w.spawn(1, Visual::YellowstripeGoatfish, Locomotion::Swim, SpeciesParams::yellowstripeGoatfish(), 25, 1, Vec3(100, 150, 40));
    w.spawn(2, Visual::ConvictSurgeonfish, Locomotion::Swim, SpeciesParams::convictSurgeonfish(), 22, 2, Vec3(60, 150, 30));
    w.spawn(3, Visual::ScissortailSergeant, Locomotion::Swim, SpeciesParams::scissortailSergeant(), 18, 3, Vec3(140, 150, 10));
    w.step(1.0f / 60.0f);
    Mesh m;
    MeshBuilder().build(w, cameraOver(w), m);
    int barbels[16] = {};
    for (auto& v : m.vertices)
        if (partOf(v) == kPartBarbel && visualOf(v) < 16) barbels[visualOf(v)]++;
    EXPECT_EQ(2 * 3 * 3, barbels[(int)Visual::YellowstripeGoatfish]);
    EXPECT_EQ(0, barbels[(int)Visual::ConvictSurgeonfish]);
    EXPECT_EQ(0, barbels[(int)Visual::ScissortailSergeant]);
}

TEST(Mesh, MixedSchoolsKeepApartAndStaySmooth) {
    // Five schools in one lagoon for a minute: each fish still swims with its own kind.
    World w;
    w.setTank({200, 300, 55});
    w.setSeed(8);
    Rng r(8);
    struct K { Visual v; SpeciesParams p; float len; };
    const K kinds[] = {{Visual::GreenChromis, SpeciesParams::greenChromis(), 15},
                       {Visual::ConvictSurgeonfish, SpeciesParams::convictSurgeonfish(), 22},
                       {Visual::BluestripeSnapper, SpeciesParams::bluestripeSnapper(), 23},
                       {Visual::YellowstripeGoatfish, SpeciesParams::yellowstripeGoatfish(), 25},
                       {Visual::ScissortailSergeant, SpeciesParams::scissortailSergeant(), 18}};
    int64_t id = 1;
    for (const K& k : kinds)
        for (int i = 0; i < 8; i++)
            w.spawn(id++, k.v, Locomotion::Swim, k.p, k.len, r.next(), Vec3(r.range(40, 160), r.range(40, 260), r.range(10, 45)));
    for (int s = 0; s < 60 * 60; s++) w.step(1.0f / 60.0f);
    // Each fish's nearest neighbour is most often one of its own.
    int own = 0, total = 0;
    for (const Creature& a : w.creatures()) {
        float best = 1e9f;
        const Creature* near = nullptr;
        for (const Creature& b : w.creatures()) {
            if (b.id == a.id) continue;
            float d = length(b.pos.xy() - a.pos.xy());
            if (d < best) { best = d; near = &b; }
        }
        own += near && near->visual == a.visual;
        total++;
    }
    // By chance alone it would be about one in five; schools brushing past each other are real.
    EXPECT_GT((float)own / (float)total, 0.6f);
}
