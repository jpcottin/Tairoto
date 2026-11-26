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
