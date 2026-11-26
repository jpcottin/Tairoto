#include "sim/current.h"

#include <algorithm>

namespace dt {

void CurrentField::resize(float width, float height) {
    width_ = width;
    height_ = height;
    nx_ = std::max(2, (int)std::ceil(width / kCell) + 1);
    ny_ = std::max(2, (int)std::ceil(height / kCell) + 1);
    v_.assign((size_t)nx_ * ny_, Vec2());
    tmp_ = v_;
}

Vec2 CurrentField::sample(const std::vector<Vec2>& f, Vec2 p) const {
    if (nx_ == 0) return {};
    float gx = clamp(p.x / kCell, 0.0f, (float)(nx_ - 1) - 1e-3f);
    float gy = clamp(p.y / kCell, 0.0f, (float)(ny_ - 1) - 1e-3f);
    int x = (int)gx, y = (int)gy;
    float fx = gx - x, fy = gy - y;
    auto c = [&](int i, int j) { return f[(size_t)j * nx_ + i]; };
    Vec2 a = lerp(c(x, y), c(x + 1, y), fx);
    Vec2 b = lerp(c(x, y + 1), c(x + 1, y + 1), fx);
    return lerp(a, b, fy);
}

Vec2 CurrentField::gyre(Vec2 p) const {
    // A slow loop round the middle of the tank, its centre wandering a little.
    Vec2 c(width_ * (0.5f + 0.12f * std::sin(time_ * 0.013f)), height_ * (0.5f + 0.1f * std::sin(time_ * 0.017f + 1.0f)));
    Vec2 d = p - c;
    float r = length(d);
    float reach = 0.5f * std::min(width_, height_);
    float speed = 1.4f * smoothstep(0.0f, reach * 0.4f, r) * (1.0f - smoothstep(reach, reach * 1.6f, r) * 0.6f);
    return normalize(perp(d), Vec2(1, 0)) * speed;
}

Vec2 CurrentField::stirredAt(Vec2 p) const { return sample(v_, p); }

Vec2 CurrentField::at(Vec2 p) const { return gyre(p) + sample(v_, p); }

void CurrentField::push(Vec2 p, Vec2 dv, float radius) {
    if (nx_ == 0) return;
    radius = std::max(radius, kCell * 0.5f);
    int x0 = std::max(0, (int)((p.x - radius) / kCell)), x1 = std::min(nx_ - 1, (int)((p.x + radius) / kCell) + 1);
    int y0 = std::max(0, (int)((p.y - radius) / kCell)), y1 = std::min(ny_ - 1, (int)((p.y + radius) / kCell) + 1);
    for (int y = y0; y <= y1; y++)
        for (int x = x0; x <= x1; x++) {
            float d = length(Vec2(x * kCell, y * kCell) - p);
            if (d > radius) continue;
            float w = 1.0f - d / radius;
            v_[(size_t)y * nx_ + x] += dv * (w * w);
        }
}

void CurrentField::step(float dt) {
    if (nx_ == 0 || !(dt > 0)) return;
    time_ += dt;
    // Carry the stirred water along itself (semi-Lagrangian), blur it a little, let it die down.
    const float keep = std::exp(-0.55f * dt);
    const float blur = 1.0f - std::exp(-6.0f * dt);
    for (int y = 0; y < ny_; y++) {
        for (int x = 0; x < nx_; x++) {
            Vec2 p(x * kCell, y * kCell);
            Vec2 back = p - at(p) * dt;
            Vec2 adv = sample(v_, back);
            Vec2 avg = (v_[(size_t)y * nx_ + std::max(0, x - 1)] + v_[(size_t)y * nx_ + std::min(nx_ - 1, x + 1)] +
                        v_[(size_t)std::max(0, y - 1) * nx_ + x] + v_[(size_t)std::min(ny_ - 1, y + 1) * nx_ + x]) * 0.25f;
            tmp_[(size_t)y * nx_ + x] = lerp(adv, avg, blur) * keep;
        }
    }
    // Walls: no flow through the glass.
    for (int y = 0; y < ny_; y++) {
        tmp_[(size_t)y * nx_].x = std::max(0.0f, tmp_[(size_t)y * nx_].x);
        tmp_[(size_t)y * nx_ + nx_ - 1].x = std::min(0.0f, tmp_[(size_t)y * nx_ + nx_ - 1].x);
    }
    for (int x = 0; x < nx_; x++) {
        tmp_[x].y = std::max(0.0f, tmp_[x].y);
        tmp_[(size_t)(ny_ - 1) * nx_ + x].y = std::min(0.0f, tmp_[(size_t)(ny_ - 1) * nx_ + x].y);
    }
    v_.swap(tmp_);
}

float CurrentField::maxStirred() const {
    float m = 0;
    for (auto& v : v_) m = std::max(m, length(v));
    return m;
}

}  // namespace dt
