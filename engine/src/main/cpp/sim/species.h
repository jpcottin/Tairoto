#pragma once
// Species as data. The Kotlin catalogue (core/world Species.kt) is the source
// of truth and hands these values over in this order (NativeParam there).
#include <cstdint>

namespace dt {

/** How a creature moves; one controller per mode in the simulation. Matches Locomotion.nativeId. */
enum class Locomotion : int32_t {
    Swim = 0,    // fish: a tail beat, free in the water
    Paddle = 1,  // turtle: rows with its flippers, rests on the sand
    Glide = 2,   // eagle ray: flaps and glides on its wings, buries itself in the sand
    Crawl = 3,   // octopus: walks on its arms, jets away, hides
    Walk = 4,    // hermit crab: steps on its legs carrying its shell
};

/** The look: the mesh profile and the shader pattern. Matches Species.visualId. */
enum class Visual : int32_t {
    GreenChromis = 0, GreenSeaTurtle = 1, EagleRay = 3, Octopus = 4, HermitCrab = 5,
    // More schooling fish of the lagoon (2 is food and 6 clouds).
    ConvictSurgeonfish = 7, BluestripeSnapper = 8, YellowstripeGoatfish = 9, ScissortailSergeant = 10,
    // 11 .. 14 are the decorations. A fish someone drew, wearing its drawing (Creature::skin).
    Drawn = 15,
    // More of the lagoon: paraharaha (raccoon butterflyfish), maʻo mauri (blacktip reef shark).
    RaccoonButterflyfish = 16, BlacktipReefShark = 17,
};

/** A fish that schools, swims with a tail beat and is drawn by the fish mesh. */
inline bool isSchoolingFish(Visual v) {
    return v == Visual::GreenChromis || v == Visual::ConvictSurgeonfish || v == Visual::BluestripeSnapper ||
           v == Visual::YellowstripeGoatfish || v == Visual::ScissortailSergeant || v == Visual::RaccoonButterflyfish ||
           v == Visual::BlacktipReefShark;
}

struct SpeciesParams {
    float cruiseBl = 1.0f;     // cruising speed, body lengths per second
    float burstBl = 3.0f;      // burst speed, body lengths per second
    float depthMin = 0.2f;     // preferred depth band, fraction of the tank depth
    float depthMax = 0.6f;
    float schooling = 0.0f;    // 0 solitary .. 1 tight school
    float shyness = 0.5f;      // 0 bold .. 1 flees from everything
    float turnRate = 3.0f;     // rad/s at cruising speed
    float bodyWidth = 0.2f;    // width / length, seen from above
    float tailBeatHz = 2.0f;   // beats per second at one body length per second
    float smell = 1.0f;        // how far food is smelled (1 = keen)
    float biteMg = 0.5f;       // food taken in one bite
    float messiness = 0.3f;    // fraction of what is left that breaks off as crumbs at a bite
    float stomachMg = 4.0f;    // food from starving to full
    float hungerPerS = 0.005f; // hunger gained per second (0 full .. 1 starving)
    bool bottomFeeder = false; // eats what lies on the sand

    static constexpr int kCount = 15;

    static SpeciesParams fromArray(const float* p, int n) {
        SpeciesParams s;
        if (n < kCount) return s;
        s.cruiseBl = p[0];
        s.burstBl = p[1];
        s.depthMin = p[2];
        s.depthMax = p[3];
        s.schooling = p[4];
        s.shyness = p[5];
        s.turnRate = p[6];
        s.bodyWidth = p[7];
        s.tailBeatHz = p[8];
        s.smell = p[9];
        s.biteMg = p[10];
        s.messiness = p[11];
        s.stomachMg = p[12];
        s.hungerPerS = p[13];
        s.bottomFeeder = p[14] > 0.5f;
        return s;
    }

    /** Defaults that match the Kotlin catalogue, for host tests that do not go through JNI. */
    static SpeciesParams greenChromis() {
        SpeciesParams s;
        s.cruiseBl = 1.3f; s.burstBl = 5.0f; s.depthMin = 0.18f; s.depthMax = 0.62f;
        s.schooling = 0.85f; s.shyness = 0.75f; s.turnRate = 3.0f; s.bodyWidth = 0.24f; s.tailBeatHz = 2.6f;
        s.smell = 1.0f; s.biteMg = 0.45f; s.messiness = 0.35f; s.stomachMg = 4.0f; s.hungerPerS = 1.0f / 150.0f;
        s.bottomFeeder = false;
        return s;
    }
    static SpeciesParams greenSeaTurtle() {
        SpeciesParams s;
        s.cruiseBl = 0.28f; s.burstBl = 0.9f; s.depthMin = 0.3f; s.depthMax = 0.75f;
        s.schooling = 0.0f; s.shyness = 0.2f; s.turnRate = 0.55f; s.bodyWidth = 0.62f; s.tailBeatHz = 0.32f;
        s.smell = 0.8f; s.biteMg = 3.5f; s.messiness = 0.6f; s.stomachMg = 40.0f; s.hungerPerS = 1.0f / 400.0f;
        s.bottomFeeder = true;
        return s;
    }
    static SpeciesParams convictSurgeonfish() {
        SpeciesParams s = greenChromis();
        s.cruiseBl = 1.0f; s.burstBl = 4.0f; s.depthMin = 0.35f; s.depthMax = 0.8f;
        s.schooling = 0.8f; s.shyness = 0.6f; s.turnRate = 2.6f; s.bodyWidth = 0.2f; s.tailBeatHz = 2.2f;
        s.smell = 0.9f; s.biteMg = 0.6f; s.messiness = 0.4f; s.stomachMg = 6.0f; s.hungerPerS = 1.0f / 140.0f;
        s.bottomFeeder = true;  // grazes the algae on the bottom
        return s;
    }
    static SpeciesParams bluestripeSnapper() {
        SpeciesParams s = greenChromis();
        s.cruiseBl = 0.9f; s.burstBl = 4.5f; s.depthMin = 0.3f; s.depthMax = 0.7f;
        s.schooling = 0.95f; s.shyness = 0.5f; s.turnRate = 2.4f; s.bodyWidth = 0.22f; s.tailBeatHz = 2.2f;
        s.smell = 1.1f; s.biteMg = 0.8f; s.messiness = 0.3f; s.stomachMg = 8.0f; s.hungerPerS = 1.0f / 160.0f;
        return s;
    }
    static SpeciesParams yellowstripeGoatfish() {
        SpeciesParams s = greenChromis();
        s.cruiseBl = 0.8f; s.burstBl = 4.0f; s.depthMin = 0.7f; s.depthMax = 0.92f;
        s.schooling = 0.75f; s.shyness = 0.55f; s.turnRate = 2.2f; s.bodyWidth = 0.18f; s.tailBeatHz = 2.0f;
        s.smell = 1.3f; s.biteMg = 0.6f; s.messiness = 0.25f; s.stomachMg = 7.0f; s.hungerPerS = 1.0f / 150.0f;
        s.bottomFeeder = true;  // probes the sand with its barbels
        return s;
    }
    static SpeciesParams scissortailSergeant() {
        SpeciesParams s = greenChromis();
        s.cruiseBl = 1.2f; s.burstBl = 4.8f; s.depthMin = 0.1f; s.depthMax = 0.5f;
        s.schooling = 0.85f; s.shyness = 0.65f; s.turnRate = 2.9f; s.bodyWidth = 0.24f; s.tailBeatHz = 2.5f;
        s.smell = 1.0f; s.biteMg = 0.5f; s.messiness = 0.4f; s.stomachMg = 4.5f; s.hungerPerS = 1.0f / 140.0f;
        return s;
    }
    static SpeciesParams eagleRay() {
        SpeciesParams s;
        s.cruiseBl = 0.5f; s.burstBl = 2.2f; s.depthMin = 0.35f; s.depthMax = 0.8f;
        s.schooling = 0.0f; s.shyness = 0.55f; s.turnRate = 0.7f; s.bodyWidth = 1.8f; s.tailBeatHz = 0.45f;
        s.smell = 0.9f; s.biteMg = 1.2f; s.messiness = 0.5f; s.stomachMg = 20.0f; s.hungerPerS = 1.0f / 300.0f;
        s.bottomFeeder = true;
        return s;
    }
    static SpeciesParams octopus() {
        SpeciesParams s;
        s.cruiseBl = 0.22f; s.burstBl = 3.0f; s.depthMin = 0.92f; s.depthMax = 1.0f;
        s.schooling = 0.0f; s.shyness = 0.8f; s.turnRate = 1.4f; s.bodyWidth = 0.45f; s.tailBeatHz = 0.9f;
        s.smell = 1.2f; s.biteMg = 0.8f; s.messiness = 0.2f; s.stomachMg = 12.0f; s.hungerPerS = 1.0f / 250.0f;
        s.bottomFeeder = true;
        return s;
    }
    static SpeciesParams hermitCrab() {
        SpeciesParams s;
        s.cruiseBl = 0.35f; s.burstBl = 0.8f; s.depthMin = 1.0f; s.depthMax = 1.0f;
        s.schooling = 0.0f; s.shyness = 0.9f; s.turnRate = 1.6f; s.bodyWidth = 0.7f; s.tailBeatHz = 2.2f;
        s.smell = 1.0f; s.biteMg = 0.25f; s.messiness = 0.45f; s.stomachMg = 3.0f; s.hungerPerS = 1.0f / 200.0f;
        s.bottomFeeder = true;
        return s;
    }
};

}  // namespace dt
