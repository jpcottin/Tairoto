// The bottom dwellers of the lagoon:
//   Fai manu, the ocellated eagle ray: flaps and glides on its wings, settles
//   on the sand and throws sand over itself until only its eyes show.
//   Feʻe, the day octopus: walks on its arms, fades into the sand, inks and
//   jets away when frightened, reaches out an arm for food.
//   Uʻa, the hermit crab: steps on its legs carrying its shell, picks at the
//   sand, and slams into the shell when something big comes close.
// Plus the clouds of sand and ink they leave in the water.
#include <algorithm>

#include "sim/world.h"

namespace dt {

namespace {
constexpr int kAJ = Creature::kArmJoints;

float crabLift(float L) { return 0.2f * L; }

/** The arm's place around the crown: angle from the front, and its spread when the arms fan out. */
constexpr float kArmRoot[4] = {0.35f, 0.95f, 1.65f, 2.35f};
constexpr float kArmSpread[4] = {0.3f, 0.95f, 1.75f, 2.55f};

int legGroup(int leg) { return (leg == 0 || leg == 3) ? 0 : 1; }
}  // namespace

// ---------------------------------------------------------------- shared

float World::topOf(const Creature& c) {
    const float L = c.length;
    switch (c.loco) {
        case Locomotion::Walk: return c.pos.z - 0.25f * L;   // the shell's dome
        case Locomotion::Crawl: return c.pos.z - 0.1f * L;   // the mantle
        case Locomotion::Paddle: return c.pos.z - 0.1f * L;  // the carapace
        case Locomotion::Glide: return c.pos.z - 0.5f;       // a flat disc
        default: return c.pos.z - 0.06f * L;
    }
}

float World::floorUnder(const Creature& c) const {
    float limit = tank_.depth;
    // How far it reaches out over the sand, and how far below its centre it goes.
    float reach, below;
    switch (c.loco) {
        case Locomotion::Glide:
            reach = 0.42f * c.length * c.sp.bodyWidth;
            below = 1.5f;
            break;
        case Locomotion::Paddle:
            reach = 0.45f * c.length;
            below = 0.12f * c.length;
            break;
        default:
            reach = 0.35f * c.length;
            below = 0.1f * c.length + 1.0f;
            break;
    }
    // Over a coral head or a stone it keeps above its top (a chromis may go in among the branches).
    for (const Decor& d : decor_) {
        if (shelters(d, c)) continue;
        Vec2 v = c.pos.xy() - d.pos;
        float r = length(v);
        float edge = d.radiusAt((r > 1e-4f ? std::atan2(v.y, v.x) : 0.0f) - d.angle) + reach * 0.5f;
        if (r >= edge) continue;
        limit = std::min(limit, tank_.depth - d.heightAtRho(r / edge) - below);
    }
    for (int i : grounded_) {
        const Creature& o = creatures_[i];
        if (o.id == c.id) continue;
        // The turtle settles beside a crab or over a buried ray, not into them; a ray buries where it lands.
        float r = reach + extentOf(o) * (o.loco == Locomotion::Crawl ? 0.35f : 0.55f);
        if (length2(o.pos.xy() - c.pos.xy()) > r * r) continue;
        limit = std::min(limit, topOf(o) - below);
    }
    return limit;
}

float World::extentOf(const Creature& c) {
    const float L = c.length;
    switch (c.loco) {
        case Locomotion::Glide: return std::max(0.5f * L * c.sp.bodyWidth, 2.2f * L);  // the tail reaches far back
        case Locomotion::Crawl: return 1.0f * L;
        case Locomotion::Walk: return 0.95f * L;  // antennae
        default: return 0.75f * L;
    }
}

void World::initBottomDweller(Creature& c) {
    const float L = c.length, D = tank_.depth;
    const Vec2 fwd = fromAngle(c.heading);
    c.behavior = Behavior::Cruise;
    c.stateTimer = 8.0f + hash01(c.seed ^ 0x5u) * 10.0f;
    c.puffTimer = 0;
    c.gaitPhase = hash01(c.seed ^ 0x61u) * kTau;
    switch (c.loco) {
        case Locomotion::Glide: {
            float span = L * c.sp.bodyWidth;
            float m = std::min(span * 0.6f, std::min(tank_.width, tank_.height) * 0.4f);
            c.target = Vec3(rng_.range(m, tank_.width - m), rng_.range(m, tank_.height - m),
                            D * rng_.range(c.sp.depthMin, c.sp.depthMax));
            c.pos.z = clamp(c.pos.z, 4.0f, D - 1.0f);
            c.flapAmp = 0.6f;
            c.flapPhase = hash01(c.seed ^ 0x3u) * kTau;
            c.speed = c.sp.cruiseBl * L * 0.8f;
            // The tail: a long whip trailing from the back of the disc.
            c.segLen = 1.6f * L / (Creature::kJoints - 1);
            Vec2 base = c.pos.xy() - fwd * (0.5f * L);
            for (int i = 0; i < Creature::kJoints; i++) c.joint[i] = base - fwd * (c.segLen * (float)i);
            break;
        }
        case Locomotion::Crawl: {
            c.pos.z = D - 1.5f;
            c.speed = 0;
            c.mantleAngle = c.heading + kPi;
            c.target = Vec3(c.pos.x, c.pos.y, c.pos.z);
            c.cover = 0.5f;
            const float frontA = c.heading;
            for (int k = 0; k < Creature::kArms; k++) {
                float s = k < 4 ? -1.0f : 1.0f;
                int i = k % 4;
                c.armLen[k] = 0.72f * L * (0.92f + 0.16f * hash01(c.seed * 7u + (uint32_t)k));
                Vec2 dir = fromAngle(frontA + s * kArmSpread[i]);
                Vec2 root = c.pos.xy() + fromAngle(frontA + s * kArmRoot[i]) * (0.06f * L);
                float seg = c.armLen[k] / (kAJ - 1);
                for (int j = 0; j < kAJ; j++) c.arm[k][j] = root + dir * (seg * (float)j);
                for (int j = 0; j < kAJ - 1; j++) c.armAng[k][j] = angleOf(dir);
                c.armSeg[k] = seg;
            }
            break;
        }
        case Locomotion::Walk: {
            c.pos.z = std::min(D - crabLift(L), D - 1.0f);
            c.speed = 0;
            c.target = Vec3(c.pos.x, c.pos.y, c.pos.z);
            for (int leg = 0; leg < Creature::kLegs; leg++) {
                c.foot[leg] = crabFootRest(c, leg);
                c.footFrom[leg] = c.foot[leg];
                c.swing[leg] = -1;
            }
            break;
        }
        default: break;
    }
}

bool World::puff(CloudKind kind, Vec3 at, Vec2 vel, float radius, float density, float life) {
    if ((int)clouds_.size() >= CloudConstants::kMax) return false;
    Cloud k;
    k.kind = kind;
    k.seed = rng_.next();
    k.pos = Vec3(clamp(at.x, 0.5f, tank_.width - 0.5f), clamp(at.y, 0.5f, tank_.height - 0.5f),
                 clamp(at.z, 0.5f, tank_.depth - 0.5f));
    k.vel = vel;
    k.vz = kind == CloudKind::Sand ? -rng_.range(1.5f, 4.0f) : rng_.range(-1.0f, 1.0f);
    k.radius = radius;
    k.growth = radius * (kind == CloudKind::Ink ? 1.6f : 1.3f);
    k.density = density;
    k.life = life;
    k.spin = rng_.range(0, kTau);
    clouds_.push_back(k);
    return true;
}

int World::inkClouds() const {
    int n = 0;
    for (auto& k : clouds_) n += k.kind == CloudKind::Ink;
    return n;
}

void World::sandBurst(Vec2 at, float radius, int count, float strength) {
    const float D = tank_.depth;
    for (int i = 0; i < count; i++) {
        float a = kTau * ((float)i + rng_.range(-0.3f, 0.3f)) / (float)count;
        Vec2 dir = fromAngle(a);
        Vec2 pt = at + dir * (radius * rng_.range(0.55f, 1.05f));
        puff(CloudKind::Sand, Vec3(pt.x, pt.y, D - 1.5f), dir * (rng_.range(10.0f, 22.0f) * strength),
             rng_.range(3.0f, 6.0f) * (0.6f + 0.4f * strength), rng_.range(0.55f, 0.8f), rng_.range(2.2f, 3.4f));
        current_.push(pt, dir * (12.0f * strength), 8.0f);
    }
}

void World::stepClouds(float h) {
    const float D = tank_.depth;
    for (Cloud& k : clouds_) {
        k.age += h;
        Vec2 cur = current_.at(k.pos.xy());
        k.vel = cur + (k.vel - cur) * std::exp(-1.8f * h);
        k.pos.x = clamp(k.pos.x + k.vel.x * h, 0.5f, tank_.width - 0.5f);
        k.pos.y = clamp(k.pos.y + k.vel.y * h, 0.5f, tank_.height - 0.5f);
        k.radius += k.growth * h;
        k.growth *= std::exp((k.kind == CloudKind::Ink ? -0.35f : -0.9f) * h);
        // Sand is heavy and settles back; ink hangs in the water.
        k.vz = damp(k.vz, k.kind == CloudKind::Sand ? 2.2f : 0.25f, k.kind == CloudKind::Sand ? 0.8f : 0.5f, h);
        k.pos.z = clamp(k.pos.z + k.vz * h, 0.5f, D - 0.5f);
        k.spin += h * 0.3f;
    }
    clouds_.erase(std::remove_if(clouds_.begin(), clouds_.end(), [](const Cloud& k) { return k.age >= k.life; }),
                  clouds_.end());
}

// ---------------------------------------------------------------- eagle ray

void World::chooseRayBehavior(Creature& c) {
    const float D = tank_.depth;
    float span = c.length * c.sp.bodyWidth;
    float m = std::min(span * 0.6f, std::min(tank_.width, tank_.height) * 0.4f);
    float x = rng_.range(m, tank_.width - m), y = rng_.range(m, tank_.height - m);
    if (rng_.chance(0.4f)) {
        c.behavior = Behavior::Descend;
        c.target = Vec3(x, y, D - 1.0f);
        c.stateTimer = 30.0f;
    } else {
        c.behavior = Behavior::Cruise;
        c.target = Vec3(x, y, D * rng_.range(c.sp.depthMin, c.sp.depthMax));
        c.stateTimer = rng_.range(12.0f, 26.0f);
    }
}

void World::stepRay(Creature& c, float h) {
    const float L = c.length, D = tank_.depth;
    const float span = L * c.sp.bodyWidth;
    const float cruise = c.sp.cruiseBl * L;
    const float floorZ = D - 1.0f;
    const Vec2 p = c.pos.xy();
    const Vec2 fwd = fromAngle(c.heading);
    const Vec2 side = perp(fwd);

    // A tap close by: out of the sand in a burst, and away.
    if (c.startleTimer > 0) {
        if (c.startleDelay > 0) c.startleDelay -= h;
        else {
            if (c.behavior == Behavior::Hide || c.cover > 0.3f) sandBurst(p, span * 0.42f, 14, 1.0f);
            c.behavior = Behavior::Flee;
            c.stateTimer = 2.5f;
            c.startleTimer = 0;
            c.glideTimer = 0;
        }
    }

    // Food: eagle rays dig for their food in the sand.
    const Vec2 mouth = p + fwd * (0.4f * L);
    Vec3 foodAt;
    bool feeding = false;
    bool mayFeed = c.behavior == Behavior::Cruise || c.behavior == Behavior::Forage || c.behavior == Behavior::Descend ||
                   (c.behavior == Behavior::Hide && c.hunger > 0.75f && c.cover > 0.95f);
    if (mayFeed) {
        feeding = forage(c, mouth, h, foodAt);
        if (feeding && c.behavior != Behavior::Forage) {
            if (c.behavior == Behavior::Hide) {
                c.behavior = Behavior::Emerge;
                c.stateTimer = 6.0f;
                sandBurst(p, span * 0.42f, 10, 0.7f);
            } else {
                c.behavior = Behavior::Forage;
                c.stateTimer = 25.0f;
            }
        }
    } else {
        c.hunger = std::min(1.0f, c.hunger + c.sp.hungerPerS * h);
        if (c.biteCooldown > 0) c.biteCooldown -= h;
    }

    c.stateTimer -= h;
    float targetSpeed = cruise, flapWant = 0.6f, zWant = c.target.z, coverWant = 0;
    Vec2 toT = c.target.xy() - p;
    float dist = length(toT);
    Vec2 steer = dist > 1e-3f ? toT / dist : fwd;
    bool onSand = false;

    switch (c.behavior) {
        case Behavior::Cruise: {
            if (dist < span * 0.5f) {
                float m = std::min(span * 0.6f, std::min(tank_.width, tank_.height) * 0.4f);
                c.target = Vec3(rng_.range(m, tank_.width - m), rng_.range(m, tank_.height - m), c.target.z);
            }
            // Beat and glide: a few slow flaps, then a long glide on spread wings.
            if (c.glideTimer > 0) {
                c.glideTimer -= h;
                flapWant = 0.05f;
            } else if (rng_.chance(0.12f * h)) {
                c.glideTimer = rng_.range(1.5f, 3.5f);
            }
            if (c.stateTimer <= 0) chooseRayBehavior(c);
            break;
        }
        case Behavior::Descend:
            targetSpeed = cruise * (0.3f + 0.6f * saturate(dist / (2.0f * L)));
            zWant = floorZ;
            flapWant = 0.25f;
            if ((dist < 0.5f * L && c.pos.z > floorZ - 2.5f) || c.stateTimer <= 0) {
                c.behavior = Behavior::Hide;
                c.stateTimer = rng_.range(18.0f, 40.0f);
                c.puffTimer = 0;
            }
            break;
        case Behavior::Hide:
            targetSpeed = 0;
            zWant = floorZ;
            steer = fwd;
            onSand = true;
            coverWant = 1;
            flapWant = c.cover < 0.98f ? 0.3f : 0.0f;  // the wing margins ripple to throw the sand
            c.puffTimer -= h;
            if (c.puffTimer <= 0 && c.pos.z > floorZ - 1.5f) {
                if (c.cover < 0.98f) {
                    // Sand thrown up over the disc from its edges.
                    c.puffTimer = rng_.range(0.08f, 0.16f);
                    float s = rng_.chance(0.5f) ? 1.0f : -1.0f;
                    Vec2 at = p + fwd * (L * rng_.range(-0.35f, 0.25f)) + side * (s * span * 0.5f * rng_.range(0.3f, 0.95f));
                    Vec2 out = normalize(at - p, side * s);
                    puff(CloudKind::Sand, Vec3(at.x, at.y, D - 1.5f), out * rng_.range(3.0f, 9.0f), rng_.range(2.5f, 5.0f),
                         rng_.range(0.45f, 0.7f), rng_.range(1.6f, 2.6f));
                } else {
                    // Buried, it still breathes through the spiracles behind its eyes.
                    c.puffTimer = rng_.range(4.0f, 9.0f);
                    float s = rng_.chance(0.5f) ? 1.0f : -1.0f;
                    Vec2 at = p + fwd * (0.25f * L) + side * (s * 0.08f * L);
                    puff(CloudKind::Sand, Vec3(at.x, at.y, D - 1.5f), Vec2(), 1.4f, 0.35f, 1.4f);
                }
            }
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Emerge;
                c.stateTimer = 6.0f;
                sandBurst(p, span * 0.42f, 10, 0.7f);
            }
            break;
        case Behavior::Emerge:
            targetSpeed = cruise * 0.5f;
            zWant = D - 0.3f * L;
            flapWant = 0.9f;
            if ((c.cover < 0.02f && c.pos.z < D - 4.0f) || c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                float m = std::min(span * 0.6f, std::min(tank_.width, tank_.height) * 0.4f);
                c.target = Vec3(rng_.range(m, tank_.width - m), rng_.range(m, tank_.height - m),
                                D * rng_.range(c.sp.depthMin, c.sp.depthMax));
                c.stateTimer = rng_.range(10.0f, 20.0f);
            }
            break;
        case Behavior::Forage:
            if (!feeding) {
                c.behavior = Behavior::Cruise;
                c.target.z = D * rng_.range(c.sp.depthMin, c.sp.depthMax);
                c.stateTimer = rng_.range(8.0f, 16.0f);
                break;
            }
            c.target = foodAt;
            toT = foodAt.xy() - mouth;
            steer = normalize(toT, fwd);
            targetSpeed = cruise * (0.25f + 0.75f * saturate(length(toT) / L));
            zWant = clamp(foodAt.z - 1.0f, 3.0f, D - 2.5f);
            flapWant = 0.45f;
            break;
        case Behavior::Flee:
            targetSpeed = c.sp.burstBl * L;
            steer = normalize(p - c.startleFrom, fwd);
            zWant = D * 0.4f;
            flapWant = 1.0f;
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                Vec2 ahead = p + fwd * (2.0f * L);
                float m = std::min(span * 0.6f, std::min(tank_.width, tank_.height) * 0.4f);
                c.target = Vec3(clamp(ahead.x, m, tank_.width - m), clamp(ahead.y, m, tank_.height - m),
                                D * rng_.range(c.sp.depthMin, c.sp.depthMax));
                c.stateTimer = rng_.range(8.0f, 14.0f);
            }
            break;
        default:
            c.behavior = Behavior::Cruise;
            break;
    }

    // Sand slides off fast, but takes a while to throw over.
    bool touching = c.pos.z > floorZ - 1.5f;
    if (coverWant > c.cover) {
        if (touching) c.cover = approach(c.cover, coverWant, h / 3.5f);
    } else {
        c.cover = approach(c.cover, coverWant, h / 0.8f);
    }

    // Wings.
    c.flapAmp = damp(c.flapAmp, flapWant, 2.5f, h);
    float rate = c.sp.tailBeatHz * (c.behavior == Behavior::Flee ? 2.0f : 1.0f) * (0.5f + 0.5f * c.flapAmp);
    if (onSand && c.cover < 0.98f) rate = 2.2f;  // quick ripples of the margins
    c.flapPhase = std::fmod(c.flapPhase + h * kTau * rate, kTau * 64.0f);
    float pulse = std::max(0.0f, std::sin(c.flapPhase)) * c.flapAmp;

    // Speed: the downstroke drives, the glide coasts on.
    if (onSand) c.speed = damp(c.speed, 0.0f, 3.0f, h);
    else if (targetSpeed > c.speed) c.speed += (targetSpeed - c.speed) * (0.3f + 2.2f * pulse) * h;
    else c.speed = damp(c.speed, targetSpeed, c.flapAmp < 0.1f ? 0.08f : 0.6f, h);
    if (c.glideTimer > 0 && c.speed < cruise * 0.6f) c.glideTimer = 0;

    // Turning, wide and smooth.
    if (!onSand) {
        steer += wallAvoidance(p, fwd, 1.2f * span, 0.7f * span) * 1.4f;
        steer += decorAvoidance(c, fwd, 1.2f * span, 0.35f * span) * 1.4f;
    }
    float maxTurn = c.sp.turnRate * (c.behavior == Behavior::Flee ? 1.8f : 1.0f) * (onSand ? 0.1f : 1.0f) *
                    (0.5f + 0.5f * saturate(c.speed / cruise));
    float before = c.heading;
    c.heading = approachAngle(c.heading, angleOf(steer), maxTurn * h);
    c.turnRate = damp(c.turnRate, wrapAngle(c.heading - before) / h, 3.0f, h);

    // Depth.
    float vzMax = 0.18f * L * (c.behavior == Behavior::Flee ? 1.6f : 1.0f);
    c.vz = damp(c.vz, clamp((zWant - c.pos.z) * 0.5f, -vzMax, vzMax), 1.5f, h);
    c.pos.z = std::max(3.0f, riseAbove(c, clamp(c.pos.z + c.vz * h, 3.0f, floorZ), h));

    Vec2 np = p + fromAngle(c.heading) * (c.speed * h);
    c.pos.x = np.x;
    c.pos.y = np.y;
    clampInside(c, span * 0.45f);
    keepOutOfDecor(c, 0.3f * span);

    if (c.behavior == Behavior::Forage) {
        float eatenBefore = c.eatenMg;
        const Vec2 m = c.pos.xy() + fromAngle(c.heading) * (0.4f * L);
        tryBite(c, m, c.pos.z + 1.5f, 0.14f * L);
        // Digging in the sand for it throws up a little cloud.
        if (c.eatenMg > eatenBefore && c.pos.z > D - 5.0f)
            puff(CloudKind::Sand, Vec3(m.x, m.y, D - 1.5f), fromAngle(c.heading) * 4.0f, 2.5f, 0.55f, 1.8f);
    }
    // Each downstroke pushes water back; low over the sand it lifts a little of it.
    if (pulse > 0.1f) {
        current_.push(p - fwd * (0.4f * L), fwd * (-pulse * 5.0f), 0.4f * span);
        if (c.pos.z > D - 6.0f && !onSand && rng_.chance(2.0f * pulse * h)) {
            float s = rng_.chance(0.5f) ? 1.0f : -1.0f;
            Vec2 at = p + side * (s * span * 0.4f);
            puff(CloudKind::Sand, Vec3(at.x, at.y, D - 1.5f), side * (s * 6.0f), 2.5f, 0.4f, 2.0f);
        }
    }

    // The tail trails behind, a whip on a lax chain.
    {
        const int N = Creature::kJoints;
        Vec2 f2 = fromAngle(c.heading);
        c.joint[0] = c.pos.xy() - f2 * (0.5f * L);
        for (int i = 1; i < N; i++) {
            Vec2 prevDir = i >= 2 ? normalize(c.joint[i - 2] - c.joint[i - 1], f2) : f2;
            Vec2 straight = c.joint[i - 1] - prevDir * c.segLen;
            Vec2 follow = c.joint[i - 1] + normalize(c.joint[i] - c.joint[i - 1], -prevDir) * c.segLen;
            float s = (float)i / (float)(N - 1);
            float stiff = 1.0f - std::exp(-(14.0f * (1.0f - s) + 2.0f) * h);
            Vec2 q = lerp(follow, straight, stiff);
            c.joint[i] = c.joint[i - 1] + normalize(q - c.joint[i - 1], -prevDir) * c.segLen;
        }
    }
}

// ---------------------------------------------------------------- octopus

void World::updateArms(Creature& c, float h, float spreadK, float curlK, float muscle, Vec2 grabAt) {
    // An arm is a chain of segments, each with its own angle on the table,
    // pulled toward a pose by a spring and slowed by a damper: stiff near the
    // body, soft toward the tip, so every movement runs down the arm as a
    // lagging wave. Positions are rebuilt from the angles: lengths are exact
    // and no kink can form.
    //  - On the sand each segment also follows where the joint ahead of it
    //    was, like a rope dragged: a moving body trails its arms behind it.
    //  - Walking, each arm sweeps forward and back in turn, neighbours out of
    //    step, curling a little on the backstroke and unrolling as it reaches.
    //  - Waves run from root to tip all the time, quicker when it moves.
    const float L = c.length;
    const float frontA = angleOf(octopusFront(c));
    const bool jetting = c.behavior == Behavior::Flee;
    const bool onSand = !jetting && c.pos.z > tank_.depth - 3.0f;
    const float cruise = std::max(c.sp.cruiseBl * L, 1.0f);
    const float walk = onSand ? smoothstep(0.02f, 0.8f, c.speed / cruise) : 0.0f;  // 0 still .. 1 walking
    const float open = 0.5f + 0.5f * std::cos(c.jetPhase);
    const float t = c.age;
    const bool still = c.behavior == Behavior::Hide;
    // The hold of the sand fades in as it settles, out as it lifts off; a jet streams the arms back.
    const float sandness = smoothstep(tank_.depth - 7.0f, tank_.depth - 2.0f, c.pos.z);
    c.armDrag = damp(c.armDrag, jetting ? 0.55f : lerp(0.3f, 0.75f, sandness), 3.0f, h);
    // Muscles do not switch from one pose to the next: they ease into it.
    c.armCurl = damp(c.armCurl, curlK, 2.0f, h);
    c.armSpread = damp(c.armSpread, spreadK, 2.0f, h);
    c.armMuscle = damp(c.armMuscle, muscle, 2.0f, h);
    c.armJet = damp(c.armJet, jetting ? 1.0f : 0.0f, jetting ? 8.0f : 2.5f, h);
    for (int k = 0; k < Creature::kArms; k++) {
        const float s = k < 4 ? -1.0f : 1.0f;
        const int i = k % 4;
        const Vec2 root = c.pos.xy() + fromAngle(frontA + s * kArmRoot[i]) * (0.06f * L);
        const float la = c.armLen[k];
        const bool grabbing = k == c.grabArm;
        Vec2* a = c.arm[k];
        float* ang = c.armAng[k];
        float* spin = c.armSpin[k];

        // The stroke: neighbours half a cycle apart, the two sides a quarter.
        const float phase = c.gaitPhase + (float)(i % 2) * kPi + (s > 0 ? 0.5f * kPi : 0.0f);
        const float stroke = std::sin(phase);  // +1 reaching forward .. -1 pulled back
        const float sweep = (i == 3 ? 0.22f : 0.42f) * walk;

        float base;
        float curl = c.armCurl * (0.6f + 0.4f * hash01(c.seed * 13u + (uint32_t)k));
        float waveAmp = still ? 0.25f : (c.behavior == Behavior::Rest ? 0.8f : 1.0f);
        float waveRate = 1.3f + 2.2f * walk;
        float len = la;
        if (grabbing) {
            Vec2 to = grabAt - root;
            base = angleOf(to);
            len = clamp(length(to), la * 0.3f, la * 1.05f);  // an arm can shorten, or stretch a little, to reach
            curl = 0;
            waveAmp = 0.1f;
        } else {
            float wander = (still ? 0.05f : 0.25f) * noise1(t * 0.4f + (float)k * 3.1f, c.seed + (uint32_t)k);
            base = frontA + s * (kArmSpread[i] * c.armSpread - sweep * stroke) + wander;
            curl += walk * 0.7f * (0.5f - 0.5f * stroke);  // curled in on the backstroke
            // Jetting, mantle first: the arms on the other side of the body, bundled, opening as it refills.
            float jetBase = frontA + s * (0.05f + 0.04f * (float)i) * (1.0f + 2.5f * open);
            base += wrapAngle(jetBase - base) * c.armJet;
            curl *= 1.0f - 0.8f * c.armJet;
            waveAmp = lerp(waveAmp, 0.35f, c.armJet);
        }
        // Segment length eases toward the length wanted (an arm reaching, then relaxing).
        c.armSeg[k] = damp(c.armSeg[k] > 0 ? c.armSeg[k] : la / (kAJ - 1), len / (kAJ - 1), 4.0f, h);
        const float seg = c.armSeg[k];

        // The pose, as an angle per segment.
        float want[kAJ - 1];
        float aw = base;
        for (int j = 0; j < kAJ - 1; j++) {
            float sj = (float)(j + 1) / (float)(kAJ - 1);
            float sj2 = sj * sj;
            float kappa = (-s * curl * (0.4f + 1.5f * sj2 + 4.0f * sj2 * sj2 * sj2) +
                           waveAmp * std::sin(t * waveRate + (float)k * 1.7f - sj * 4.5f) * 0.9f * sj) / la;
            if (j > 0) aw += kappa * (la / (kAJ - 1));
            want[j] = aw;
        }

        // Dragged: each segment turns to point from its joint, where it is now,
        // to where the next joint was. Strong on the sand, weak in the water.
        const float drag = c.armDrag;
        Vec2 at = root;
        for (int j = 0; j < kAJ - 1; j++) {
            float sj = (float)(j + 1) / (float)(kAJ - 1);
            Vec2 to = a[j + 1] - at;
            if (length2(to) > 1e-8f) {
                float follow = angleOf(to);
                ang[j] += wrapAngle(follow - ang[j]) * drag * (0.3f + 0.7f * sj);
            }
            at += fromAngle(ang[j]) * seg;
        }

        // Muscles: a damped spring per segment toward the pose, stiff at the root.
        const float k0 = c.armMuscle * (grabbing ? 3.0f : 1.0f);
        for (int j = 0; j < kAJ - 1; j++) {
            float sj = (float)j / (float)(kAJ - 2);
            float K = k0 * (1.0f - 0.7f * sj);
            float C = 2.0f * std::sqrt(K) * 0.85f;
            spin[j] += (K * wrapAngle(want[j] - ang[j]) - C * spin[j]) * h;
            spin[j] = clamp(spin[j], -12.0f, 12.0f);
            ang[j] += spin[j] * h;
        }

        // The joints, from the root out.
        a[0] = root;
        for (int j = 0; j < kAJ - 1; j++) a[j + 1] = a[j] + fromAngle(ang[j]) * seg;
    }
}

void World::stepOctopus(Creature& c, float h) {
    const float L = c.length, D = tank_.depth;
    const float cruise = c.sp.cruiseBl * L;
    const float sandZ = D - 1.5f;
    const Vec2 p = c.pos.xy();

    if (c.inkCooldown > 0) c.inkCooldown -= h;

    // Fright: a dark flash, a cloud of ink left behind, and a jet away.
    if (c.startleTimer > 0) {
        if (c.startleDelay > 0) c.startleDelay -= h;
        else {
            Vec2 away = normalize(p - c.startleFrom, fromAngle(c.heading));
            c.alarm = 1;
            c.cover = 0;
            if (c.inkCooldown <= 0) {
                // A dense core and wisps flung further out.
                for (int i = 0; i < 9; i++) {
                    Vec2 j(rng_.range(-3.0f, 3.0f), rng_.range(-3.0f, 3.0f));
                    bool wisp = i >= 4;
                    puff(CloudKind::Ink, Vec3(p.x + j.x, p.y + j.y, c.pos.z - rng_.range(0.5f, 3.0f)),
                         -away * rng_.range(6.0f, 16.0f) + j * (wisp ? 5.0f : 2.0f), rng_.range(3.0f, 5.5f) * (wisp ? 0.7f : 1.0f),
                         rng_.range(0.75f, 0.95f) * (wisp ? 0.7f : 1.0f), rng_.range(9.0f, 14.0f));
                }
                c.inkCooldown = 25.0f;
            }
            c.behavior = Behavior::Flee;
            c.stateTimer = rng_.range(1.6f, 2.2f);
            c.heading = angleOf(away);  // it aims its funnel and goes
            c.jetPhase = 0;
            c.startleTimer = 0;
            c.grabArm = -1;
        }
    }

    // Food: tasted with the suckers, taken with the arms.
    Vec3 foodAt;
    bool feeding = false;
    bool mayFeed = c.behavior == Behavior::Cruise || c.behavior == Behavior::Rest || c.behavior == Behavior::Forage ||
                   (c.behavior == Behavior::Hide && c.hunger > 0.6f);
    if (mayFeed) {
        feeding = forage(c, p, h, foodAt);
        if (feeding && c.behavior != Behavior::Forage) {
            c.behavior = Behavior::Forage;
            c.stateTimer = 30.0f;
        }
    } else {
        c.hunger = std::min(1.0f, c.hunger + c.sp.hungerPerS * h);
        if (c.biteCooldown > 0) c.biteCooldown -= h;
    }

    c.stateTimer -= h;
    float targetSpeed = 0, camoWant = 0.1f, spread = 1.0f, curl = 1.0f, rate = 50.0f, zWant = sandZ;
    Vec2 steer = fromAngle(c.heading);
    Vec2 toT = c.target.xy() - p;
    float dist = length(toT);
    Vec2 grabAt = p;
    auto newTarget = [&]() {
        float m = std::min(L * 0.9f, std::min(tank_.width, tank_.height) * 0.4f);
        Vec2 at = openSand(m, 0.6f * L);
        c.target = Vec3(at.x, at.y, sandZ);
        c.den = false;
    };

    switch (c.behavior) {
        case Behavior::Cruise:
            targetSpeed = cruise * (0.75f + 0.25f * noise1(c.age * 0.3f, c.seed));
            if (dist > 1e-3f) steer = toT / dist;
            if (dist < 0.3f * L) {
                c.behavior = Behavior::Rest;
                c.stateTimer = rng_.range(5.0f, 12.0f);
            }
            if (c.stateTimer <= 0) {
                if (rng_.chance(0.35f)) {
                    c.behavior = Behavior::Hide;
                    c.stateTimer = rng_.range(20.0f, 45.0f);
                    Vec2 den;
                    if ((c.den = denFor(c, den))) c.target = Vec3(den.x, den.y, sandZ);
                } else {
                    newTarget();
                    c.stateTimer = rng_.range(10.0f, 25.0f);
                }
            }
            break;
        case Behavior::Rest:
            camoWant = 0.25f;
            curl = 1.6f;
            rate = 40.0f;
            if (c.stateTimer <= 0) {
                if (rng_.chance(0.4f)) {
                    c.behavior = Behavior::Hide;
                    c.stateTimer = rng_.range(20.0f, 45.0f);
                    Vec2 den;
                    if ((c.den = denFor(c, den))) c.target = Vec3(den.x, den.y, sandZ);
                } else {
                    c.behavior = Behavior::Cruise;
                    newTarget();
                    c.stateTimer = rng_.range(10.0f, 25.0f);
                }
            }
            break;
        case Behavior::Hide:
            if (c.den && dist > 0.25f * L) {
                // First to its den against a coral head or a stone, fading as it goes.
                targetSpeed = cruise;
                steer = toT / dist;
                camoWant = 0.4f;
                break;
            }
            camoWant = 1;
            spread = 0.75f;
            curl = 2.4f;  // arms curled close around it
            rate = 60.0f;
            zWant = D - 1.0f;
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                newTarget();
                c.stateTimer = rng_.range(10.0f, 25.0f);
            }
            break;
        case Behavior::Forage: {
            if (!feeding) {
                c.behavior = Behavior::Rest;
                c.stateTimer = rng_.range(4.0f, 8.0f);
                c.grabArm = -1;
                break;
            }
            camoWant = 0.3f;
            Vec2 to = foodAt.xy() - p;
            float d = length(to);
            bool reachable = foodAt.z > D - 6.0f;  // on the sand or about to land
            if (d > 0.55f * L || !reachable) {
                // Crawl over to it (under it if it is still sinking) and wait.
                targetSpeed = d > 0.25f * L ? cruise * 1.3f : 0.0f;
                steer = normalize(to, steer);
                c.grabArm = -1;
            } else {
                // One arm goes for it: the one pointing closest.
                if (c.grabArm < 0) {
                    float best = -2;
                    for (int k = 0; k < Creature::kArms; k++) {
                        float sc = dot(normalize(c.arm[k][kAJ / 2] - p), normalize(to));
                        if (sc > best) {
                            best = sc;
                            c.grabArm = k;
                        }
                    }
                }
                grabAt = foodAt.xy();
                Vec2 tip = c.arm[c.grabArm][kAJ - 1];
                if (length(tip - grabAt) < 2.0f) tryBite(c, tip, foodAt.z, 1.5f);
            }
            break;
        }
        case Behavior::Flee: {
            // Jet: mantle first, arms trailing together, a push at every pulse.
            c.jetPhase += h * kTau * c.sp.tailBeatHz * 2.2f;
            float pulse = std::pow(std::max(0.0f, std::sin(c.jetPhase)), 2.0f);
            c.speed += pulse * c.sp.burstBl * L * 3.0f * h;
            c.speed -= c.speed * 1.6f * h;
            camoWant = 0;
            rate = 15.0f;
            zWant = D * 0.55f;
            if (pulse > 0.2f) current_.push(p - fromAngle(c.heading) * (0.3f * L), fromAngle(c.heading) * (-pulse * 10.0f), 6.0f);
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Descend;
                c.stateTimer = 8.0f;
            }
            break;
        }
        case Behavior::Descend:
            // Parachuting back down, arms spread wide.
            spread = 1.15f;
            curl = 0.3f;
            rate = 25.0f;
            camoWant = 0.4f;
            if (c.pos.z > D - 2.5f || c.stateTimer <= 0) {
                c.behavior = Behavior::Hide;
                c.stateTimer = rng_.range(15.0f, 30.0f);
                Vec2 den;
                if ((c.den = denFor(c, den))) c.target = Vec3(den.x, den.y, sandZ);
            }
            break;
        default:
            c.behavior = Behavior::Cruise;
            break;
    }

    // Colour: the alarm fades, the camouflage comes slowly and goes at once.
    if (c.behavior != Behavior::Flee) c.alarm = damp(c.alarm, 0.0f, 0.5f, h);
    c.cover = camoWant > c.cover ? approach(c.cover, camoWant, 0.35f * h) : damp(c.cover, camoWant, 3.0f, h);

    bool jetting = c.behavior == Behavior::Flee;
    if (!jetting) {
        if (targetSpeed > 0) {
            steer += wallAvoidance(p, steer, 0.8f * L, 0.7f * L) * 1.2f;
            steer += decorAvoidance(c, normalize(steer, fromAngle(c.heading)), 0.8f * L, 0.35f * L) * 1.2f;
        }
        float before = c.heading;
        if (targetSpeed > 0) c.heading = approachAngle(c.heading, angleOf(steer), c.sp.turnRate * h);
        c.turnRate = damp(c.turnRate, wrapAngle(c.heading - before) / h, 4.0f, h);
        c.speed = damp(c.speed, targetSpeed, 2.0f, h);
        // The mantle trails behind as it crawls.
        c.mantleAngle = approachAngle(c.mantleAngle, c.heading + kPi + 0.2f * noise1(c.age * 0.2f, c.seed + 3), 1.5f * h);
    } else {
        c.mantleAngle = approachAngle(c.mantleAngle, c.heading, 10.0f * h);
    }
    // About one stroke per arm length crawled.
    c.gaitPhase = std::fmod(c.gaitPhase + h * kTau * (0.08f + c.speed / (0.6f * L)), kTau * 64.0f);
    if (!jetting) c.jetPhase = std::fmod(c.jetPhase + h * kTau * 0.5f, kTau * 64.0f);  // breathing

    // Depth: on the sand unless it jets.
    float vzMax = jetting ? 0.5f * L : 0.15f * L;
    c.vz = damp(c.vz, clamp((zWant - c.pos.z) * (jetting ? 1.2f : 0.6f), -vzMax, vzMax), 2.0f, h);
    c.pos.z = clamp(c.pos.z + c.vz * h, 3.0f, D - 1.0f);

    Vec2 np = p + fromAngle(c.heading) * (c.speed * h);
    c.pos.x = np.x;
    c.pos.y = np.y;
    clampInside(c, 0.5f * L);
    keepOutOfDecor(c, 0.3f * L);
    if (c.behavior != Behavior::Forage) c.grabArm = -1;

    updateArms(c, h, spread, curl, rate, grabAt);
}

// ---------------------------------------------------------------- hermit crab

Vec2 World::crabLegRoot(const Creature& c, int leg) {
    const float L = c.length;
    const Vec2 f = fromAngle(c.heading), r = perp(f);
    const float s = leg < 2 ? -1.0f : 1.0f;
    const int pair = leg % 2;
    Vec2 b = c.pos.xy() + f * (0.36f * L);
    return b + f * (-0.04f * L - 0.08f * L * (float)pair) + r * (s * 0.1f * L);
}

Vec2 World::crabFootRest(const Creature& c, int leg) {
    const float L = c.length;
    const Vec2 f = fromAngle(c.heading), r = perp(f);
    const float s = leg < 2 ? -1.0f : 1.0f;
    const int pair = leg % 2;
    Vec2 b = c.pos.xy() + f * (0.36f * L);
    return b + f * (0.16f * L - 0.24f * L * (float)pair) + r * (s * (0.4f - 0.03f * (float)pair) * L);
}

float World::crabThreat(const Creature& c) const {
    float worst = 0;
    for (const Creature& o : creatures_) {
        if (o.id == c.id || o.loco == Locomotion::Walk) continue;
        if (o.loco == Locomotion::Swim && o.length < 2.5f * c.length) continue;
        if (o.loco == Locomotion::Glide && o.cover > 0.8f) continue;  // a buried ray is no danger
        if (o.loco == Locomotion::Crawl && o.cover > 0.9f) continue;  // and a camouflaged octopus is not seen
        if (std::fabs(o.pos.z - c.pos.z) > 20.0f) continue;
        float d = length(o.pos.xy() - c.pos.xy());
        float r = extentOf(o) * 0.6f + c.length;
        if (d < r) worst = std::max(worst, 1.0f - d / r);
    }
    return worst;
}

void World::stepLegs(Creature& c, float h, Vec2 travel) {
    const float L = c.length;
    const float stride = 0.22f * L;
    const float dur = 0.22f;
    const Vec2 dir = normalize(travel, Vec2());
    const bool moving = length(travel) > 0.05f * L;
    for (int leg = 0; leg < Creature::kLegs; leg++) {
        Vec2 rest = crabFootRest(c, leg);
        if (c.cover > 0.5f) {
            // Legs pulled into the shell.
            c.foot[leg] = lerp(c.foot[leg], crabLegRoot(c, leg), 1.0f - std::exp(-14.0f * h));
            c.swing[leg] = -1;
            continue;
        }
        if (c.swing[leg] >= 0) {
            c.swing[leg] += h / dur;
            Vec2 to = rest + dir * (stride * 0.4f);
            float t = smoothstep(0.0f, 1.0f, std::min(c.swing[leg], 1.0f));
            c.foot[leg] = lerp(c.footFrom[leg], to, t);
            if (c.swing[leg] >= 1) {
                c.swing[leg] = -1;
                c.foot[leg] = to;
            }
            continue;
        }
        // Planted: the foot stays where it is on the sand until it is too far behind.
        float off = length(c.foot[leg] - rest);
        float limit = moving ? stride * 0.5f : 0.12f * L;
        if (off <= limit) continue;
        bool otherSwinging = false;
        for (int k = 0; k < Creature::kLegs; k++)
            if (legGroup(k) != legGroup(leg) && c.swing[k] >= 0) otherSwinging = true;
        if (otherSwinging) continue;
        c.swing[leg] = 0;
        c.footFrom[leg] = c.foot[leg];
    }
}

void World::stepCrab(Creature& c, float h) {
    const float L = c.length, D = tank_.depth;
    const float cruise = c.sp.cruiseBl * L;
    const Vec2 p = c.pos.xy();
    const Vec2 fwd = fromAngle(c.heading);
    c.pos.z = std::min(D - crabLift(L), D - 1.0f);

    // A knock on the glass or something big passing: into the shell at once.
    if (c.startleTimer > 0) {
        if (c.startleDelay > 0) c.startleDelay -= h;
        else {
            if (c.behavior != Behavior::Hide) c.wobble = 1;
            c.behavior = Behavior::Hide;
            c.stateTimer = rng_.range(4.0f, 8.0f);
            c.startleTimer = 0;
            c.speed = 0;
        }
    }
    float threat = crabThreat(c);
    if (threat > 0) {
        if (c.behavior != Behavior::Hide) {
            c.behavior = Behavior::Hide;
            c.stateTimer = rng_.range(3.0f, 6.0f);
            c.wobble = 0.7f;
        }
        c.stateTimer = std::max(c.stateTimer, 2.5f);
        c.threat = 0;
    } else {
        c.threat += h;
    }

    // Food: a scavenger of whatever reaches the sand.
    const Vec2 mouth = p + fwd * (0.5f * L);
    Vec3 foodAt;
    bool feeding = false;
    bool mayFeed = c.behavior == Behavior::Cruise || c.behavior == Behavior::Rest || c.behavior == Behavior::Forage;
    if (mayFeed) {
        feeding = forage(c, mouth, h, foodAt);
        if (feeding && c.behavior != Behavior::Forage) {
            c.behavior = Behavior::Forage;
            c.stateTimer = 30.0f;
        }
    } else {
        c.hunger = std::min(1.0f, c.hunger + c.sp.hungerPerS * h);
        if (c.biteCooldown > 0) c.biteCooldown -= h;
    }

    c.stateTimer -= h;
    float targetSpeed = 0, coverWant = 0, pick = 0.5f;
    Vec2 toT = c.target.xy() - p;
    float dist = length(toT);
    Vec2 steer = dist > 1e-3f ? toT / dist : fwd;
    auto newTarget = [&]() {
        float m = std::min(L * 1.2f, std::min(tank_.width, tank_.height) * 0.4f);
        Vec2 at = openSand(m, 0.6f * L);
        c.target = Vec3(at.x, at.y, c.pos.z);
    };

    switch (c.behavior) {
        case Behavior::Cruise:
            targetSpeed = cruise;
            if (dist < 0.3f * L) {
                c.behavior = Behavior::Rest;
                c.stateTimer = rng_.range(2.0f, 6.0f);
            }
            if (c.stateTimer <= 0) {
                newTarget();
                c.stateTimer = rng_.range(8.0f, 20.0f);
            }
            break;
        case Behavior::Rest:
            pick = 1.6f;  // picking at the sand
            steer = fwd;
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                newTarget();
                c.stateTimer = rng_.range(8.0f, 20.0f);
            }
            break;
        case Behavior::Forage: {
            if (!feeding) {
                c.behavior = Behavior::Rest;
                c.stateTimer = rng_.range(2.0f, 5.0f);
                break;
            }
            Vec2 to = foodAt.xy() - mouth;
            float d = length(to);
            steer = normalize(foodAt.xy() - p, fwd);
            if (d > 0.2f * L) targetSpeed = cruise * 1.2f;
            pick = d < 0.5f * L ? 2.8f : 0.8f;
            if (foodAt.z > D - 4.0f) tryBite(c, mouth, D - 1.5f, 0.2f * L);
            break;
        }
        case Behavior::Hide:
            coverWant = 1;
            steer = fwd;
            if (c.stateTimer <= 0 && threat <= 0) {
                c.behavior = Behavior::Emerge;
                c.stateTimer = 2.6f;
            }
            break;
        case Behavior::Emerge:
            // The eyes first, a look around, then all of it.
            coverWant = c.stateTimer > 1.0f ? 0.6f : 0.0f;
            steer = fwd;
            if (c.stateTimer <= 0) {
                c.behavior = Behavior::Cruise;
                newTarget();
                c.stateTimer = rng_.range(6.0f, 14.0f);
            }
            break;
        default:
            c.behavior = Behavior::Cruise;
            break;
    }

    float coverRate = coverWant > c.cover ? 10.0f : (c.behavior == Behavior::Emerge ? 1.2f : 3.0f);
    c.cover = approach(c.cover, coverWant, coverRate * h);

    if (c.cover < 0.3f) {
        if (targetSpeed > 0) {
            steer += wallAvoidance(p, fwd, 0.6f * L, 0.8f * L) * 1.2f;
            steer += decorAvoidance(c, fwd, 0.8f * L, 0.45f * L) * 1.4f;
        }
        float before = c.heading;
        c.heading = approachAngle(c.heading, angleOf(steer), c.sp.turnRate * h);
        c.turnRate = damp(c.turnRate, wrapAngle(c.heading - before) / h, 6.0f, h);
        // A hermit crab walks forward: it turns before it goes.
        targetSpeed *= saturate(std::cos(wrapAngle(angleOf(steer) - c.heading)));
    } else {
        targetSpeed = 0;
        c.turnRate = damp(c.turnRate, 0.0f, 6.0f, h);
    }
    c.speed = approach(c.speed, targetSpeed, 3.0f * L * h);

    Vec2 np = p + fromAngle(c.heading) * (c.speed * h);
    c.pos.x = np.x;
    c.pos.y = np.y;
    clampInside(c, 0.6f * L);
    keepOutOfDecor(c, 0.45f * L);
    c.pos.z = std::min(D - crabLift(L), D - 1.0f);

    c.wobble = damp(c.wobble, 0.0f, 2.5f, h);
    c.gaitPhase = std::fmod(c.gaitPhase + h * kTau * pick, kTau * 64.0f);
    c.tailPhase = std::fmod(c.tailPhase + h * kTau * 0.7f, kTau * 64.0f);  // antennae
    stepLegs(c, h, fromAngle(c.heading) * c.speed + perp(fromAngle(c.heading)) * (c.turnRate * 0.3f * L));
}

}  // namespace dt
