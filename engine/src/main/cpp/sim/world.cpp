#include "sim/world.h"

#include <algorithm>

namespace dt {

// ---------------------------------------------------------------- grid

void SpatialGrid::rebuild(const std::vector<Creature>& cs, const Tank& tank, float cell) {
    cell_ = std::max(cell, 1.0f);
    cols_ = std::max(1, (int)std::ceil(tank.width / cell_));
    rows_ = std::max(1, (int)std::ceil(tank.height / cell_));
    int cells = cols_ * rows_;
    start_.assign(cells + 1, 0);
    cellOf_.resize(cs.size());
    for (size_t i = 0; i < cs.size(); i++) {
        int x = clamp((int)(cs[i].pos.x / cell_), 0, cols_ - 1);
        int y = clamp((int)(cs[i].pos.y / cell_), 0, rows_ - 1);
        cellOf_[i] = y * cols_ + x;
        start_[cellOf_[i] + 1]++;
    }
    for (int c = 0; c < cells; c++) start_[c + 1] += start_[c];
    items_.resize(cs.size());
    std::vector<int> fill(start_.begin(), start_.end() - 1);
    for (size_t i = 0; i < cs.size(); i++) items_[fill[cellOf_[i]]++] = (int)i;
}

// ---------------------------------------------------------------- world

void World::setTank(const Tank& t) {
    tank_ = t;
    current_.resize(t.width, t.height);
    for (auto& c : creatures_) clampInside(c, c.length * 0.5f);
}

bool World::spawn(int64_t id, Visual visual, Locomotion loco, const SpeciesParams& sp, float length,
                  uint32_t seed, Vec3 at, bool ghost) {
    if (find(id)) return false;
    Creature c;
    c.id = id;
    c.visual = visual;
    c.loco = loco;
    c.sp = sp;
    c.length = std::max(length, 2.0f);
    c.seed = seed;
    c.pos = at;
    c.heading = hash01(seed) * kTau - kPi;
    c.speed = sp.cruiseBl * c.length * 0.8f;
    c.depthTarget = clamp(at.z, 1.0f, tank_.depth - 2.0f);
    c.depthTimer = 2.0f + hash01(seed ^ 0x51u) * 6.0f;
    c.tailPhase = hash01(seed ^ 0x9u) * kTau;
    c.pectoralPhase = hash01(seed ^ 0x17u) * kTau;
    c.strokePhase = hash01(seed ^ 0x33u) * kTau;
    c.breathPhase = hash01(seed ^ 0x44u) * kTau;
    c.wanderSeed = hash01(seed ^ 0x77u) * 1000.0f;
    c.segLen = c.length * 0.78f / (Creature::kJoints - 1);
    c.hunger = 0.25f + 0.5f * hash01(seed ^ 0x99u);
    Vec2 back = -fromAngle(c.heading);
    for (int i = 0; i < Creature::kJoints; i++) c.joint[i] = at.xy() + back * (c.segLen * (float)i);
    if (loco == Locomotion::Paddle) {
        c.behavior = Behavior::Cruise;
        c.stateTimer = 6.0f + hash01(seed ^ 0x5u) * 8.0f;
        c.target = Vec3(tank_.width * 0.5f, tank_.height * 0.5f, at.z);
    }
    c.ghost = ghost;
    clampInside(c, c.length * 0.5f);
    if (loco == Locomotion::Glide || loco == Locomotion::Crawl || loco == Locomotion::Walk) initBottomDweller(c);
    creatures_.push_back(c);
    return true;
}

bool World::remove(int64_t id) {
    auto it = std::find_if(creatures_.begin(), creatures_.end(), [&](const Creature& c) { return c.id == id; });
    if (it == creatures_.end()) return false;
    creatures_.erase(it);
    return true;
}

const Creature* World::find(int64_t id) const {
    for (auto& c : creatures_)
        if (c.id == id) return &c;
    return nullptr;
}

int World::count(Visual v) const {
    int n = 0;
    for (auto& c : creatures_) n += c.visual == v;
    return n;
}

Vec2 World::schoolAttractor(Visual v, float t) const {
    // A slow Lissajous loop inside the middle of the tank, a different one per look.
    float k = (float)(int)v * 1.7f;
    float ax = tank_.width * 0.32f, ay = tank_.height * 0.32f;
    return {tank_.width * 0.5f + ax * std::sin(t * 0.045f + k),
            tank_.height * 0.5f + ay * std::sin(t * 0.031f + 1.3f + k * 0.7f)};
}

void World::step(float dt) {
    if (!(dt > 0)) return;
    accumulator_ = std::min(accumulator_ + dt, kStep * kMaxSubsteps);
    while (accumulator_ >= kStep) {
        stepFixed(kStep);
        accumulator_ -= kStep;
    }
}

void World::startle(Vec2 at, float strength) {
    // The tap sends a ring of water outwards.
    for (int i = 0; i < 8; i++) {
        Vec2 d = fromAngle(kTau * (float)i / 8.0f);
        current_.push(at + d * 6.0f, d * (22.0f * strength), 10.0f);
    }
    for (auto& c : creatures_) {
        float d = length(c.pos.xy() - at);
        float radius;
        switch (c.loco) {
            case Locomotion::Paddle: radius = c.length * 0.9f; break;
            case Locomotion::Glide: radius = c.length * 1.1f + 6.0f; break;   // the wings span far
            case Locomotion::Crawl: radius = c.length * 0.8f + 6.0f; break;
            case Locomotion::Walk: radius = c.length * 2.0f + 14.0f; break;   // crabs feel the knock through the sand
            default: radius = (18.0f + 35.0f * c.sp.shyness) * strength; break;
        }
        if (d > radius) continue;
        c.startleFrom = at;
        c.startleDelay = d / 420.0f;  // the fright spreads outwards as a wave
        c.startleTimer = 0.5f + 0.7f * c.sp.shyness;
    }
    // A giant clam feels the knock and shuts.
    for (Decor& d : decor_)
        if (d.kind == DecorKind::GiantClam && length(d.pos - at) < d.radius * 2.5f + 25.0f * strength)
            d.shutTimer = std::max(d.shutTimer, rng_.range(3.5f, 6.0f));
}

void World::stepFixed(float h) {
    time_ += h;
    if (current_.columns() == 0) current_.resize(tank_.width, tank_.height);
    current_.step(h);
    stepFood(h);
    stepClouds(h);
    stepDecor(h);
    buildScents(h);
    float cell = 15.0f;
    for (auto& c : creatures_)
        if (c.loco == Locomotion::Swim) cell = std::max(cell, c.length * 5.0f);
    grid_.rebuild(creatures_, tank_, cell);
    grounded_.clear();
    for (int i = 0; i < (int)creatures_.size(); i++) {
        const Creature& c = creatures_[i];
        bool down = c.loco == Locomotion::Walk || c.loco == Locomotion::Crawl ||
                    (c.loco == Locomotion::Paddle && c.rest > 0.5f) ||
                    (c.loco == Locomotion::Glide && c.behavior == Behavior::Hide && c.pos.z > tank_.depth - 2.5f);
        if (down && c.pos.z > tank_.depth - 0.3f * c.length - 2.0f) grounded_.push_back(i);
    }
    for (auto& c : creatures_) {
        c.age += h;
        switch (c.loco) {
            case Locomotion::Paddle: stepTurtle(c, h); break;
            case Locomotion::Glide: stepRay(c, h); break;
            case Locomotion::Crawl: stepOctopus(c, h); break;
            case Locomotion::Walk: stepCrab(c, h); break;
            default: stepFish(c, h); break;
        }
        correctGhost(c, h);
    }
    settleFood();
}

Vec2 World::wallAvoidance(Vec2 p, Vec2 fwd, float lookAhead, float margin) const {
    Vec2 a = p + fwd * lookAhead;
    Vec2 push;
    auto edge = [&](float dist, Vec2 inward) {
        if (dist < margin) {
            float k = 1.0f - std::max(dist, 0.0f) / margin;
            push += inward * (k * k * 4.0f);
        }
    };
    edge(a.x, {1, 0});
    edge(tank_.width - a.x, {-1, 0});
    edge(a.y, {0, 1});
    edge(tank_.height - a.y, {0, -1});
    // The body itself close to a wall pushes too, so a fish never pins itself to the glass.
    edge(p.x * 1.5f, {1, 0});
    edge((tank_.width - p.x) * 1.5f, {-1, 0});
    edge(p.y * 1.5f, {0, 1});
    edge((tank_.height - p.y) * 1.5f, {0, -1});
    return push;
}

void World::clampInside(Creature& c, float margin) const {
    margin = std::min(margin, std::min(tank_.width, tank_.height) * 0.45f);
    c.pos.x = clamp(c.pos.x, margin, tank_.width - margin);
    c.pos.y = clamp(c.pos.y, margin, tank_.height - margin);
    c.pos.z = clamp(c.pos.z, 1.0f, tank_.depth - 1.0f);
}

// ---------------------------------------------------------------- fish

void World::stepFish(Creature& c, float h) {
    const float L = c.length;
    const float cruise = c.sp.cruiseBl * L;
    const float burst = c.sp.burstBl * L;
    const Vec2 p = c.pos.xy();
    const Vec2 fwd = fromAngle(c.heading);

    Vec2 sep, ali, cohSum;
    float depthSum = 0;
    int n = 0;
    Vec2 avoid;
    const float sepR = 1.3f * L, aliR = 4.0f * L, cohR = 5.0f * L;
    grid_.query(p, std::max(cohR, 30.0f), [&](int i) {
        const Creature& o = creatures_[i];
        if (o.id == c.id) return;
        Vec2 d = o.pos.xy() - p;
        float dist = length(d);
        if (dist < 1e-4f) return;
        bool big = o.loco != Locomotion::Swim || o.length > 2.5f * L;
        if (big) {
            // A ray under the sand or a crab far below is no obstacle to a fish swimming over it.
            if (std::fabs(o.pos.z - c.pos.z) > o.length * 0.5f + 2.0f * L) return;
            float r = o.length * 0.7f + 2.0f * L;
            if (dist < r) avoid -= d / dist * ((r - dist) / r * 3.0f);
            return;
        }
        if (o.visual != c.visual) return;
        // Fish in the same column of water count more than ones far above or below.
        float dz = std::fabs(o.pos.z - c.pos.z);
        float layer = 1.0f / (1.0f + dz / (2.0f * L));
        if (dist < sepR) sep -= d / dist * ((sepR - dist) / sepR) * layer;
        if (dist < aliR) ali += fromAngle(o.heading) * layer;
        if (dist < cohR) {
            cohSum += o.pos.xy();
            depthSum += o.pos.z;
            n++;
        }
    });

    // Food first: a feeding fish leaves the school's pace for the food's.
    Vec3 foodAt;
    const bool feeding = c.startleTimer <= 0 && forage(c, p, h, foodAt);
    const float social = feeding ? 0.25f : 1.0f;

    Vec2 steer = fwd * 1.0f;
    steer += sep * 2.4f;
    const float foodDist = feeding ? length(foodAt.xy() - p) : 0.0f;
    if (feeding) {
        Vec2 toFood = foodAt.xy() - p;
        steer += normalize(toFood, fwd) * 4.5f;
        c.depthTarget = clamp(foodAt.z, 2.0f, tank_.depth - 4.0f);
        c.depthTimer = 0.5f;
    }
    if (length2(ali) > 0) steer += normalize(ali) * (1.1f * c.sp.schooling * social);
    if (n > 0) {
        Vec2 toCenter = cohSum / (float)n - p;
        float dc = length(toCenter);
        steer += normalize(toCenter) * (0.9f * c.sp.schooling * social * saturate(dc / (3.0f * L)));
    }
    // A loose pull toward where the school is heading keeps it roaming the whole tank.
    Vec2 toAttractor = schoolAttractor(c.visual, time_) - p;
    steer += normalize(toAttractor) * (0.35f * c.sp.schooling * social * smoothstep(5.0f, 45.0f, length(toAttractor)));
    // Wandering: each fish has its own slowly changing whim.
    float whim = noise1(c.age * 0.35f + c.wanderSeed, c.seed) * 1.1f;
    steer += rotate(fwd, whim) * (feeding ? 0.15f : 0.55f);
    steer += avoid;
    float margin = std::max(3.0f * L, 9.0f);
    steer += wallAvoidance(p, fwd, 2.5f * L, margin) * 1.6f;
    steer += decorAvoidance(c, fwd, 2.5f * L, 0.4f * L) * 1.6f;

    bool frightened = false;
    const Decor* shelter = nullptr;
    if (c.startleTimer > 0) {
        if (c.startleDelay > 0) {
            c.startleDelay -= h;
        } else {
            c.startleTimer -= h;
            Vec2 away = p - c.startleFrom;
            // A chromis dives into the nearest branching coral rather than out into the open.
            if (c.visual == Visual::GreenChromis)
                shelter = nearestDecor(p, 10.0f * L, [](const Decor& d) { return d.sheltering(); });
            // Unless the coral lies straight toward what frightened it.
            if (shelter && (length(shelter->pos - p) < shelter->radius ||
                            dot(normalize(shelter->pos - p), normalize(c.startleFrom - p, -fwd)) < 0.75f)) {
                Vec2 to = shelter->pos - p;
                float inside = length(to) / shelter->radius;
                // Toward its middle, holding on there among the branches.
                steer += normalize(to, fwd) * (6.0f * std::max(saturate(inside), 0.6f));
                c.depthTarget = tank_.depth - shelter->height * 0.55f;
                c.depthTimer = std::max(c.depthTimer, 1.5f);
                // Down among the branches it stays a few seconds, until the fright has passed.
                if (inside < 0.8f && c.stateTimer <= 0) {
                    c.stateTimer = rng_.range(2.5f, 4.5f);
                    c.startleTimer = std::max(c.startleTimer, c.stateTimer);
                }
            } else {
                shelter = nullptr;
                steer += normalize(away, fwd) * 6.0f;
            }
            if (!shelter || length(shelter->pos - p) > shelter->radius * 1.5f) c.burstTimer = std::max(c.burstTimer, 0.28f);
            else c.burstTimer = 0;
            frightened = true;
        }
    }

    // Turning: limited, and quicker when going fast or frightened.
    float desired = angleOf(steer);
    float maxTurn = c.sp.turnRate * (0.55f + 0.6f * saturate(c.speed / cruise)) * (frightened ? 2.2f : 1.0f);
    // Close to food a fish turns sharply and slows to snap it, rather than circling it.
    if (feeding) maxTurn *= foodDist < 2.0f * L ? 3.0f : 1.5f;
    float before = c.heading;
    c.heading = approachAngle(c.heading, desired, maxTurn * h);
    c.turnRate = damp(c.turnRate, wrapAngle(c.heading - before) / h, 10.0f, h);

    // Beat and glide: a few strong beats, then a coast.
    if (c.burstTimer > 0) c.burstTimer -= h;
    if (c.glideTimer > 0) c.glideTimer -= h;
    else if (!frightened && !feeding && rng_.chance(0.16f * h)) c.glideTimer = rng_.range(0.35f, 0.9f);
    if (c.burstTimer <= 0 && rng_.chance(0.02f * h)) c.burstTimer = rng_.range(0.15f, 0.3f);

    if (c.stateTimer > 0) c.stateTimer -= h;
    float target;
    if (shelter) target = cruise * clamp(length(shelter->pos - p) / shelter->radius, 0.12f, 1.6f);
    else if (c.burstTimer > 0) target = burst;
    else if (feeding) target = cruise * (0.25f + 1.5f * saturate(foodDist / (4.0f * L)));
    else if (c.glideTimer > 0) target = cruise * 0.55f;
    else target = cruise * (0.85f + 0.3f * noise1(c.age * 0.2f, c.seed + 7));

    if (target > c.speed) c.speed = approach(c.speed, target, (c.burstTimer > 0 ? 34.0f : 4.0f) * L * h);
    else c.speed = damp(c.speed, target, c.glideTimer > 0 ? 1.4f : 2.2f, h);

    float wantThrust;
    if (c.burstTimer > 0) wantThrust = 1.0f;
    else if (c.glideTimer > 0) wantThrust = 0.1f;
    else wantThrust = 0.35f + 0.45f * saturate(c.speed / cruise);
    wantThrust = saturate(wantThrust + std::fabs(c.turnRate) * 0.12f);
    c.thrust = damp(c.thrust, wantThrust, 7.0f, h);

    // Tail beat frequency rises with speed, like a real fish.
    c.tailPhase = std::fmod(c.tailPhase + h * kTau * c.sp.tailBeatHz * (0.35f + c.speed / L) * (0.35f + 0.65f * c.thrust), kTau * 64.0f);
    // Pectorals scull faster when hovering.
    c.pectoralPhase = std::fmod(c.pectoralPhase + h * kTau * (2.4f + 2.6f * (1.0f - saturate(c.speed / cruise))), kTau * 64.0f);

    // Depth: drift to a preferred layer, near the rest of the school.
    c.depthTimer -= h;
    if (c.depthTimer <= 0) {
        float want = tank_.depth * rng_.range(c.sp.depthMin, c.sp.depthMax);
        if (n > 0) want = lerp(want, depthSum / (float)n, 0.6f * c.sp.schooling);
        c.depthTarget = want;
        c.depthTimer = rng_.range(4.0f, 12.0f);
    }
    float vzMax = shelter ? 1.5f * L : 0.6f * L;  // diving into cover, it drops fast
    float vzWant = clamp((c.depthTarget - c.pos.z) * (feeding || shelter ? 1.5f : 0.6f), -vzMax, vzMax);
    if (frightened && !shelter) vzWant -= 0.2f * L;  // a startled fish dives a little, then comes back
    c.vz = damp(c.vz, vzWant, shelter ? 5.0f : 2.0f, h);
    c.pos.z = std::max(2.0f, riseAbove(c, clamp(c.pos.z + c.vz * h, 2.0f, tank_.depth - 4.0f), h));

    Vec2 np = p + fromAngle(c.heading) * (c.speed * h);
    c.pos.x = np.x;
    c.pos.y = np.y;
    clampInside(c, L * 0.6f);
    keepOutOfDecor(c, 0.3f * L);
    if (c.startleTimer <= 0) c.startleTimer = 0;

    // The mouth is at the snout: a bite when it reaches the food.
    if (feeding) tryBite(c, c.pos.xy() + fromAngle(c.heading) * (0.04f * L), c.pos.z, 0.3f * L);
    // The tail pushes water back: a wake that stirs food and the current.
    current_.push(c.joint[Creature::kJoints - 1], fromAngle(c.heading) * (-c.speed * 0.012f * (0.3f + c.thrust)), 5.0f);

    // Caudal fin: a damped spring lagging behind the tail's sideways motion.
    float finTarget = -0.55f * std::cos(c.tailPhase) * (0.3f + 0.7f * c.thrust) - c.turnRate * 0.08f;
    const float k = 420.0f, damping = 2.0f * std::sqrt(k) * 0.45f;
    c.finVel += ((finTarget - c.finAngle) * k - c.finVel * damping) * h;
    c.finAngle = clamp(c.finAngle + c.finVel * h, -1.2f, 1.2f);

    updateSpine(c, h);
}

void World::updateSpine(Creature& c, float h) {
    const int N = Creature::kJoints;
    c.joint[0] = c.pos.xy();
    Vec2 fwd = fromAngle(c.heading);
    for (int i = 1; i < N; i++) {
        // The direction the previous segment points, toward the head.
        Vec2 prevDir = i >= 2 ? normalize(c.joint[i - 2] - c.joint[i - 1], fwd) : fwd;
        Vec2 straight = c.joint[i - 1] - prevDir * c.segLen;
        Vec2 follow = c.joint[i - 1] + normalize(c.joint[i] - c.joint[i - 1], -prevDir) * c.segLen;
        // The head is stiff, the tail free: the body bends progressively into a turn.
        float s = (float)i / (float)(N - 1);
        float rate = 70.0f * (1.0f - s) * (1.0f - s) + 9.0f;
        float stiff = 1.0f - std::exp(-rate * h);
        Vec2 q = lerp(follow, straight, stiff);
        c.joint[i] = c.joint[i - 1] + normalize(q - c.joint[i - 1], -prevDir) * c.segLen;
    }
}

// ---------------------------------------------------------------- turtle

void World::chooseTurtleBehavior(Creature& c) {
    const float L = c.length;
    const float D = tank_.depth;
    float r = rng_.uniform();
    auto randomPoint = [&](float marginK) {
        float m = std::min(L * marginK, std::min(tank_.width, tank_.height) * 0.4f);
        return Vec2(rng_.range(m, tank_.width - m), rng_.range(m, tank_.height - m));
    };
    if (r < 0.28f) {
        // Settle on the sand somewhere ahead.
        Vec2 ahead = c.pos.xy() + fromAngle(c.heading) * (L * rng_.range(1.0f, 2.5f));
        Vec2 spot = randomPoint(0.9f);
        spot = lerp(spot, ahead, 0.6f);
        float m = L * 0.8f;
        spot.x = clamp(spot.x, m, tank_.width - m);
        spot.y = clamp(spot.y, m, tank_.height - m);
        c.behavior = Behavior::Descend;
        c.target = Vec3(spot.x, spot.y, D - L * 0.085f);
        c.stateTimer = 30.0f;  // gives up descending after that
    } else if (r < 0.48f) {
        c.behavior = Behavior::Surface;
        Vec2 spot = randomPoint(1.0f);
        c.target = Vec3(spot.x, spot.y, L * 0.035f);
        c.stateTimer = 40.0f;
    } else {
        c.behavior = Behavior::Cruise;
        Vec2 spot = randomPoint(1.0f);
        c.target = Vec3(spot.x, spot.y, D * rng_.range(c.sp.depthMin, c.sp.depthMax));
        c.stateTimer = rng_.range(10.0f, 22.0f);
    }
}

void World::stepTurtle(Creature& c, float h) {
    const float L = c.length;
    const float D = tank_.depth;
    const float cruise = c.sp.cruiseBl * L;
    const Vec2 p = c.pos.xy();
    Vec2 fwd = fromAngle(c.heading);

    // A tap close by: lift off the sand and stroke away.
    if (c.startleTimer > 0) {
        if (c.startleDelay > 0) c.startleDelay -= h;
        else {
            if (c.behavior != Behavior::Flee) {
                c.behavior = Behavior::Flee;
                c.stateTimer = 2.6f;
            }
            c.startleTimer = 0;
        }
    }

    // Hungry and smelling food: go for it (a resting turtle only when very hungry).
    Vec2 mouth = p + fwd * (0.52f * L);
    Vec3 foodAt;
    bool feeding = false;
    bool mayFeed = c.behavior == Behavior::Cruise || c.behavior == Behavior::Forage || c.behavior == Behavior::Descend ||
                   (c.behavior == Behavior::Rest && c.hunger > 0.7f);
    if (mayFeed) {
        feeding = forage(c, mouth, h, foodAt);
        if (feeding && c.behavior != Behavior::Forage) {
            c.behavior = Behavior::Forage;
            c.stateTimer = 20.0f;
        }
    } else {
        c.hunger = std::min(1.0f, c.hunger + c.sp.hungerPerS * h);
        if (c.biteCooldown > 0) c.biteCooldown -= h;
    }

    c.stateTimer -= h;
    float targetSpeed = cruise;
    float restWant = 0;
    Vec2 toTarget = c.target.xy() - p;
    float dist = length(toTarget);
    Vec2 steer = dist > 1e-3f ? toTarget / dist : fwd;

    switch (c.behavior) {
        case Behavior::Cruise:
            if (dist < L * 0.7f) {
                Vec2 m(L, L);
                c.target = Vec3(rng_.range(m.x, tank_.width - m.x), rng_.range(m.y, tank_.height - m.y), c.target.z);
            }
            if (c.stateTimer <= 0) chooseTurtleBehavior(c);
            break;
        case Behavior::Descend:
            targetSpeed = cruise * (0.35f + 0.5f * saturate(dist / (2.0f * L)));
            if ((dist < L * 0.35f && c.pos.z > D - L * 0.14f) || c.stateTimer <= 0) {
                c.behavior = Behavior::Rest;
                c.stateTimer = rng_.range(14.0f, 32.0f);
            }
            break;
        case Behavior::Rest:
            targetSpeed = 0;
            restWant = 1;
            steer = fwd;
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                Vec2 m(L, L);
                c.target = Vec3(rng_.range(m.x, tank_.width - m.x), rng_.range(m.y, tank_.height - m.y),
                                D * rng_.range(c.sp.depthMin, c.sp.depthMax));
                c.stateTimer = rng_.range(10.0f, 20.0f);
            }
            break;
        case Behavior::Surface: {
            targetSpeed = cruise * 0.6f;
            bool atSurface = c.pos.z < L * 0.06f;
            if (atSurface && c.stateTimer > 5.0f) c.stateTimer = rng_.range(3.0f, 5.0f);  // a few breaths
            if (atSurface) targetSpeed = cruise * 0.25f;
            if (dist < L * 0.7f) {
                Vec2 ahead = p + fwd * (2.0f * L);
                c.target = Vec3(clamp(ahead.x, L, tank_.width - L), clamp(ahead.y, L, tank_.height - L), c.target.z);
            }
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                c.target.z = D * rng_.range(c.sp.depthMin, c.sp.depthMax);
                c.stateTimer = rng_.range(10.0f, 20.0f);
            }
            break;
        }
        case Behavior::Forage:
            if (!feeding) {
                c.behavior = Behavior::Cruise;
                c.target.z = D * rng_.range(c.sp.depthMin, c.sp.depthMax);
                c.stateTimer = rng_.range(8.0f, 16.0f);
                break;
            }
            c.target = foodAt;
            toTarget = foodAt.xy() - mouth;
            steer = normalize(toTarget, fwd);
            targetSpeed = cruise * (0.35f + 0.65f * saturate(length(toTarget) / L));
            break;
        case Behavior::Flee: {
            targetSpeed = c.sp.burstBl * L;
            steer = normalize(p - c.startleFrom, fwd);
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                Vec2 ahead = p + fwd * (2.0f * L);
                c.target = Vec3(clamp(ahead.x, L, tank_.width - L), clamp(ahead.y, L, tank_.height - L),
                                D * rng_.range(c.sp.depthMin, c.sp.depthMax));
                c.stateTimer = rng_.range(8.0f, 14.0f);
            }
            break;
        }
        case Behavior::Hide:
        case Behavior::Emerge:
            c.behavior = Behavior::Cruise;  // not a turtle's
            break;
    }

    c.rest = damp(c.rest, restWant, restWant > c.rest ? 0.9f : 2.5f, h);

    // Steering, with the walls.
    if (c.behavior != Behavior::Rest) {
        steer += wallAvoidance(p, fwd, 1.4f * L, 0.9f * L) * 1.2f;
        steer += decorAvoidance(c, fwd, 1.4f * L, 0.4f * L) * 1.2f;
    }
    float desired = angleOf(steer);
    float maxTurn = c.sp.turnRate * (c.behavior == Behavior::Flee ? 2.0f : 1.0f) * (1.0f - 0.9f * c.rest);
    float before = c.heading;
    c.heading = approachAngle(c.heading, desired, maxTurn * h);
    c.turnRate = damp(c.turnRate, wrapAngle(c.heading - before) / h, 4.0f, h);

    // Strokes: a power stroke pushes, the recovery glides.
    float rate = c.sp.tailBeatHz * (0.7f + 0.8f * saturate(c.speed / std::max(cruise, 1.0f)));
    if (c.behavior == Behavior::Flee) rate *= 2.4f;
    c.strokePhase = std::fmod(c.strokePhase + h * kTau * rate * (1.0f - 0.92f * c.rest), kTau * 64.0f);
    float pulse = std::pow(std::max(0.0f, std::sin(c.strokePhase)), 1.5f);
    const float avgPulse = 0.36f;
    float accel = targetSpeed * 0.9f / avgPulse;
    c.speed += pulse * accel * h * (1.0f - c.rest);
    float drag = 0.9f + (c.speed > targetSpeed * 1.2f ? 1.2f : 0.0f) + c.rest * 3.0f;
    c.speed = std::max(0.0f, c.speed - c.speed * drag * h);

    // Depth.
    float zWant = c.behavior == Behavior::Rest ? D - L * 0.085f : c.target.z;
    if (c.behavior == Behavior::Forage) zWant = clamp(c.target.z, L * 0.04f, D - L * 0.085f);
    if (c.behavior == Behavior::Flee) zWant = clamp(c.pos.z - L * 0.2f, L * 0.1f, D - L * 0.2f);
    float vzMax = 0.14f * L * (c.behavior == Behavior::Flee ? 2.0f : 1.0f);
    c.vz = damp(c.vz, clamp((zWant - c.pos.z) * 0.45f, -vzMax, vzMax), 1.2f, h);
    c.pos.z = std::max(L * 0.02f, riseAbove(c, clamp(c.pos.z + c.vz * h, L * 0.02f, D - L * 0.08f), h));

    Vec2 np = p + fromAngle(c.heading) * (c.speed * h);
    c.pos.x = np.x;
    c.pos.y = np.y;
    clampInside(c, L * 0.55f);
    keepOutOfDecor(c, 0.4f * L);

    if (c.behavior == Behavior::Forage) tryBite(c, p + fromAngle(c.heading) * (0.52f * L), c.pos.z, 0.1f * L);
    // Each power stroke shoves water back past the flippers.
    if (pulse > 0.1f) current_.push(p - fromAngle(c.heading) * (0.2f * L), fromAngle(c.heading) * (-pulse * 4.0f), 0.35f * L);

    c.breathPhase = std::fmod(c.breathPhase + h * kTau * (0.2f + 0.05f * (1.0f - c.rest)), kTau * 64.0f);
    float swayWant = 0.25f * noise1(c.age * 0.25f, c.seed) + c.turnRate * 0.6f;
    c.headSway = damp(c.headSway, clamp(swayWant, -0.6f, 0.6f), 2.0f, h);
}

}  // namespace dt
