#pragma once
// Builds the triangles of every creature for one frame, from the simulation's
// state. Fish are strips along their spine with the swimming wave added; the
// turtle is a shell, a head and four flippers on their own chains. Everything
// the shader needs to paint a species is in the vertex: no textures.
#include <cstdint>
#include <unordered_map>
#include <vector>

#include "render/camera.h"
#include "sim/world.h"

namespace dt {

/** Which piece of a creature a vertex belongs to (the shader paints each its own way). */
enum Part : int32_t {
    kPartBody = 0,
    kPartCaudalFin = 1,
    kPartPectoralFin = 2,
    kPartDorsalFin = 3,
    kPartBarbel = 4,  // a goatfish's chin barbels
    kPartShell = 10,
    kPartHead = 11,
    kPartFlipper = 12,
    // Food (visual 2)
    kPartFlake = 0,
    kPartPellet = 1,
    kPartCrumb = 2,
    // Eagle ray (visual 3)
    kPartRayDisc = 0,
    kPartRayTail = 1,
    // Octopus (visual 4)
    kPartArm = 0,
    kPartWeb = 1,
    kPartMantle = 2,
    kPartOctopusEye = 3,
    // Hermit crab (visual 5)
    kPartCrabShell = 0,
    kPartLeg = 1,
    kPartClaw = 2,
    kPartCrabBody = 3,
    kPartEyeStalk = 4,
    kPartAntenna = 5,
    // Clouds (visual 6)
    kPartSandCloud = 0,
    kPartInkCloud = 1,
    // Decorations (visuals 11 .. 14)
    kPartDecorBody = 0,     // the dome of a coral head, a stone, a clam
    kPartCoralBranch = 0,   // branching coral: one branch
    kPartCoralTip = 1,      // branching coral: a branch growing straight up, seen end on
};

/** The look of food, after the creatures' looks (Visual in sim/species.h). */
constexpr int kVisualFood = 2;
/** Clouds of sand and ink. */
constexpr int kVisualCloud = 6;
/** Decorations: 11 + DecorKind (coral head 11, branching coral 12, giant clam 13, stone 14). */
constexpr int kVisualDecor = 11;
/** A fish someone drew (Visual::Drawn): aux[0] its layer among the drawings, aux[1] which side shows (+1, -1). */
constexpr int kVisualDrawn = 15;

struct Vertex {
    float x, y;        // mm on the table plane
    float u, v;        // along / across the part
    float z;           // mm below the surface
    float kind;        // part + 16 * visual
    float seed;        // per creature, [0, 1)
    float frame;       // angle of the part's local frame, for lighting
    // Per-look extras. For the bottom dwellers and clouds aux[3] is always the
    // distance to the outline (0 inside .. 1 at the edge), for antialiasing and
    // shadows; the rest:
    //   ray:     sand cover, height slope along and across (for the wing light)
    //   octopus: camouflage, alarm, arm index / 8
    //   crab:    drawn in, -, -
    //   cloud:   opacity now, age / life, -
    //   decor:   slope of its surface (dh/dx, dh/dy, world axes), clam: how open
    //            (branching coral: length along the branch in mm, its half width in mm)
    float aux[4];
};
static_assert(sizeof(Vertex) == 48, "the vertex layout is shared with the shaders");

struct Mesh {
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;
    int creaturesDrawn = 0;
    int foodDrawn = 0;
    int cloudsDrawn = 0;
    int decorDrawn = 0;
    void clear() {
        decorDrawn = 0;
        vertices.clear();
        indices.clear();
        creaturesDrawn = 0;
        foodDrawn = 0;
        cloudsDrawn = 0;
    }
};

class MeshBuilder {
public:
    /** Fills mesh with every creature that can be seen (or whose shadow can), deepest first. */
    void build(const World& world, const CameraParams& cam, Mesh& mesh);

    /** The strip half-width of a chromis at t (0 snout .. 1 peduncle), as a fraction of its widest. */
    static float chromisProfile(float t);
    /** The same for any schooling fish: where it is widest and how pointed its snout is differ. */
    static float fishProfile(Visual v, float t);

private:
    void fish(const Creature& c, Mesh& m);
    void drawn(const Creature& c, Mesh& m, const CameraParams& cam, float now);
    void turtle(const Creature& c, Mesh& m);
    void food(const Food& f, Mesh& m);
    void ray(const Creature& c, Mesh& m, float floorZ);
    void octopus(const Creature& c, Mesh& m);
    void crab(const Creature& c, Mesh& m, float floorZ);
    void cloud(const Cloud& k, Mesh& m);
    void decor(const Decor& d, Mesh& m, float floorZ);
    void dome(const Decor& d, Mesh& m, float floorZ, int rings, int segs);
    void branches(const Decor& d, Mesh& m, float floorZ);
    enum class ItemType : int8_t { Creature, Food, Cloud, Decor };
    struct Item {
        float z;
        ItemType type;
        int index;
        int64_t key;
    };
    std::vector<Item> order_;
    /**
     * How far each drawn fish has rolled over: +1 showing the drawing as it
     * was drawn (swimming to the left of the screen), -1 its other side
     * (swimming right), through 0 edge on. Only the look, so it lives here.
     */
    struct Roll {
        float side = 1;
        float seen = 0;
    };
    std::unordered_map<int64_t, Roll> rolls_;
    float lastBuild_ = -1;
};

}  // namespace dt
