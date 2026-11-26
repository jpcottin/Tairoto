#include "render/mesh.h"

#include <algorithm>

namespace dt {
namespace {

constexpr int kJ = Creature::kJoints;

struct Emitter {
    Mesh& m;
    float z, kind, seed;
    float aux[4] = {0, 0, 0, 0};

    uint32_t vert(Vec2 p, float u, float v, float frame) {
        m.vertices.push_back({p.x, p.y, u, v, z, kind, seed, frame, {aux[0], aux[1], aux[2], aux[3]}});
        return (uint32_t)m.vertices.size() - 1;
    }
    /** A vertex with its own depth and extras. */
    uint32_t vert(Vec2 p, float u, float v, float frame, float vz, float a0, float a1, float a2, float a3) {
        m.vertices.push_back({p.x, p.y, u, v, vz, kind, seed, frame, {a0, a1, a2, a3}});
        return (uint32_t)m.vertices.size() - 1;
    }
    void tri(uint32_t a, uint32_t b, uint32_t c) {
        m.indices.push_back(a);
        m.indices.push_back(b);
        m.indices.push_back(c);
    }
    void quad(uint32_t a, uint32_t b, uint32_t c, uint32_t d) {  // a-b on one row, d-c on the next
        tri(a, b, c);
        tri(a, c, d);
    }
};

float kindOf(Part part, Visual visual) { return (float)part + 16.0f * (float)(int)visual; }

/**
 * A ribbon along a centreline: rows of (left, centre, right) vertices, with v
 * running -1 .. 1 across and u along. The centre vertex keeps the shading of
 * a round body smooth.
 */
void ribbon(Emitter& e, const Vec2* center, const float* halfWidth, const float* u, const float* frame, int rows,
            const float* zs = nullptr) {
    uint32_t prev[3] = {0, 0, 0};
    for (int i = 0; i < rows; i++) {
        Vec2 side = perp(fromAngle(frame[i]));
        float zz = zs ? zs[i] : e.z;
        uint32_t row[3] = {
            e.vert(center[i] - side * halfWidth[i], u[i], -1.0f, frame[i], zz, e.aux[0], e.aux[1], e.aux[2], 1.0f),
            e.vert(center[i], u[i], 0.0f, frame[i], zz, e.aux[0], e.aux[1], e.aux[2], 0.0f),
            e.vert(center[i] + side * halfWidth[i], u[i], 1.0f, frame[i], zz, e.aux[0], e.aux[1], e.aux[2], 1.0f),
        };
        if (i > 0) {
            e.quad(prev[0], prev[1], row[1], row[0]);
            e.quad(prev[1], prev[2], row[2], row[1]);
        }
        std::copy(row, row + 3, prev);
    }
}

}  // namespace

float MeshBuilder::chromisProfile(float t) {
    t = saturate(t);
    // A blunt snout, the widest point a third of the way back, then a long
    // taper to a narrow caudal peduncle.
    if (t < 0.32f) return std::pow(std::sin(kPi * 0.5f * (t / 0.32f)), 0.55f) * 0.98f + 0.02f;
    return lerp(1.0f, 0.2f, smoothstep(0.32f, 1.0f, t));
}

float MeshBuilder::fishProfile(Visual v, float t) {
    float widest, snout, peduncle;
    switch (v) {
        case Visual::ConvictSurgeonfish: widest = 0.4f; snout = 0.55f; peduncle = 0.2f; break;   // oval, blunt
        case Visual::BluestripeSnapper: widest = 0.36f; snout = 0.75f; peduncle = 0.22f; break;  // a pointed head
        case Visual::YellowstripeGoatfish: widest = 0.32f; snout = 1.6f; peduncle = 0.2f; break;  // long sloping snout
        case Visual::ScissortailSergeant: widest = 0.35f; snout = 0.6f; peduncle = 0.22f; break;
        default: return chromisProfile(t);
    }
    t = saturate(t);
    if (t < widest) return std::pow(std::sin(kPi * 0.5f * (t / widest)), snout) * 0.97f + 0.03f;
    return lerp(1.0f, peduncle, smoothstep(widest, 1.0f, t));
}

void MeshBuilder::build(const World& world, const CameraParams& cam, Mesh& mesh) {
    mesh.clear();
    const auto& cs = world.creatures();
    const auto& fs = world.food();
    const float floorZ = world.tank().depth;
    order_.clear();
    auto visible = [&](Vec2 at, float z, float radiusMm) {
        // Visible if the thing or its shadow touches the screen.
        float r = radiusMm * cam.pxPerMm;
        Vec2 a = worldToScreen(cam, at, z);
        Vec2 b = worldToScreen(cam, at + Sun::shadowOffset(z, floorZ), floorZ);
        auto on = [&](Vec2 s) { return s.x > -r && s.y > -r && s.x < cam.outSize.x + r && s.y < cam.outSize.y + r; };
        return on(a) || on(b);
    };
    for (int i = 0; i < (int)cs.size(); i++)
        if (visible(cs[i].pos.xy(), cs[i].pos.z, World::extentOf(cs[i])))
            order_.push_back({World::topOf(cs[i]), ItemType::Creature, i, cs[i].id});
    for (int i = 0; i < (int)fs.size(); i++)
        if (visible(fs[i].pos.xy(), fs[i].pos.z, fs[i].radius() + 1.0f))
            order_.push_back({fs[i].pos.z, ItemType::Food, i, -(int64_t)fs[i].id});
    const auto& ks = world.clouds();
    for (int i = 0; i < (int)ks.size(); i++)
        if (visible(ks[i].pos.xy(), ks[i].pos.z, ks[i].radius * 1.3f))
            order_.push_back({ks[i].pos.z, ItemType::Cloud, i, ((int64_t)1 << 50) + (int64_t)ks[i].seed});
    const auto& ds = world.decor();
    for (int i = 0; i < (int)ds.size(); i++)
        if (visible(ds[i].pos, floorZ - ds[i].height, ds[i].radius * 1.35f))
            order_.push_back({floorZ - ds[i].height, ItemType::Decor, i, ((int64_t)1 << 52) + ds[i].id});
    // Painter's order: deepest first, so nearer things cover deeper ones.
    std::sort(order_.begin(), order_.end(), [](const Item& a, const Item& b) {
        if (a.z != b.z) return a.z > b.z;
        return a.key < b.key;
    });
    for (const Item& it : order_) {
        switch (it.type) {
            case ItemType::Creature: {
                const Creature& c = cs[it.index];
                switch (c.loco) {
                    case Locomotion::Paddle: turtle(c, mesh); break;
                    case Locomotion::Glide: ray(c, mesh, floorZ); break;
                    case Locomotion::Crawl: octopus(c, mesh); break;
                    case Locomotion::Walk: crab(c, mesh, floorZ); break;
                    default: fish(c, mesh); break;
                }
                mesh.creaturesDrawn++;
                break;
            }
            case ItemType::Food:
                food(fs[it.index], mesh);
                mesh.foodDrawn++;
                break;
            case ItemType::Cloud:
                cloud(ks[it.index], mesh);
                mesh.cloudsDrawn++;
                break;
            case ItemType::Decor:
                decor(ds[it.index], mesh, floorZ);
                mesh.decorDrawn++;
                break;
        }
    }
}

void MeshBuilder::food(const Food& f, Mesh& m) {
    // Freshness rides in the fraction of kind, like the shell's radius.
    const int part = f.kind == FoodKind::Flake ? kPartFlake : (f.kind == FoodKind::Pellet ? kPartPellet : kPartCrumb);
    const float kind = (float)part + 16.0f * kVisualFood + 0.499f * saturate(f.freshness);
    Emitter e{m, f.pos.z, kind, hash01(f.seed)};
    const float r = f.radius();
    const Vec2 c = f.pos.xy();
    // A flake tumbling as it sinks shows its edge now and then.
    float squash = f.kind == FoodKind::Pellet ? 1.0f : 0.3f + 0.7f * std::fabs(std::cos(f.tumble));
    if (f.state == FoodState::Floating || f.state == FoodState::Resting) squash = 1.0f;
    const int n = f.kind == FoodKind::Pellet ? 12 : (f.kind == FoodKind::Flake ? 7 : 5);
    Vec2 ax = fromAngle(f.spin), ay = perp(ax);
    uint32_t centre = e.vert(c, 0, 0, f.spin);
    uint32_t first = 0, prev = 0;
    for (int i = 0; i < n; i++) {
        float a = kTau * (float)i / (float)n;
        // Flakes and crumbs are irregular; pellets are round.
        float k = f.kind == FoodKind::Pellet ? 1.0f : 0.65f + 0.35f * hash01(f.seed * 31u + (uint32_t)i);
        float u = std::cos(a) * k, v = std::sin(a) * k;
        Vec2 p = c + ax * (u * r) + ay * (v * r * squash);
        uint32_t idx = e.vert(p, u, v, f.spin);
        if (i == 0) first = idx;
        else e.tri(centre, prev, idx);
        prev = idx;
    }
    e.tri(centre, prev, first);
}

void MeshBuilder::fish(const Creature& c, Mesh& m) {
    const float L = c.length;
    const float maxHalf = L * c.sp.bodyWidth * 0.5f;
    const float seed = hash01(c.seed);
    const float bodyLen = 0.78f;  // of the length; the caudal fin is the rest

    // The swimming wave rides on the spine: small at the head, large at the tail.
    Vec2 center[kJ];
    float half[kJ], u[kJ], frame[kJ];
    Vec2 tangent[kJ];
    for (int i = 0; i < kJ; i++) {
        Vec2 a = c.joint[i > 0 ? i - 1 : 0];
        Vec2 b = c.joint[i < kJ - 1 ? i + 1 : kJ - 1];
        tangent[i] = normalize(a - b, fromAngle(c.heading));
    }
    const float amp0 = L * (0.3f + 0.7f * c.thrust);
    for (int i = 0; i < kJ; i++) {
        float t = (float)i / (float)(kJ - 1);
        float amp = amp0 * (0.008f + 0.075f * t * t);
        float lateral = amp * std::sin(c.tailPhase - 0.95f * kTau * t);
        center[i] = c.joint[i] + perp(tangent[i]) * lateral;
        half[i] = maxHalf * fishProfile(c.visual, t);
        u[i] = t * bodyLen;
        frame[i] = angleOf(tangent[i]);
    }
    // Recompute the frame along the waved centreline so the body bends with it.
    for (int i = 0; i < kJ; i++) {
        Vec2 a = center[i > 0 ? i - 1 : 0];
        Vec2 b = center[i < kJ - 1 ? i + 1 : kJ - 1];
        frame[i] = angleOf(normalize(a - b, tangent[i]));
    }

    Emitter fins{m, c.pos.z, 0, seed};

    // Pectoral fins, under the body, sculling in turn.
    {
        fins.kind = kindOf(kPartPectoralFin, c.visual);
        int at = 3;
        Vec2 dir = fromAngle(frame[at]);
        for (int side = -1; side <= 1; side += 2) {
            float beat = std::sin(c.pectoralPhase + (side > 0 ? kPi : 0.0f));
            float spread = 0.95f + 0.38f * beat;  // from the body's side, swept back
            Vec2 out = rotate(perp(dir) * (float)side, (float)side * spread);
            Vec2 root = center[at] + perp(dir) * ((float)side * half[at] * 0.8f);
            const int rows = 3;
            Vec2 cl[rows];
            float hw[rows], uu[rows], fr[rows];
            float finLen = L * 0.17f;
            for (int r = 0; r < rows; r++) {
                float t = (float)r / (rows - 1);
                cl[r] = root + out * (finLen * t);
                hw[r] = L * 0.045f * (r == 0 ? 0.6f : (r == 1 ? 1.0f : 0.55f));
                uu[r] = t;
                fr[r] = angleOf(out);
            }
            ribbon(fins, cl, hw, uu, fr, rows);
        }
    }

    // Caudal fin, flexing behind the peduncle as a damped spring.
    {
        fins.kind = kindOf(kPartCaudalFin, c.visual);
        const int rows = 4;
        Vec2 cl[rows];
        float hw[rows], uu[rows], fr[rows];
        Vec2 p = center[kJ - 1];
        float ang = frame[kJ - 1] + kPi;  // pointing backward
        float finLen = L * 0.25f;
        for (int r = 0; r < rows; r++) {
            float t = (float)r / (rows - 1);
            if (r > 0) {
                ang += c.finAngle * 0.45f;
                p += fromAngle(ang) * (finLen / (rows - 1));
            }
            cl[r] = p;
            hw[r] = lerp(half[kJ - 1] * 0.9f, L * 0.15f, std::pow(t, 0.75f));
            uu[r] = t;
            fr[r] = ang + kPi;  // keep the frame pointing forward like the body
        }
        ribbon(fins, cl, hw, uu, fr, rows);
    }

    // A goatfish's two barbels under the chin: tucked back when it cruises,
    // forward and busy, sweeping the sand, when it looks for food.
    if (c.visual == Visual::YellowstripeGoatfish) {
        Emitter b{m, c.pos.z, kindOf(kPartBarbel, c.visual), seed};
        const float probing = c.foodTarget ? 1.0f : 0.25f;
        const Vec2 f0 = fromAngle(frame[0]);
        for (int side = -1; side <= 1; side += 2) {
            Vec2 root = center[1] + perp(f0) * ((float)side * half[1] * 0.35f);
            float sweep = 0.35f * std::sin(c.age * 9.0f + (float)side) * probing;
            Vec2 dir = rotate(f0, (float)side * (0.25f + sweep) - (1.0f - probing) * 0.0f);
            const int rows = 3;
            Vec2 cl[rows];
            float hw[rows], uu[rows], fr[rows];
            float len = L * (0.06f + 0.08f * probing);
            for (int r = 0; r < rows; r++) {
                float t = (float)r / (rows - 1);
                cl[r] = root + dir * (len * t);
                hw[r] = L * 0.009f * (1.0f - 0.6f * t);
                uu[r] = t;
                fr[r] = angleOf(dir);
            }
            ribbon(b, cl, hw, uu, fr, rows);
        }
    }

    // The body.
    Emitter body{m, c.pos.z, kindOf(kPartBody, c.visual), seed};
    ribbon(body, center, half, u, frame, kJ);

    // Dorsal fin: seen edge-on from above, a thin ridge of spines along the back.
    {
        Emitter dorsal{m, c.pos.z, kindOf(kPartDorsalFin, c.visual), seed};
        const int first = 3, last = kJ - 1;
        const int rows = last - first + 1;
        Vec2 cl[kJ];
        float hw[kJ], uu[kJ], fr[kJ];
        for (int r = 0; r < rows; r++) {
            int i = first + r;
            float t = (float)r / (rows - 1);
            cl[r] = center[i];
            hw[r] = maxHalf * 0.2f * std::sin(kPi * (0.08f + 0.84f * t));
            uu[r] = t;
            fr[r] = frame[i];
        }
        ribbon(dorsal, cl, hw, uu, fr, rows);
    }
}

void MeshBuilder::turtle(const Creature& c, Mesh& m) {
    const float L = c.length;
    const float seed = hash01(c.seed);
    const Vec2 f = fromAngle(c.heading);
    const Vec2 r = perp(f);
    const Vec2 C = c.pos.xy();
    const float a = 0.31f * L;   // shell half length
    const float b = 0.235f * L;  // shell half width
    const float breath = 1.0f + 0.012f * std::sin(c.breathPhase) * (0.4f + 0.6f * c.rest);
    const float swim = 1.0f - c.rest;
    const float phase = c.strokePhase;
    auto local = [&](float x, float y) { return C + f * x + r * y; };

    Emitter e{m, c.pos.z, 0, seed};

    // Rear flippers: short, rounded, steering.
    e.kind = kindOf(kPartFlipper, c.visual);
    for (int side = -1; side <= 1; side += 2) {
        float s = (float)side;
        Vec2 root = local(-a * 0.74f, s * b * 0.5f);
        float alpha = 1.05f + (0.22f * std::sin(phase + kPi) * swim) + s * c.turnRate * 0.5f;
        alpha = lerp(alpha, 1.35f, c.rest);
        const int rows = 4;
        Vec2 cl[rows];
        float hw[rows], uu[rows], fr[rows];
        float len = 0.17f * L;
        Vec2 p = root;
        for (int k = 0; k < rows; k++) {
            float t = (float)k / (rows - 1);
            Vec2 dir = rotate(r * s, s * (alpha + 0.12f * t));
            if (k > 0) p += dir * (len / (rows - 1));
            cl[k] = p;
            hw[k] = L * 0.05f * std::pow(std::sin(kPi * (0.22f + 0.72f * t)), 0.7f);
            uu[k] = t;
            fr[k] = angleOf(dir);
        }
        ribbon(e, cl, hw, uu, fr, rows);
    }

    // Front flippers: long curved paddles. Power stroke sweeps back with the
    // blade flat to the water, the recovery comes forward edge-on.
    for (int side = -1; side <= 1; side += 2) {
        float s = (float)side;
        Vec2 root = local(a * 0.42f, s * b * 0.8f);
        float turnAmp = clamp(1.0f + s * c.turnRate * 1.2f, 0.4f, 1.6f);
        float stroke = 0.35f - 0.82f * std::cos(phase) * turnAmp;
        float alpha = lerp(stroke, 1.3f + 0.04f * std::sin(c.breathPhase), c.rest);
        float feather = lerp(0.5f + 0.5f * std::fabs(std::sin(phase)), 1.0f, c.rest);
        float bend = (-0.22f * std::sin(phase - 0.7f)) * swim + 0.13f;
        const int rows = 6;
        Vec2 cl[rows];
        float hw[rows], uu[rows], fr[rows];
        float len = 0.44f * L;
        Vec2 p = root;
        for (int k = 0; k < rows; k++) {
            float t = (float)k / (rows - 1);
            Vec2 dir = rotate(r * s, s * (alpha + bend * t * 2.0f));
            if (k > 0) p += dir * (len / (rows - 1));
            cl[k] = p;
            float shape = lerp(0.5f, 1.0f, smoothstep(0.0f, 0.3f, t)) * (1.0f - 0.9f * smoothstep(0.5f, 1.0f, t));
            hw[k] = L * 0.072f * shape * feather + L * 0.004f;
            uu[k] = t;
            fr[k] = angleOf(dir);
        }
        ribbon(e, cl, hw, uu, fr, rows);
    }

    // Head on a short neck, swaying a little and reaching out at the surface.
    {
        e.kind = kindOf(kPartHead, c.visual);
        float surfacing = 1.0f - smoothstep(L * 0.04f, L * 0.15f, c.pos.z);
        float reach = 1.0f + 0.12f * surfacing - 0.18f * c.rest;
        const int rows = 6;
        Vec2 cl[rows];
        float hw[rows], uu[rows], fr[rows];
        float len = 0.21f * L * reach;
        Vec2 p = local(a * 0.86f, 0);
        for (int k = 0; k < rows; k++) {
            float t = (float)k / (rows - 1);
            Vec2 dir = rotate(f, c.headSway * (0.4f + 0.6f * t));
            if (k > 0) p += dir * (len / (rows - 1));
            cl[k] = p;
            hw[k] = L * 0.064f * std::pow(std::sin(kPi * lerp(0.3f, 0.97f, t)), 0.5f);
            uu[k] = t;
            fr[k] = angleOf(dir);
        }
        ribbon(e, cl, hw, uu, fr, rows);
    }

    // Shell: a polar grid on a heart-shaped outline. u, v are the shell's own
    // coordinates (-1..1 front to back, left to right) for the scute pattern.
    {
        // The ring's radius rides in the fraction of kind (shader: fract(kind) * 2),
        // so the outline can be antialiased whatever its shape.
        const float shellKind = kindOf(kPartShell, c.visual);
        e.kind = shellKind;
        const int rings = 7, segs = 36;
        float frame = c.heading;
        uint32_t centre = e.vert(C, 0, 0, frame);
        std::vector<uint32_t> prev(segs), cur(segs);
        for (int j = 1; j <= rings; j++) {
            float rho = (float)j / rings;
            e.kind = shellKind + 0.5f * rho;
            for (int k = 0; k < segs; k++) {
                float th = kTau * (float)k / segs;
                float cx = std::cos(th), sy = std::sin(th);
                float rear = std::max(0.0f, -cx);
                float x = cx * (cx < 0 ? 1.06f : 1.0f);
                float y = sy * (1.0f - 0.2f * rear * rear) * (1.0f + 0.05f * std::max(0.0f, cx));
                float ux = x * rho, vy = y * rho;
                cur[k] = e.vert(local(ux * a * breath, vy * b * breath), ux, vy, frame);
            }
            for (int k = 0; k < segs; k++) {
                int k2 = (k + 1) % segs;
                if (j == 1) e.tri(centre, cur[k], cur[k2]);
                else e.quad(prev[k], prev[k2], cur[k2], cur[k]);
            }
            prev.swap(cur);
        }
    }
}

// ---------------------------------------------------------------- bottom dwellers

namespace {

float kindOfV(int part, int visual) { return (float)part + 16.0f * (float)visual; }

/**
 * The eagle ray's outline: half its span (as a fraction of the semi-span) at
 * X, from -1 at the root of the tail to 1 at the tip of the snout. A rounded
 * head sticks out in front of the wings, the leading edges run straight out to
 * pointed tips swept back behind the middle, the trailing edges curve in to
 * small pelvic fins.
 */
float rayHalfSpan(float X) {
    const float headX = 0.62f, tipX = -0.12f, pelvicX = -0.72f;
    if (X >= headX) {
        float t = (X - headX) / (1.0f - headX);
        return 0.13f * std::sqrt(std::max(0.0f, 1.0f - t * t));
    }
    if (X >= tipX) return 0.13f + 0.87f * std::pow((headX - X) / (headX - tipX), 0.9f);
    if (X >= pelvicX) return 1.0f - 0.84f * std::pow((tipX - X) / (tipX - pelvicX), 0.6f);
    float t = (X - pelvicX) / (-1.0f - pelvicX);
    return 0.03f + 0.13f * std::sqrt(std::max(0.0f, 1.0f - t * t));
}

Vec2 catmull(Vec2 p0, Vec2 p1, Vec2 p2, Vec2 p3, float t) {
    float t2 = t * t, t3 = t2 * t;
    return (p1 * 2.0f + (p2 - p0) * t + (p0 * 2.0f - p1 * 5.0f + p2 * 4.0f - p3) * t2 + (p1 * 3.0f - p0 - p2 * 3.0f + p3) * t3) *
           0.5f;
}

/** Frames along a centreline, pointing back toward its start (like a fish's, toward the head). */
void framesOf(const Vec2* c, int n, float* frame, float fallback) {
    for (int i = 0; i < n; i++) {
        Vec2 a = c[i > 0 ? i - 1 : 0];
        Vec2 b = c[i < n - 1 ? i + 1 : n - 1];
        frame[i] = angleOf(normalize(a - b, fromAngle(fallback)));
    }
}

/** A disc as a fan: uv on the unit disc, aux[3] the distance from the centre. */
void disc(Emitter& e, Vec2 centre, float radius, float frame, int n, float z) {
    uint32_t mid = e.vert(centre, 0, 0, frame, z, e.aux[0], e.aux[1], e.aux[2], 0.0f);
    uint32_t first = 0, prev = 0;
    for (int i = 0; i < n; i++) {
        float a = kTau * (float)i / (float)n;
        Vec2 d = fromAngle(a);
        uint32_t idx = e.vert(centre + rotate(d, frame) * radius, d.x, d.y, frame, z, e.aux[0], e.aux[1], e.aux[2], 1.0f);
        if (i == 0) first = idx;
        else e.tri(mid, prev, idx);
        prev = idx;
    }
    e.tri(mid, prev, first);
}

}  // namespace

void MeshBuilder::ray(const Creature& c, Mesh& m, float floorZ) {
    const float L = c.length;
    const float semi = 0.5f * L * c.sp.bodyWidth;
    const float seed = hash01(c.seed);
    const Vec2 f = fromAngle(c.heading), r = perp(f);
    const Vec2 C = c.pos.xy();
    const float cover = saturate(c.cover);
    Emitter e{m, c.pos.z, kindOfV(kPartRayTail, (int)c.visual), seed};
    e.aux[0] = cover;

    // The tail first, under the disc: a long whip, thick at the root.
    {
        const int N = Creature::kJoints;
        Vec2 cl[N];
        float hw[N], uu[N], fr[N];
        for (int i = 0; i < N; i++) {
            float t = (float)i / (float)(N - 1);
            cl[i] = c.joint[i];
            hw[i] = L * (0.03f * std::pow(1.0f - t, 1.5f) + 0.0035f);
            uu[i] = t;
        }
        framesOf(cl, N, fr, c.heading);
        ribbon(e, cl, hw, uu, fr, N);
    }

    // The disc: a grid over the outline, the wings bending with each beat.
    static const float kRows[] = {1.0f, 0.97f, 0.92f, 0.85f, 0.76f, 0.67f, 0.62f, 0.53f, 0.42f, 0.3f, 0.18f, 0.07f,
                                  -0.03f, -0.12f, -0.2f, -0.3f, -0.42f, -0.54f, -0.64f, -0.72f, -0.8f, -0.9f, -1.0f};
    const int rows = (int)(sizeof(kRows) / sizeof(kRows[0]));
    const int cols = 17;
    // Close to the sand the wings beat shallower, so their tips never dig into it.
    const float clearance = std::max(floorZ - c.pos.z, 0.0f);
    const float amp = std::min(0.3f * semi * c.flapAmp, clearance * 0.9f + 0.4f) * (1.0f - cover);
    const float bank = clamp(c.turnRate * 0.5f, -0.3f, 0.3f) * (1.0f - cover);
    auto height = [&](float X, float Y) {
        float y = Y * rayHalfSpan(X) * semi;
        float ay = std::fabs(y) / semi;
        // The wave runs back across the wing, from the leading edge to the trailing edge.
        float wave = std::sin(c.flapPhase - 2.0f * (0.62f - X));
        return amp * std::pow(ay, 1.5f) * wave - bank * y;
    };
    e.kind = kindOfV(kPartRayDisc, (int)c.visual);
    const uint32_t base = (uint32_t)m.vertices.size();
    for (int i = 0; i < rows; i++) {
        float X = kRows[i];
        float W = rayHalfSpan(X);
        float x = X * 0.5f * L;
        for (int j = 0; j < cols; j++) {
            float Y = -1.0f + 2.0f * (float)j / (float)(cols - 1);
            float y = Y * W * semi;
            float h = height(X, Y);
            // A wing bent up or down covers less of the table: its width is the arc it spans.
            float yp = (y < 0 ? -1.0f : 1.0f) * std::sqrt(std::max(y * y - h * h, 0.3f * y * y));
            const float ex = 0.02f, ey = 0.04f;
            float hx = (height(std::min(X + ex, 1.0f), Y) - height(std::max(X - ex, -1.0f), Y)) /
                       ((std::min(X + ex, 1.0f) - std::max(X - ex, -1.0f)) * 0.5f * L);
            float dy = 2.0f * ey * std::max(W, 0.05f) * semi;
            float hy = (height(X, std::min(Y + ey, 1.0f)) - height(X, std::max(Y - ey, -1.0f))) / dy;
            float z = std::min(c.pos.z - h, floorZ + 0.5f);
            e.vert(C + f * x + r * yp, x / L, y / L, c.heading, std::max(z, 0.5f), cover, hx, hy, std::fabs(Y));
        }
    }
    for (int i = 0; i + 1 < rows; i++)
        for (int j = 0; j + 1 < cols; j++) {
            uint32_t a = base + (uint32_t)(i * cols + j), b = a + 1, d = a + (uint32_t)cols, cc = d + 1;
            e.quad(a, b, cc, d);
        }
}

void MeshBuilder::octopus(const Creature& c, Mesh& m) {
    const float L = c.length;
    const float seed = hash01(c.seed);
    const Vec2 C = c.pos.xy();
    const Vec2 md = fromAngle(c.mantleAngle);
    const Vec2 front = -md;
    const float frontA = angleOf(front);
    const float flat = saturate(c.cover);
    const bool jetting = c.behavior == Behavior::Flee;
    Emitter e{m, c.pos.z, kindOfV(kPartArm, (int)c.visual), seed};
    e.aux[0] = saturate(c.cover);
    e.aux[1] = saturate(c.alarm);

    // Arms: each chain smoothed into a tapering ribbon.
    constexpr int J = Creature::kArmJoints;
    constexpr int R = 2 * (J - 1) + 1;
    for (int k = 0; k < Creature::kArms; k++) {
        e.aux[2] = (float)k / 8.0f;
        const Vec2* p = c.arm[k];
        Vec2 cl[R];
        float hw[R], uu[R], fr[R];
        for (int j = 0; j < J - 1; j++) {
            Vec2 p0 = p[j > 0 ? j - 1 : 0], p1 = p[j], p2 = p[j + 1], p3 = p[j + 2 < J ? j + 2 : J - 1];
            cl[2 * j] = p1;
            cl[2 * j + 1] = catmull(p0, p1, p2, p3, 0.5f);
        }
        cl[R - 1] = p[J - 1];
        for (int i = 0; i < R; i++) {
            float t = (float)i / (float)(R - 1);
            hw[i] = L * (0.058f * std::pow(1.0f - t, 1.25f) + 0.004f) * (1.0f + 0.15f * flat);
            uu[i] = t;
        }
        framesOf(cl, R, fr, frontA);
        ribbon(e, cl, hw, uu, fr, R);
    }
    e.aux[2] = 0;

    // The web between the arms, around the mouth.
    {
        e.kind = kindOfV(kPartWeb, (int)c.visual);
        static const int kRing[8] = {0, 1, 2, 3, 7, 6, 5, 4};
        Vec2 right = perp(front);
        auto local = [&](Vec2 q) { Vec2 d = q - C; return Vec2(dot(d, front) / L, dot(d, right) / L); };
        uint32_t mid = e.vert(C, 0, 0, frontA, c.pos.z, e.aux[0], e.aux[1], 0, 0);
        uint32_t first = 0, prev = 0;
        for (int i = 0; i < 16; i++) {
            int a = kRing[(i / 2) % 8], b = kRing[(i / 2 + 1) % 8];
            Vec2 q;
            if (i % 2 == 0) q = c.arm[a][3];
            else q = lerp(C, (c.arm[a][3] + c.arm[b][3]) * 0.5f, 0.7f);  // the web dips between arms
            Vec2 uv = local(q);
            uint32_t idx = e.vert(q, uv.x, uv.y, frontA, c.pos.z, e.aux[0], e.aux[1], 0, 1.0f);
            if (i == 0) first = idx;
            else e.tri(mid, prev, idx);
            prev = idx;
        }
        e.tri(mid, prev, first);
    }

    // Head and mantle: a neck between the eyes, then the rounded sac.
    {
        e.kind = kindOfV(kPartMantle, (int)c.visual);
        const int N = 11;
        Vec2 cl[N];
        float hw[N], uu[N], fr[N];
        float pulse = jetting ? std::max(0.0f, std::sin(c.jetPhase)) : 0.0f;
        float breath = 1.0f + (jetting ? -0.22f * pulse : 0.06f * std::sin(c.jetPhase));
        float len = 0.32f * L * (jetting ? 1.12f : 1.0f) * (1.0f - 0.12f * flat);
        for (int i = 0; i < N; i++) {
            float s = (float)i / (float)(N - 1);
            cl[i] = C + md * (0.02f * L + len * s);
            float head = 0.78f * std::sqrt(std::max(0.0f, 1.0f - std::pow((s - 0.12f) / 0.2f, 2.0f)));
            float sac = std::sqrt(std::max(0.0f, 1.0f - std::pow((s - 0.62f) / 0.38f, 2.0f)));
            float profile = std::max(std::max(head, sac), 0.04f);
            hw[i] = 0.12f * L * profile * (s > 0.3f ? breath : 1.0f) * (1.0f + 0.18f * flat);
            uu[i] = s;
            fr[i] = frontA;
        }
        ribbon(e, cl, hw, uu, fr, N);
    }

    // Eyes, raised on the sides of the head.
    e.kind = kindOfV(kPartOctopusEye, (int)c.visual);
    for (int s = -1; s <= 1; s += 2) {
        Vec2 at = C + md * (0.075f * L) + perp(md) * ((float)s * 0.07f * L);
        disc(e, at, 0.03f * L, c.mantleAngle, 12, c.pos.z);
    }
}

void MeshBuilder::crab(const Creature& c, Mesh& m, float floorZ) {
    const float L = c.length;
    const float seed = hash01(c.seed);
    const Vec2 f = fromAngle(c.heading), r = perp(f);
    const Vec2 P = c.pos.xy();
    const float ext = 1.0f - saturate(c.cover);
    const float eyeExt = saturate(ext * 2.2f);
    const float limbExt = saturate((ext - 0.35f) / 0.65f);
    const Vec2 B = P + f * (0.36f * L + 0.1f * L * ext);  // where the crab comes out of the shell
    Emitter e{m, c.pos.z, kindOfV(kPartLeg, (int)c.visual), seed};
    e.aux[0] = saturate(c.cover);

    // Walking legs: two segments each, the knee bent up and out, the foot on the sand.
    if (limbExt > 0.02f) {
        const float a = 0.27f * L, b = 0.3f * L;
        for (int leg = 0; leg < Creature::kLegs; leg++) {
            Vec2 root = World::crabLegRoot(c, leg);
            Vec2 foot = c.foot[leg];
            Vec2 to = foot - root;
            // Two segments reach no closer than their difference and no further than their sum.
            float d = clamp(length(to), std::fabs(a - b) + 0.02f * L, (a + b) * 0.98f);
            Vec2 dir = normalize(to, r * (leg < 2 ? -1.0f : 1.0f));
            foot = root + dir * d;
            Vec2 out = perp(dir);
            if (dot(out, root - P) < 0) out = -out;
            float along = clamp((a * a - b * b + d * d) / (2.0f * d), -a, a);
            float hk = std::sqrt(std::max(a * a - along * along, 0.0f));
            Vec2 knee = root + dir * along + out * hk;
            float lift = c.swing[leg] >= 0 ? 1.5f * std::sin(kPi * saturate(c.swing[leg])) : 0.0f;
            Vec2 cl[5] = {root, lerp(root, knee, 0.5f), knee, lerp(knee, foot, 0.5f), foot};
            float hw[5] = {0.048f, 0.05f, 0.044f, 0.034f, 0.01f};
            float uu[5] = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
            float zz[5] = {c.pos.z - 0.5f, c.pos.z - 1.4f, c.pos.z - 2.0f, floorZ - 1.0f - lift, floorZ - 0.3f - lift};
            float fr[5];
            for (int i = 0; i < 5; i++) hw[i] *= L * (0.4f + 0.6f * limbExt);
            framesOf(cl, 5, fr, c.heading);
            ribbon(e, cl, hw, uu, fr, 5, zz);
        }
    }

    // The borrowed shell: a turban, spire at the back, opening at the front.
    {
        e.kind = kindOfV(kPartCrabShell, (int)c.visual);
        const float shellA = c.heading + c.wobble * 0.14f * std::sin(c.age * 19.0f);
        const Vec2 sf = fromAngle(shellA), sr = perp(sf);
        const Vec2 S = P - f * (0.02f * L);
        const float ha = 0.5f * L, hb = 0.5f * L * c.sp.bodyWidth;
        const int rings = 7, segs = 32;
        float zTop = c.pos.z - 0.25f * L;
        uint32_t centre = e.vert(S, 0, 0, shellA, zTop, e.aux[0], c.wobble, 0, 0);
        std::vector<uint32_t> prev(segs), cur(segs);
        for (int j = 1; j <= rings; j++) {
            float rho = (float)j / rings;
            float z = c.pos.z - 0.25f * L * (1.0f - rho * rho);
            for (int k = 0; k < segs; k++) {
                float th = kTau * (float)k / segs;
                float cx = std::cos(th), sy = std::sin(th);
                float rear = std::max(0.0f, -cx);
                float x = cx * (cx > 0 ? 0.92f : 1.0f) * (1.0f + 0.12f * rear * rear);
                float y = sy * (1.0f - 0.3f * rear * rear);
                float ux = x * rho, vy = y * rho;
                cur[k] = e.vert(S + sf * (ux * ha) + sr * (vy * hb), ux, vy, shellA, z, e.aux[0], c.wobble, 0, rho);
            }
            for (int k = 0; k < segs; k++) {
                int k2 = (k + 1) % segs;
                if (j == 1) e.tri(centre, cur[k], cur[k2]);
                else e.quad(prev[k], prev[k2], cur[k2], cur[k]);
            }
            prev.swap(cur);
        }
    }

    if (ext < 0.03f) return;

    // The front of the crab, out of the opening.
    {
        e.kind = kindOfV(kPartCrabBody, (int)c.visual);
        Vec2 cl[4];
        float hw[4] = {0.9f, 1.0f, 0.85f, 0.5f}, uu[4], fr[4], zz[4];
        for (int i = 0; i < 4; i++) {
            float t = (float)i / 3.0f;
            cl[i] = lerp(P + f * (0.34f * L), B, t);
            hw[i] *= 0.12f * L * (0.4f + 0.6f * ext);
            uu[i] = t;
            fr[i] = c.heading;
            zz[i] = c.pos.z - 1.5f;
        }
        ribbon(e, cl, hw, uu, fr, 4, zz);
    }

    // Claws: the left one bigger (Dardanus are left-handed), picking at the sand in turn.
    if (limbExt > 0.02f) {
        e.kind = kindOfV(kPartClaw, (int)c.visual);
        bool picking = c.behavior == Behavior::Rest || c.behavior == Behavior::Forage;
        for (int s = -1; s <= 1; s += 2) {
            float size = s < 0 ? 1.25f : 0.9f;
            float reach = picking ? 0.5f + 0.5f * std::sin(c.gaitPhase + (s > 0 ? kPi : 0.0f)) : 0.15f;
            Vec2 base = B + r * ((float)s * 0.06f * L) - f * (0.02f * L);
            Vec2 dir = rotate(f, -(float)s * (0.6f - 0.4f * reach));
            float len = 0.3f * L * size * limbExt * (0.85f + 0.25f * reach);
            const int N = 7;
            static const float kProfile[N] = {0.035f, 0.045f, 0.075f, 0.09f, 0.08f, 0.05f, 0.015f};
            Vec2 cl[N];
            float hw[N], uu[N], fr[N], zz[N];
            Vec2 p = base;
            for (int i = 0; i < N; i++) {
                float t = (float)i / (float)(N - 1);
                if (i > 0) p += rotate(dir, (float)s * 0.25f * t) * (len / (N - 1));
                cl[i] = p;
                hw[i] = kProfile[i] * L * size * (0.4f + 0.6f * limbExt);
                uu[i] = t;
                zz[i] = c.pos.z - 1.8f + 1.4f * reach * t;
            }
            framesOf(cl, N, fr, c.heading);
            ribbon(e, cl, hw, uu, fr, N, zz);
        }
    }

    // Eyes on stalks: the first thing out.
    {
        e.kind = kindOfV(kPartEyeStalk, (int)c.visual);
        for (int s = -1; s <= 1; s += 2) {
            Vec2 base = B + r * ((float)s * 0.025f * L);
            Vec2 dir = rotate(f, (float)s * (0.22f + 0.08f * std::sin(c.tailPhase * 0.7f + (float)s)));
            const int N = 5;
            static const float kProfile[N] = {0.016f, 0.016f, 0.02f, 0.03f, 0.008f};
            Vec2 cl[N];
            float hw[N], uu[N], fr[N], zz[N];
            for (int i = 0; i < N; i++) {
                float t = (float)i / (float)(N - 1);
                cl[i] = base + dir * (0.17f * L * eyeExt * t);
                hw[i] = kProfile[i] * L * (0.5f + 0.5f * eyeExt);
                uu[i] = t;
                zz[i] = c.pos.z - 2.0f;
            }
            framesOf(cl, N, fr, c.heading);
            ribbon(e, cl, hw, uu, fr, N, zz);
        }
    }

    // Long antennae sweeping the water.
    if (limbExt > 0.05f) {
        e.kind = kindOfV(kPartAntenna, (int)c.visual);
        for (int s = -1; s <= 1; s += 2) {
            Vec2 p = B + f * (0.06f * L) + r * ((float)s * 0.015f * L);
            float ang = c.heading + (float)s * (0.3f + 0.2f * std::sin(c.tailPhase + (float)s * 1.3f));
            const int N = 9;
            Vec2 cl[N];
            float hw[N], uu[N], fr[N], zz[N];
            float len = 0.85f * L * limbExt;
            for (int i = 0; i < N; i++) {
                float t = (float)i / (float)(N - 1);
                if (i > 0) {
                    ang += (float)s * 0.1f + 0.12f * std::sin(c.tailPhase * 1.7f + (float)i * 0.6f + (float)s);
                    p += fromAngle(ang) * (len / (N - 1));
                }
                cl[i] = p;
                hw[i] = std::max(0.009f * L * (1.0f - 0.6f * t), 0.07f);
                uu[i] = t;
                zz[i] = c.pos.z - 2.0f;
            }
            framesOf(cl, N, fr, c.heading);
            ribbon(e, cl, hw, uu, fr, N, zz);
        }
    }
}

void MeshBuilder::cloud(const Cloud& k, Mesh& m) {
    int part = k.kind == CloudKind::Ink ? kPartInkCloud : kPartSandCloud;
    Emitter e{m, k.pos.z, kindOfV(part, kVisualCloud), hash01(k.seed)};
    e.aux[0] = saturate(k.density * k.fade());
    e.aux[1] = saturate(k.age / k.life);
    // Drawn a little larger than the cloud: the shader frays its edge with noise.
    disc(e, k.pos.xy(), k.radius * 1.3f, k.spin, 16, k.pos.z);
}

// ---------------------------------------------------------------- decorations

void MeshBuilder::decor(const Decor& d, Mesh& m, float floorZ) {
    switch (d.kind) {
        case DecorKind::CoralHead: dome(d, m, floorZ, 12, 56); break;
        case DecorKind::Stone: dome(d, m, floorZ, 9, 44); break;
        case DecorKind::GiantClam: dome(d, m, floorZ, 7, 40); break;
        case DecorKind::BranchingCoral: branches(d, m, floorZ); break;
    }
}

/**
 * A dome on its own outline: rings out from the top, uv on the unit disc in
 * the decoration's frame, the surface slope in aux for the light, aux[3] the
 * distance to the outline.
 */
void MeshBuilder::dome(const Decor& d, Mesh& m, float floorZ, int rings, int segs) {
    Emitter e{m, floorZ - d.height, kindOfV(kPartDecorBody, kVisualDecor + (int)d.kind), hash01(d.seed)};
    const float open = d.kind == DecorKind::GiantClam ? d.open : 0.0f;
    auto at = [&](float rho, float th) {
        return d.pos + rotate(fromAngle(th) * (d.radiusAt(th) * rho), d.angle);
    };
    auto vertex = [&](float rho, float th) {
        Vec2 p = at(rho, th);
        // The slope of the surface, from its height a little either side (world axes).
        const float eps = std::max(0.15f, d.radius * 0.02f);
        float r1 = std::min(rho, 0.985f);
        Vec2 q = at(r1, th);
        float hx = (d.heightAt(q + Vec2(eps, 0)) - d.heightAt(q - Vec2(eps, 0))) / (2 * eps);
        float hy = (d.heightAt(q + Vec2(0, eps)) - d.heightAt(q - Vec2(0, eps))) / (2 * eps);
        hx = clamp(hx, -6.0f, 6.0f);
        hy = clamp(hy, -6.0f, 6.0f);
        float z = floorZ - d.heightAtRho(std::min(rho, 0.999f));
        Vec2 uv = fromAngle(th) * rho;
        return e.vert(p, uv.x, uv.y, d.angle, z, hx, hy, open, rho);
    };
    uint32_t top = vertex(0.0f, 0.0f);
    std::vector<uint32_t> prev(segs), cur(segs);
    for (int j = 1; j <= rings; j++) {
        // Rings closer together toward the edge, where the surface curves fastest.
        float t = (float)j / (float)rings;
        float rho = 1.0f - (1.0f - t) * (1.0f - t);
        for (int k = 0; k < segs; k++) cur[k] = vertex(rho, kTau * (float)k / (float)segs);
        for (int k = 0; k < segs; k++) {
            int k2 = (k + 1) % segs;
            if (j == 1) e.tri(top, cur[k], cur[k2]);
            else e.quad(prev[k], prev[k2], cur[k2], cur[k]);
        }
        std::swap(prev, cur);
    }
}

/**
 * Branching coral seen from above: branches fanning out and up from the
 * middle, each forking once or twice, the tips highest; and short ones growing
 * straight up, seen end on. Deepest drawn first, so the tips cover the bases.
 */
void MeshBuilder::branches(const Decor& d, Mesh& m, float floorZ) {
    struct Branch {
        Vec2 pts[4];
        float z[4];
        float w0, w1;
        float tipZ;
    };
    struct Stub {
        Vec2 at;
        float z, r;
    };
    std::vector<Branch> bs;
    std::vector<Stub> stubs;
    uint32_t s = d.seed * 2654435761u + 0x9E3779B9u;
    auto rnd = [&]() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return (float)(s & 0xFFFFFF) / (float)0x1000000;
    };
    const float R = d.radius, H = d.height;
    auto world = [&](Vec2 local) { return d.pos + rotate(local, d.angle); };
    const int mains = 9 + (int)(rnd() * 4.0f);
    for (int i = 0; i < mains; i++) {
        float th = kTau * ((float)i + rnd() * 0.6f) / (float)mains;
        float len = R * (0.62f + 0.36f * rnd());
        float bend = (rnd() - 0.5f) * 0.5f;
        float tipH = H * (0.72f + 0.28f * rnd());
        Branch b;
        for (int k = 0; k < 4; k++) {
            float t = (float)k / 3.0f;
            float r = len * (0.08f + 0.92f * t);
            float a = th + bend * t * t;
            b.pts[k] = world(fromAngle(a) * std::min(r, d.radiusAt(a) * 0.98f));
            b.z[k] = floorZ - H * 0.2f - (tipH - H * 0.2f) * std::sqrt(t);
        }
        b.w0 = R * 0.085f;
        b.w1 = R * 0.05f;
        b.tipZ = b.z[3];
        bs.push_back(b);
        // One or two forks off it, reaching higher still.
        int forks = 1 + (rnd() < 0.45f ? 1 : 0);
        for (int f = 0; f < forks; f++) {
            float t0 = 0.35f + 0.3f * rnd();
            float side = (f == 0 ? 1.0f : -1.0f) * (rnd() < 0.5f ? 1.0f : -1.0f);
            float a0 = th + bend * t0 * t0;
            float r0 = len * (0.08f + 0.92f * t0);
            Vec2 base = fromAngle(a0) * r0;
            float fa = a0 + side * (0.45f + 0.35f * rnd());
            float flen = len * (0.3f + 0.25f * rnd());
            float baseZ = floorZ - H * 0.2f - (tipH - H * 0.2f) * std::sqrt(t0);
            float fTip = floorZ - std::min(H, tipH + H * 0.12f * rnd());
            Branch c;
            for (int k = 0; k < 4; k++) {
                float t = (float)k / 3.0f;
                Vec2 local = base + fromAngle(fa) * (flen * t);
                float la = std::atan2(local.y, local.x);
                float lr = std::min(length(local), d.radiusAt(la) * 0.98f);
                c.pts[k] = world(fromAngle(la) * lr);
                c.z[k] = lerp(baseZ, fTip, std::sqrt(t));
            }
            c.w0 = R * 0.06f;
            c.w1 = R * 0.04f;
            c.tipZ = c.z[3];
            bs.push_back(c);
        }
    }
    // Upright branches near the middle, seen end on as round tips.
    int ups = 5 + (int)(rnd() * 5.0f);
    for (int i = 0; i < ups; i++) {
        float a = rnd() * kTau;
        float r = R * 0.45f * std::sqrt(rnd());
        stubs.push_back({world(fromAngle(a) * r), floorZ - H * (0.85f + 0.15f * rnd()), R * (0.04f + 0.015f * rnd())});
    }
    std::sort(bs.begin(), bs.end(), [](const Branch& a, const Branch& b) { return a.tipZ > b.tipZ; });

    Emitter e{m, floorZ - H, kindOfV(kPartCoralBranch, kVisualDecor + (int)d.kind), hash01(d.seed)};
    // The old, shaded heart of the thicket the branches grow out of.
    e.kind = kindOfV(kPartCoralTip, kVisualDecor + (int)d.kind);
    e.aux[0] = 1;  // a base, not a tip
    e.aux[1] = R * 0.2f;
    disc(e, d.pos, R * 0.2f, d.angle, 18, floorZ - H * 0.22f);
    e.aux[0] = 0;
    e.kind = kindOfV(kPartCoralBranch, kVisualDecor + (int)d.kind);
    const int rows = 8;
    for (const Branch& b : bs) {
        Vec2 c[rows];
        float hw[rows], uu[rows], fr[rows], zz[rows];
        float along = 0;
        for (int i = 0; i < rows; i++) {
            float t = (float)i / (float)(rows - 1) * 3.0f;
            int k = std::min((int)t, 2);
            float f = t - (float)k;
            Vec2 p0 = b.pts[std::max(k - 1, 0)], p1 = b.pts[k], p2 = b.pts[k + 1], p3 = b.pts[std::min(k + 2, 3)];
            c[i] = catmull(p0, p1, p2, p3, f);
            zz[i] = lerp(b.z[k], b.z[k + 1], f);
            float tt = (float)i / (float)(rows - 1);
            // Tapering, and rounded off at the tip.
            float round = tt > 0.85f ? std::sqrt(std::max(0.0f, 1.0f - std::pow((tt - 0.85f) / 0.15f, 2.0f))) : 1.0f;
            hw[i] = lerp(b.w0, b.w1, tt) * std::max(round, 0.25f);
            uu[i] = tt;
            if (i > 0) along += length(c[i] - c[i - 1]);
        }
        framesOf(c, rows, fr, d.angle);
        // aux: length so far along the branch (mm) and its half width (mm), for round corallites.
        uint32_t prev[3] = {0, 0, 0};
        float soFar = 0;
        for (int i = 0; i < rows; i++) {
            if (i > 0) soFar += length(c[i] - c[i - 1]);
            Vec2 side = perp(fromAngle(fr[i]));
            uint32_t row[3] = {
                e.vert(c[i] - side * hw[i], uu[i], -1.0f, fr[i], zz[i], soFar, hw[i], 0, 1.0f),
                e.vert(c[i], uu[i], 0.0f, fr[i], zz[i], soFar, hw[i], 0, 0.0f),
                e.vert(c[i] + side * hw[i], uu[i], 1.0f, fr[i], zz[i], soFar, hw[i], 0, 1.0f),
            };
            if (i > 0) {
                e.quad(prev[0], prev[1], row[1], row[0]);
                e.quad(prev[1], prev[2], row[2], row[1]);
            }
            std::copy(row, row + 3, prev);
        }
        (void)along;
    }
    e.kind = kindOfV(kPartCoralTip, kVisualDecor + (int)d.kind);
    std::sort(stubs.begin(), stubs.end(), [](const Stub& a, const Stub& b) { return a.z > b.z; });
    for (const Stub& st : stubs) {
        e.aux[0] = 0;
        e.aux[1] = st.r;
        e.aux[2] = 0;
        disc(e, st.at, st.r, d.angle, 14, st.z);
    }
}

}  // namespace dt
