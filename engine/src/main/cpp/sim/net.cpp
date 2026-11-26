// A lagoon shared by several devices, as the simulation of one of them sees
// it. Every creature and every piece of food has one owner, the only device
// that simulates it; the others hold a ghost. A ghost here is animated like
// anything else (so a fish crossing an edge looks the same on both screens),
// then steered back toward its owner's word every step. It never eats: a
// bite at food another device owns is a request, and that device decides.
#include <algorithm>

#include "sim/world.h"

namespace dt {

namespace {
// How quickly a ghost closes on where its owner says it is: most of the way in a tenth of a second.
constexpr float kGhostLag = 0.08f;
// A ghost further off than this jumps (a creature it had not heard of for long, or its first word).
float snapDistance(const Creature& c) { return 4.0f * c.length + 20.0f; }
constexpr float kMaxAge = 0.6f;
}  // namespace

NetState World::stateOf(const Creature& c) {
    NetState s;
    s.id = c.id;
    s.pos = c.pos;
    s.heading = c.heading;
    s.speed = c.speed;
    s.vz = c.vz;
    s.behavior = c.behavior;
    s.stateTimer = c.stateTimer;
    s.hunger = c.hunger;
    s.cover = c.cover;
    s.rest = c.rest;
    s.alarm = c.alarm;
    s.target = c.target;
    s.den = c.den;
    s.eatenMg = c.eatenMg;
    s.startle = c.startleTimer;
    return s;
}

bool World::release(int64_t id, NetState* last) {
    for (Creature& c : creatures_) {
        if (c.id != id) continue;
        if (c.ghost) return false;
        c.ghost = true;
        c.net = stateOf(c);
        c.hasNet = true;  // drifts on as it was going until its new owner's word
        c.foodTarget = 0;
        c.grabArm = -1;
        if (last) *last = c.net;
        return true;
    }
    return false;
}

bool World::takeOver(int64_t id, const NetState* s) {
    for (Creature& c : creatures_) {
        if (c.id != id) continue;
        if (!c.ghost) return false;
        c.ghost = false;
        c.hasNet = false;
        if (s) {
            // The pose its ghost had here is kept (no jump at the edge), unless it was far off.
            if (length(s->pos.xy() - c.pos.xy()) > 2.0f * c.length + 10.0f) {
                c.pos = s->pos;
                c.heading = s->heading;
                c.speed = s->speed;
            }
            c.behavior = s->behavior;
            c.stateTimer = s->stateTimer;
            c.hunger = s->hunger;
            c.cover = s->cover;
            c.rest = s->rest;
            c.alarm = s->alarm;
            c.target = s->target;
            c.den = s->den;
            c.eatenMg = s->eatenMg;
            c.startleTimer = std::max(c.startleTimer, s->startle);
        }
        return true;
    }
    return false;
}

void World::ghostUpdate(const NetState& s, float age) {
    for (Creature& c : creatures_) {
        if (c.id != s.id) continue;
        if (!c.ghost) return;  // this device simulates it: its own word counts
        bool first = !c.hasNet;
        c.net = s;
        float a = clamp(age, 0.0f, kMaxAge);
        Vec2 v = fromAngle(s.heading) * s.speed;
        c.net.pos.x += v.x * a;
        c.net.pos.y += v.y * a;
        c.net.pos.z = clamp(c.net.pos.z + s.vz * a, 0.0f, tank_.depth);
        c.net.stateTimer = std::max(0.0f, s.stateTimer - a);
        c.hasNet = true;
        if (first) {
            // Never heard of before: it appears where it is, its body laid out behind it.
            c.pos = c.net.pos;
            c.heading = c.net.heading;
            c.speed = c.net.speed;
            Vec2 back = -fromAngle(c.heading);
            for (int i = 0; i < Creature::kJoints; i++) c.joint[i] = c.pos.xy() + back * (c.segLen * (float)i);
        }
        return;
    }
}

void World::correctGhost(Creature& c, float h) {
    if (!c.ghost || !c.hasNet) return;
    NetState& n = c.net;
    // Dead reckoning: the owner's word moves on the way the creature was moving.
    Vec2 v = fromAngle(n.heading) * n.speed;
    n.pos.x = clamp(n.pos.x + v.x * h, 0.0f, tank_.width);
    n.pos.y = clamp(n.pos.y + v.y * h, 0.0f, tank_.height);
    n.pos.z = clamp(n.pos.z + n.vz * h, 0.0f, tank_.depth);
    n.stateTimer = std::max(0.0f, n.stateTimer - h);

    Vec3 err = n.pos - c.pos;
    if (length(err.xy()) > snapDistance(c) || std::fabs(err.z) > 30.0f) {
        c.pos = n.pos;
        c.heading = n.heading;
        c.speed = n.speed;
        Vec2 back = -fromAngle(c.heading);
        for (int i = 0; i < Creature::kJoints; i++) c.joint[i] = c.pos.xy() + back * (c.segLen * (float)i);
    } else {
        float k = 1.0f - std::exp(-h / kGhostLag);
        c.pos = c.pos + err * k;
        c.heading = wrapAngle(c.heading + wrapAngle(n.heading - c.heading) * k);
        c.speed = lerp(c.speed, n.speed, k);
    }
    // What it is doing is the owner's to say.
    float k = 1.0f - std::exp(-h / 0.3f);
    c.behavior = n.behavior;
    c.stateTimer = n.stateTimer;
    c.hunger = n.hunger;
    c.cover = lerp(c.cover, n.cover, k);
    c.rest = lerp(c.rest, n.rest, k);
    c.alarm = lerp(c.alarm, n.alarm, k);
    c.target = n.target;
    c.den = n.den;
    c.eatenMg = n.eatenMg;
}

// ---------------------------------------------------------------- food

void World::setFoodSlot(int slot) {
    foodSlot_ = (uint32_t)(slot & 0xFF) << 24;
    nextFoodId_ = 1;
}

uint32_t World::newFoodId() {
    uint32_t serial = nextFoodId_++ & 0xFFFFFFu;
    if (serial == 0) serial = nextFoodId_++ & 0xFFFFFFu;
    return foodSlot_ | serial;
}

void World::ghostFood(const Food& in, float age) {
    float a = clamp(age, 0.0f, kMaxAge);
    Vec3 at = in.pos + Vec3(in.vel.x, in.vel.y, in.vz) * a;
    auto it = foodIndex_.find(in.id);
    if (it != foodIndex_.end()) {
        Food& f = food_[it->second];
        if (!f.remote) return;  // this device's own: its word counts
        f.kind = in.kind;
        f.state = in.state;
        f.mass = in.mass;
        f.freshness = in.freshness;
        f.vel = in.vel;
        f.vz = in.vz;
        f.netPos = at;
        f.seen = time_;
        return;
    }
    if ((int)(food_.size() + pendingFood_.size()) >= FoodConstants::kMaxParticles) return;
    Food f = in;
    f.remote = true;
    f.pos = at;
    f.netPos = at;
    f.seen = time_;
    food_.push_back(f);
    foodIndex_[f.id] = food_.size() - 1;
}

bool World::releaseFood(uint32_t id, Food* last) {
    auto it = foodIndex_.find(id);
    if (it == foodIndex_.end()) return false;
    Food& f = food_[it->second];
    if (f.remote || f.mass <= 0) return false;
    ledger_.sent += f.mass;
    f.remote = true;
    f.seen = time_;
    f.netPos = f.pos;
    if (last) *last = f;
    return true;
}

void World::adoptFood(const Food& in) {
    auto it = foodIndex_.find(in.id);
    if (it != foodIndex_.end()) {
        Food& f = food_[it->second];
        if (!f.remote) return;  // already this device's: never count it twice
        Vec3 shown = f.pos;
        f = in;
        f.remote = false;
        // From where its ghost was drawn, if close, so it does not jump.
        if (length(shown - in.pos) < 6.0f) f.pos = shown;
    } else {
        if ((int)(food_.size() + pendingFood_.size()) >= FoodConstants::kMaxParticles) {
            ledger_.received += in.mass;
            ledger_.decomposed += in.mass;  // no room: it dissolves, still accounted for
            return;
        }
        Food f = in;
        f.remote = false;
        food_.push_back(f);
        foodIndex_[f.id] = food_.size() - 1;
    }
    ledger_.received += in.mass;
}

float World::remoteBite(uint32_t id, float wantMg, float messiness) {
    Food* f = findFoodMutable(id);
    if (!f || f->remote) return 0.0f;
    float eaten = std::min(f->mass, std::max(wantMg, 0.0f));
    f->mass -= eaten;
    ledger_.eaten += eaten;
    int cell = scentCellOf(f->pos.xy());
    if (cell >= 0 && cell < (int)activity_.size()) activity_[cell] += 1.0f;
    breakCrumbs(*f, messiness);
    return eaten;
}

void World::credit(int64_t id, float mg) {
    for (Creature& c : creatures_) {
        if (c.id != id || c.ghost) continue;
        c.eatenMg += mg;
        c.hunger = std::max(0.0f, c.hunger - mg / c.sp.stomachMg);
        return;
    }
}

std::vector<BiteWant> World::takeWants() {
    std::vector<BiteWant> out;
    out.swap(wants_);
    return out;
}

std::vector<Scent> World::ownScents() const {
    std::unordered_map<int, Scent> cells;
    std::unordered_map<int, float> weight;
    for (const Food& f : food_) {
        if (f.remote || f.mass <= 0) continue;
        int cell = scentCellOf(f.pos.xy());
        Scent& s = cells[cell];
        s.cell = cell;
        (f.state == FoodState::Resting ? s.sandMass : s.waterMass) += f.mass;
        s.at += f.pos.xy() * f.mass;
        s.z += f.pos.z * f.mass;
        weight[cell] += f.mass;
    }
    std::vector<Scent> out;
    for (auto& [cell, s] : cells) {
        float w = weight[cell];
        if (w <= 0) continue;
        s.at = s.at / w;
        s.z /= w;
        if (cell >= 0 && cell < (int)activity_.size()) s.activity = activity_[cell];
        out.push_back(s);
    }
    for (const Creature& c : creatures_) {
        if (c.ghost || !c.foodTarget || c.scentCell < 0) continue;
        auto it = std::find_if(out.begin(), out.end(), [&](const Scent& s) { return s.cell == c.scentCell; });
        if (it != out.end()) it->eaters++;
    }
    std::sort(out.begin(), out.end(), [](const Scent& a, const Scent& b) { return a.cell < b.cell; });
    return out;
}

}  // namespace dt
