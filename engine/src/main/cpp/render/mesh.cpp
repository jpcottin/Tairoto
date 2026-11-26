#include "render/mesh.h"

#include <algorithm>

namespace dt {
namespace {

constexpr int kJ = Creature::kJoints;

struct Emitter {
    Mesh& m;
    float z, kind, seed;

    uint32_t vert(Vec2 p, float u, float v, float frame) {
        m.vertices.push_back({p.x, p.y, u, v, z, kind, seed, frame});
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
void ribbon(Emitter& e, const Vec2* center, const float* halfWidth, const float* u, const float* frame, int rows) {
    uint32_t prev[3] = {0, 0, 0};
    for (int i = 0; i < rows; i++) {
        Vec2 side = perp(fromAngle(frame[i]));
        uint32_t row[3] = {
            e.vert(center[i] - side * halfWidth[i], u[i], -1.0f, frame[i]),
            e.vert(center[i], u[i], 0.0f, frame[i]),
            e.vert(center[i] + side * halfWidth[i], u[i], 1.0f, frame[i]),
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

void MeshBuilder::build(const World& world, const CameraParams& cam, Mesh& mesh) {
    mesh.clear();
    const auto& cs = world.creatures();
    const float floorZ = world.tank().depth;
    order_.clear();
    for (int i = 0; i < (int)cs.size(); i++) {
        const Creature& c = cs[i];
        // Visible if the creature or its shadow touches the screen.
        float r = c.length * 0.75f * cam.pxPerMm;
        Vec2 a = worldToScreen(cam, c.pos.xy(), c.pos.z);
        Vec2 b = worldToScreen(cam, c.pos.xy() + Sun::shadowOffset(c.pos.z, floorZ), floorZ);
        auto on = [&](Vec2 s) {
            return s.x > -r && s.y > -r && s.x < cam.outSize.x + r && s.y < cam.outSize.y + r;
        };
        if (on(a) || on(b)) order_.push_back(i);
    }
    // Painter's order: deepest first, so nearer creatures cover deeper ones.
    std::sort(order_.begin(), order_.end(), [&](int x, int y) {
        if (cs[x].pos.z != cs[y].pos.z) return cs[x].pos.z > cs[y].pos.z;
        return cs[x].id < cs[y].id;
    });
    for (int i : order_) {
        const Creature& c = cs[i];
        if (c.loco == Locomotion::Paddle) turtle(c, mesh);
        else fish(c, mesh);
        mesh.creaturesDrawn++;
    }
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
        half[i] = maxHalf * chromisProfile(t);
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

}  // namespace dt
