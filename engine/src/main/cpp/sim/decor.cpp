// The decorations of the lagoon floor and how the creatures live with them:
//   Toʻa, a coral head, and ʻŌfaʻi, a stone: obstacles that swimmers pass over
//   or around, a turtle may rest on, and an octopus makes its den against.
//   Toʻapū, branching coral: green chromis dive in among its branches when
//   frightened, as they do on the reef.
//   Pahua, the giant clam: its valves snap shut when a shadow or a tap comes
//   near, with a puff of stirred-up sand, and open again slowly.
#include <algorithm>

#include "render/camera.h"
#include "sim/world.h"

namespace dt {

namespace {
bool solid(const Decor& d) { return d.kind == DecorKind::CoralHead || d.kind == DecorKind::Stone; }
}  // namespace

bool World::placeDecor(int64_t id, DecorKind kind, uint32_t seed, Vec2 at, float angle, float radius, float height) {
    if (findDecor(id)) return false;
    Decor d;
    d.id = id;
    d.kind = kind;
    d.seed = seed;
    d.pos = Vec2(clamp(at.x, 0.0f, tank_.width), clamp(at.y, 0.0f, tank_.height));
    d.angle = angle;
    d.radius = std::max(radius, 1.0f);
    d.height = clamp(height, 0.5f, tank_.depth * 0.8f);
    decor_.push_back(d);
    return true;
}

bool World::moveDecor(int64_t id, Vec2 at, float angle) {
    for (Decor& d : decor_)
        if (d.id == id) {
            d.pos = Vec2(clamp(at.x, 0.0f, tank_.width), clamp(at.y, 0.0f, tank_.height));
            d.angle = angle;
            return true;
        }
    return false;
}

bool World::removeDecor(int64_t id) {
    auto it = std::find_if(decor_.begin(), decor_.end(), [&](const Decor& d) { return d.id == id; });
    if (it == decor_.end()) return false;
    decor_.erase(it);
    return true;
}

const Decor* World::findDecor(int64_t id) const {
    for (const Decor& d : decor_)
        if (d.id == id) return &d;
    return nullptr;
}

float World::decorHeightAt(Vec2 p) const {
    float best = 0;
    for (const Decor& d : decor_) {
        if (length2(p - d.pos) > d.radius * d.radius * 1.6f) continue;
        best = std::max(best, d.heightAt(p));
    }
    return best;
}

bool World::onDecor(Vec2 p, float pad) const {
    for (const Decor& d : decor_) {
        Vec2 v = p - d.pos;
        float r = length(v);
        float edge = d.radiusAt(std::atan2(v.y, v.x) - d.angle) + pad;
        if (r < edge) return true;
    }
    return false;
}

bool World::shelters(const Decor& d, const Creature& c) {
    return d.sheltering() && c.visual == Visual::GreenChromis;
}

float World::bodyBelow(const Creature& c) {
    switch (c.loco) {
        case Locomotion::Glide: return 1.5f;
        case Locomotion::Paddle: return 0.12f * c.length;
        case Locomotion::Walk: return 0.5f;   // standing on the sand
        case Locomotion::Crawl: return 0.5f;
        default: return 0.08f * c.length + 0.5f;
    }
}

Vec2 World::decorAvoidance(const Creature& c, Vec2 fwd, float lookAhead, float pad) const {
    const Vec2 p = c.pos.xy();
    const float D = tank_.depth;
    Vec2 push;
    for (const Decor& d : decor_) {
        if (shelters(d, c)) continue;
        // Something that swims well above it passes over.
        if (c.pos.z + bodyBelow(c) < D - d.height) continue;
        float reach = d.radius * 1.25f + pad + lookAhead;
        if (length2(p - d.pos) > reach * reach) continue;
        for (int k = 1; k <= 2; k++) {
            Vec2 a = p + fwd * (lookAhead * 0.5f * (float)k);
            Vec2 v = a - d.pos;
            float r = length(v);
            float edge = d.radiusAt(std::atan2(v.y, v.x) - d.angle) + pad;
            float near = edge * 1.25f;
            if (r >= near) continue;
            float w = (near - r) / near;
            w = w * w * 4.0f / (float)k;
            // Away from its centre, and round it on the side we are already on,
            // so a creature heading straight at it does not stop dead.
            Vec2 side = perp(fwd);
            if (dot(side, d.pos - p) > 0) side = -side;
            push += normalize(v, side) * (w * 0.6f) + side * (w * 1.2f);
        }
    }
    return push;
}

void World::keepOutOfDecor(Creature& c, float pad) const {
    const float D = tank_.depth;
    const float below = bodyBelow(c);
    for (const Decor& d : decor_) {
        if (shelters(d, c)) continue;
        Vec2 v = c.pos.xy() - d.pos;
        float r = length(v);
        float a = r > 1e-4f ? std::atan2(v.y, v.x) : 0.0f;
        float edge = d.radiusAt(a - d.angle) + pad;
        if (r >= edge) continue;
        float allowed = D - c.pos.z - below;  // the highest surface it can be over
        float rho = r / edge;
        if (d.heightAtRho(rho) <= allowed) continue;
        // Out to where the surface is low enough beneath it: the surface only
        // falls toward the edge, so a bisection finds it.
        float lo = rho, hi = 1.0f;
        for (int i = 0; i < 14; i++) {
            float mid = 0.5f * (lo + hi);
            if (d.heightAtRho(mid) > allowed) lo = mid;
            else hi = mid;
        }
        Vec2 dir = r > 1e-4f ? v / r : fromAngle(c.heading + kPi);
        Vec2 np = d.pos + dir * (hi * edge + 0.01f);
        c.pos.x = np.x;
        c.pos.y = np.y;
    }
}

const Decor* World::nearestDecor(Vec2 p, float maxDist, bool (*accept)(const Decor&)) const {
    const Decor* best = nullptr;
    float bestD = maxDist;
    for (const Decor& d : decor_) {
        if (!accept(d)) continue;
        float dist = length(p - d.pos) - d.radius;
        if (dist < bestD) {
            bestD = dist;
            best = &d;
        }
    }
    return best;
}

Vec2 World::openSand(float margin, float pad) {
    margin = std::min(margin, std::min(tank_.width, tank_.height) * 0.4f);
    Vec2 p;
    for (int tries = 0; tries < 12; tries++) {
        p = Vec2(rng_.range(margin, tank_.width - margin), rng_.range(margin, tank_.height - margin));
        if (!onDecor(p, pad)) break;
    }
    return p;
}

bool World::denFor(const Creature& c, Vec2& at) const {
    const Decor* d = nearestDecor(c.pos.xy(), 6.0f * c.length, solid);
    if (!d) return false;
    // Against its side, on the side the octopus comes from.
    Vec2 dir = normalize(c.pos.xy() - d->pos, fromAngle(c.heading + kPi));
    float edge = d->radiusAt(std::atan2(dir.y, dir.x) - d->angle);
    at = d->pos + dir * (edge + 0.3f * c.length);
    float m = 0.5f * c.length;
    at = Vec2(clamp(at.x, m, tank_.width - m), clamp(at.y, m, tank_.height - m));
    return true;
}

void World::stepDecor(float h) {
    const float D = tank_.depth;
    for (Decor& d : decor_) {
        d.age += h;
        if (d.kind != DecorKind::GiantClam) continue;
        // A clam feels a shadow pass over it, or a creature come close.
        bool threat = false;
        for (const Creature& c : creatures_) {
            Vec2 at = c.pos.xy() + Sun::shadowOffset(c.pos.z, D);
            bool big = c.loco != Locomotion::Swim || c.length > 1.6f * d.radius;
            // A small fish only if it comes right down to it; its shadow is too slight.
            if (!big && c.pos.z < D - 2.0f * d.height) continue;
            float reach = big ? d.radius + extentOf(c) * 0.6f : d.radius * 0.7f;
            if (length2(at - d.pos) < reach * reach || length2(c.pos.xy() - d.pos) < reach * reach) {
                threat = true;
                break;
            }
        }
        if (threat) d.shutTimer = std::max(d.shutTimer, rng_.range(3.0f, 5.0f));
        if (d.shutTimer > 0) {
            d.shutTimer -= h;
            if (d.open > 0.6f && d.open - 9.0f * h <= 0.6f) {
                // The valves clap shut and blow out a puff of water.
                puff(CloudKind::Sand, Vec3(d.pos.x, d.pos.y, D - d.height * 0.5f), fromAngle(d.angle) * 4.0f,
                     d.radius * 0.7f, 0.35f, 1.6f);
            }
            d.open = std::max(0.0f, d.open - 9.0f * h);
        } else {
            d.open = std::min(1.0f, d.open + 0.22f * h);  // and opens again slowly, warily
        }
    }
}

}  // namespace dt
