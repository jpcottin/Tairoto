#pragma once
// Food in the water: dropped from above, it floats, sinks, settles on the sand
// and decomposes, unless a fish eats it first. Fish are messy: every bite
// breaks crumbs off the rest. Mass is accounted for to the last crumb.
#include <cstdint>

#include "core/math.h"

namespace dt {

enum class FoodKind : int32_t { Flake = 0, Pellet = 1, Crumb = 2 };
enum class FoodState : int32_t { Floating = 0, Sinking = 1, Resting = 2 };

struct Food {
    uint32_t id = 0;
    FoodKind kind = FoodKind::Flake;
    FoodState state = FoodState::Floating;
    Vec3 pos;
    Vec2 vel;
    float vz = 0;
    float mass = 1;        // mg
    float freshness = 1;   // 1 fresh .. 0 rotting
    float timer = 0;       // seconds left floating
    float spin = 0, spinRate = 0;
    float tumble = 0, tumbleRate = 0;
    uint32_t seed = 0;

    // Food another device owns: shown and smelled here, eaten by asking it.
    bool remote = false;
    float seen = 0;        // world time of its owner's last word
    Vec3 netPos;           // where the owner says it is, dead-reckoned forward

    /** Radius on the table plane, in mm, from the mass. */
    float radius() const {
        switch (kind) {
            case FoodKind::Flake: return 1.3f * std::sqrt(mass / 1.6f);
            case FoodKind::Pellet: return 1.2f * std::cbrt(mass / 5.0f);
            case FoodKind::Crumb:
            default: return std::fmax(0.22f, 0.75f * std::cbrt(mass / 0.4f));
        }
    }
};

/** Where every milligram went. dropped == eaten + decomposed + still in the water, always. */
struct FoodLedger {
    double dropped = 0;
    double eaten = 0;
    double decomposed = 0;
    double sent = 0;       // handed to another device with the food
    double received = 0;   // taken over from another device
};

/** A bite this device's creature took at food another device owns: that device decides. */
struct BiteWant {
    uint32_t food = 0;
    int64_t creature = 0;
    float wantMg = 0;
    float messiness = 0;
};

/** A patch of food and how strongly it draws fish: it grows with the food and with the fish feeding there. */
struct Scent {
    Vec2 at;
    float z = 0;
    float waterMass = 0;  // floating or sinking
    float sandMass = 0;   // resting on the sand
    int eaters = 0;
    float activity = 0;   // recent bites, fading
    int cell = -1;

    bool remote = false;  // the scent of another device's food: no pieces here to find

    float strength(bool bottomFeeder) const {
        float m = waterMass + (bottomFeeder ? sandMass : 0.0f);
        if (m <= 0) return 0;
        return m + 1.2f * (float)eaters + 1.5f * activity;
    }
    /** How far away the patch can be smelled (mm), before the species' nose and hunger. */
    static float radiusFor(float strength) { return 26.0f * std::sqrt(std::fmax(strength, 0.0f)); }
};

struct FoodConstants {
    static constexpr float kFlakeMg = 1.6f;
    static constexpr float kPelletMg = 5.0f;
    static constexpr float kMinMg = 0.04f;   // below this a crumb is dust: decomposed
    static constexpr int kMaxParticles = 1500;
    static constexpr float kScentCell = 12.0f;  // mm
};

}  // namespace dt
