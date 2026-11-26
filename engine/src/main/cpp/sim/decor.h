#pragma once
// What stands on the sand of the lagoon and does not swim: coral heads,
// branching coral, giant clams and stones. They are obstacles to whatever
// swims below their top, shelter for some, and the clam closes when a shadow
// or a tap comes near. Sizes come from the Kotlin catalogue (Decor.kt).
#include <cmath>
#include <cstdint>

#include "core/math.h"

namespace dt {

/** Matches DecorKind.nativeId. */
enum class DecorKind : int32_t {
    CoralHead = 0,       // Toʻa: a massive Porites bommie, a lumpy dome
    BranchingCoral = 1,  // Toʻapū: a thicket of Acropora branches, fish hide in it
    GiantClam = 2,       // Pahua: Tridacna maxima, a coloured mantle between fluted valves
    Stone = 3,           // ʻŌfaʻi: a low rock crusted with coralline algae
};

struct Decor {
    int64_t id = 0;
    DecorKind kind = DecorKind::CoralHead;
    uint32_t seed = 0;
    Vec2 pos;
    float angle = 0;   // radians on the table plane
    float radius = 20; // footprint on the sand, mm
    float height = 20; // how far its top stands above the sand, mm

    // Giant clam: how open the valves are (0 shut .. 1 the mantle spread out),
    // and how long until it dares to open again.
    float open = 1;
    float shutTimer = 0;
    float age = 0;

    /**
     * The decoration's own outline: the footprint radius in direction a
     * (relative to its angle), a little irregular so no two are alike.
     */
    float radiusAt(float a) const {
        float s = (float)(seed % 997) * 0.0131f;
        float lobes = kind == DecorKind::Stone ? 0.16f : kind == DecorKind::CoralHead ? 0.1f : 0.0f;
        if (kind == DecorKind::GiantClam) {
            // An ellipse, long along its hinge.
            float c = std::cos(a), si = std::sin(a);
            return radius / std::sqrt(c * c + si * si / (0.56f * 0.56f));
        }
        return radius * (1.0f + lobes * (0.6f * std::sin(3.0f * a + s) + 0.4f * std::sin(5.0f * a + 2.3f * s)));
    }

    /** Height of its surface above the sand at a fraction rho of the outline (0 centre .. 1 edge). */
    float heightAtRho(float rho) const {
        if (rho >= 1.0f) return 0.0f;
        rho = std::max(rho, 0.0f);
        switch (kind) {
            case DecorKind::CoralHead: return height * std::pow(1.0f - rho * rho * rho, 0.7f);    // steep sides, a broad top
            case DecorKind::BranchingCoral: return height * (0.55f + 0.45f * (1.0f - rho * rho)); // the canopy
            case DecorKind::GiantClam: return height * std::sqrt(1.0f - rho * rho);
            case DecorKind::Stone: return height * std::pow(1.0f - rho * rho, 0.8f);
        }
        return 0.0f;
    }

    /** Where p is, as (rho, direction) in the decoration's frame. */
    float rhoOf(Vec2 p) const {
        Vec2 d = p - pos;
        float r = length(d);
        if (r < 1e-4f) return 0.0f;
        float a = std::atan2(d.y, d.x) - angle;
        return r / radiusAt(a);
    }

    /** Height above the sand of its surface over p (0 off it). */
    float heightAt(Vec2 p) const { return heightAtRho(rhoOf(p)); }

    /** Small fish may slip in between its branches. */
    bool sheltering() const { return kind == DecorKind::BranchingCoral; }
};

}  // namespace dt
