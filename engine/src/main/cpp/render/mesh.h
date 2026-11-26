#pragma once
// Builds the triangles of every creature for one frame, from the simulation's
// state. Fish are strips along their spine with the swimming wave added; the
// turtle is a shell, a head and four flippers on their own chains. Everything
// the shader needs to paint a species is in the vertex: no textures.
#include <cstdint>
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
};

/** The look of food, after the creatures' looks (Visual in sim/species.h). */
constexpr int kVisualFood = 2;
/** Clouds of sand and ink. */
constexpr int kVisualCloud = 6;

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
    float aux[4];
};
static_assert(sizeof(Vertex) == 48, "the vertex layout is shared with the shaders");

struct Mesh {
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;
    int creaturesDrawn = 0;
    int foodDrawn = 0;
    int cloudsDrawn = 0;
    void clear() {
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
    void turtle(const Creature& c, Mesh& m);
    void food(const Food& f, Mesh& m);
    void ray(const Creature& c, Mesh& m, float floorZ);
    void octopus(const Creature& c, Mesh& m);
    void crab(const Creature& c, Mesh& m, float floorZ);
    void cloud(const Cloud& k, Mesh& m);
    enum class ItemType : int8_t { Creature, Food, Cloud };
    struct Item {
        float z;
        ItemType type;
        int index;
        int64_t key;
    };
    std::vector<Item> order_;
};

}  // namespace dt
