#pragma once
// Species as data. The Kotlin catalogue (core/world Species.kt) is the source
// of truth and hands these values over in this order (NativeParam there).
#include <cstdint>

namespace dt {

enum class Locomotion : int32_t { Swim = 0, Paddle = 1 };

/** The look: the mesh profile and the shader pattern. Matches Species.visualId. */
enum class Visual : int32_t { GreenChromis = 0, GreenSeaTurtle = 1 };

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
};

}  // namespace dt
