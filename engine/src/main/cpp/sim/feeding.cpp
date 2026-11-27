// Food in the tank: its life from the surface to the sand, the scent patches
// that draw fish from further the more food and the more feeders there are,
// and messy bites that break crumbs off what is left.
#include <algorithm>

#include "sim/world.h"

namespace dt {

namespace {
constexpr float kFullHunger = 0.12f;  // below this a creature does not look for food
constexpr float kSinkFlake = 3.2f, kSinkPellet = 9.0f, kSinkCrumb = 2.2f;  // mm/s
constexpr float kFoodForget = 1.0f;  // s without word from its owner: another device's food is gone
}  // namespace

double World::foodMass() const {
    // This device's own food: what another device owns is in its ledger, not this one's.
    double m = 0;
    for (auto& f : food_)
        if (!f.remote) m += f.mass;
    for (auto& f : pendingFood_) m += f.mass;
    return m;
}

const Food* World::findFood(uint32_t id) const {
    auto it = foodIndex_.find(id);
    return it == foodIndex_.end() ? nullptr : &food_[it->second];
}

Food* World::findFoodMutable(uint32_t id) {
    auto it = foodIndex_.find(id);
    if (it == foodIndex_.end()) return nullptr;
    Food& f = food_[it->second];
    return f.mass > 0 ? &f : nullptr;
}

int World::dropFood(Vec2 at, FoodKind kind, int count) {
    if (count > 0) {
        sound(kind == FoodKind::Pellet ? Sound::Pellet : Sound::Flakes, Vec3(at.x, at.y, 0.0f),
              kind == FoodKind::Pellet ? 1.0f : clamp((float)count / 10.0f, 0.2f, 1.0f));
    }
    int made = 0;
    for (int i = 0; i < count; i++) {
        if ((int)(food_.size() + pendingFood_.size()) >= FoodConstants::kMaxParticles) break;
        Food f;
        f.id = newFoodId();
        f.kind = kind;
        f.state = FoodState::Floating;
        f.seed = rng_.next();
        float spread = kind == FoodKind::Pellet ? 0.6f : 2.5f;
        float a = rng_.range(0, kTau), r = spread * std::sqrt(rng_.uniform());
        f.pos = Vec3(at.x + std::cos(a) * r, at.y + std::sin(a) * r, 0.0f);
        f.pos.x = clamp(f.pos.x, 1.0f, tank_.width - 1.0f);
        f.pos.y = clamp(f.pos.y, 1.0f, tank_.height - 1.0f);
        f.mass = kind == FoodKind::Pellet ? FoodConstants::kPelletMg * rng_.range(0.85f, 1.15f)
                                          : FoodConstants::kFlakeMg * rng_.range(0.6f, 1.3f);
        f.timer = kind == FoodKind::Pellet ? rng_.range(0.3f, 0.9f) : rng_.range(3.0f, 9.0f);
        f.spin = rng_.range(0, kTau);
        f.spinRate = rng_.range(-1.2f, 1.2f);
        f.tumble = rng_.range(0, kTau);
        f.tumbleRate = rng_.range(2.0f, 5.0f) * (rng_.chance(0.5f) ? 1.0f : -1.0f);
        f.vel = Vec2(std::cos(a), std::sin(a)) * rng_.range(0.0f, 4.0f);
        ledger_.dropped += f.mass;
        food_.push_back(f);
        foodIndex_[f.id] = food_.size() - 1;
        made++;
    }
    return made;
}

void World::settleFood() {
    // Crumbs made by this step's bites join the water; what was eaten away goes.
    for (auto& f : pendingFood_) food_.push_back(f);
    pendingFood_.clear();
    food_.erase(std::remove_if(food_.begin(), food_.end(), [](const Food& f) { return f.mass <= 0; }), food_.end());
    foodIndex_.clear();
    for (size_t i = 0; i < food_.size(); i++) foodIndex_[food_[i].id] = i;
}

void World::stepFood(float h) {
    const float D = tank_.depth;
    for (Food& f : food_) {
        if (f.mass <= 0) continue;
        if (f.remote) {
            // Another device's: it moves as its owner says, and goes when its owner stops saying.
            if (time_ - f.seen > kFoodForget) {
                f.mass = 0;
                continue;
            }
            f.netPos = f.netPos + Vec3(f.vel.x, f.vel.y, f.vz) * h;
            f.netPos.z = clamp(f.netPos.z, 0.0f, D);
            f.pos = f.pos + (f.netPos - f.pos) * (1.0f - std::exp(-h / 0.15f));
            f.spin += f.spinRate * h;
            f.tumble += f.tumbleRate * h;
            continue;
        }
        Vec2 cur = current_.at(f.pos.xy());
        switch (f.state) {
            case FoodState::Floating:
                f.timer -= h;
                f.pos.z = 0;
                f.vel = Vec2(damp(f.vel.x, cur.x * 0.9f, 2.0f, h), damp(f.vel.y, cur.y * 0.9f, 2.0f, h));
                f.spin += f.spinRate * 0.3f * h;
                if (f.timer <= 0) f.state = FoodState::Sinking;
                break;
            case FoodState::Sinking: {
                float terminal = f.kind == FoodKind::Pellet ? kSinkPellet : (f.kind == FoodKind::Flake ? kSinkFlake : kSinkCrumb);
                f.tumble += f.tumbleRate * h;
                f.spin += f.spinRate * h;
                // A flake flutters down, sliding sideways as it tips.
                Vec2 flutter = f.kind == FoodKind::Pellet ? Vec2() : fromAngle(f.spin) * (std::sin(f.tumble) * 1.6f);
                f.vz = damp(f.vz, terminal * (f.kind == FoodKind::Flake ? 0.7f + 0.3f * std::fabs(std::cos(f.tumble)) : 1.0f), 2.5f, h);
                Vec2 want = cur * 0.75f + flutter;
                f.vel = Vec2(damp(f.vel.x, want.x, 2.0f, h), damp(f.vel.y, want.y, 2.0f, h));
                f.pos.z += f.vz * h;
                // It lands on the sand, or on top of a coral head or a stone.
                float floor = D - decorHeightAt(f.pos.xy());
                if (f.pos.z >= floor - f.radius() * 0.3f) {
                    f.pos.z = floor - 0.15f;
                    f.vz = 0;
                    f.state = FoodState::Resting;
                }
                break;
            }
            case FoodState::Resting:
                f.vel = Vec2(damp(f.vel.x, cur.x * 0.05f, 3.0f, h), damp(f.vel.y, cur.y * 0.05f, 3.0f, h));
                break;
        }
        f.pos.x = clamp(f.pos.x + f.vel.x * h, 0.5f, tank_.width - 0.5f);
        f.pos.y = clamp(f.pos.y + f.vel.y * h, 0.5f, tank_.height - 0.5f);

        // Decomposition: slow in the water, faster on the sand, faster still once it has gone off.
        bool onSand = f.state == FoodState::Resting;
        float rate = onSand ? 0.008f + 0.03f * (1.0f - f.freshness) : 0.0012f;
        float lost = std::min(f.mass, f.mass * rate * h + (onSand ? 0.0006f * h : 0.0f));
        f.mass -= lost;
        ledger_.decomposed += lost;
        f.freshness = std::max(0.0f, f.freshness - h / (onSand ? 90.0f : 400.0f));
        if (f.mass < FoodConstants::kMinMg) {
            ledger_.decomposed += f.mass;
            f.mass = 0;
        }
    }
    settleFood();
}

int World::scentCellOf(Vec2 p) const {
    if (scentCols_ == 0) return -1;
    int x = clamp((int)(p.x / FoodConstants::kScentCell), 0, scentCols_ - 1);
    int y = clamp((int)(p.y / FoodConstants::kScentCell), 0, scentRows_ - 1);
    return y * scentCols_ + x;
}

void World::buildScents(float h) {
    scentCols_ = std::max(1, (int)std::ceil(tank_.width / FoodConstants::kScentCell));
    scentRows_ = std::max(1, (int)std::ceil(tank_.height / FoodConstants::kScentCell));
    const int cells = scentCols_ * scentRows_;
    if ((int)activity_.size() != cells) activity_.assign(cells, 0.0f);
    const float fade = std::exp(-0.6f * h);
    for (float& a : activity_) a *= fade;

    // Bucket the food by cell (a counting sort), and sum each cell.
    foodCellStart_.assign(cells + 1, 0);
    std::vector<int> cellOf(food_.size());
    for (size_t i = 0; i < food_.size(); i++) {
        cellOf[i] = scentCellOf(food_[i].pos.xy());
        foodCellStart_[cellOf[i] + 1]++;
    }
    for (int c = 0; c < cells; c++) foodCellStart_[c + 1] += foodCellStart_[c];
    foodCellItems_.resize(food_.size());
    std::vector<int> fill(foodCellStart_.begin(), foodCellStart_.end() - 1);
    for (size_t i = 0; i < food_.size(); i++) foodCellItems_[fill[cellOf[i]]++] = (int)i;

    scents_.clear();
    scentOfCell_.assign(cells, -1);
    for (int c = 0; c < cells; c++) {
        int a = foodCellStart_[c], b = foodCellStart_[c + 1];
        if (a == b) continue;
        Scent s;
        s.cell = c;
        float w = 0;
        Vec2 sum;
        float zs = 0;
        for (int k = a; k < b; k++) {
            const Food& f = food_[foodCellItems_[k]];
            (f.state == FoodState::Resting ? s.sandMass : s.waterMass) += f.mass;
            sum += f.pos.xy() * f.mass;
            zs += f.pos.z * f.mass;
            w += f.mass;
        }
        if (w <= 0) continue;
        s.at = sum / w;
        s.z = zs / w;
        s.activity = activity_[c];
        scentOfCell_[c] = (int)scents_.size();
        scents_.push_back(s);
    }
    for (const Creature& c : creatures_) {
        if (c.foodTarget && c.scentCell >= 0 && c.scentCell < cells && scentOfCell_[c.scentCell] >= 0)
            scents_[scentOfCell_[c.scentCell]].eaters++;
    }
    // The scent of food on other screens: fish here can follow it there.
    for (Scent s : remoteScents_) {
        s.remote = true;
        s.cell = -1;
        scents_.push_back(s);
    }
}

bool World::forage(Creature& c, Vec2 mouth, float h, Vec3& foodAt) {
    if (c.biteCooldown > 0) c.biteCooldown -= h;
    c.hunger = std::min(1.0f, c.hunger + c.sp.hungerPerS * h);
    if (c.hunger < (c.foodTarget ? 0.03f : kFullHunger)) {
        c.foodTarget = 0;
        c.scentCell = -1;
        return false;
    }
    Food* f = c.foodTarget ? findFoodMutable(c.foodTarget) : nullptr;
    if (f && !c.sp.bottomFeeder && f->state == FoodState::Resting) f = nullptr;
    c.retarget -= h;
    if (!f || c.retarget <= 0) {
        c.retarget = 0.35f + 0.3f * hash01(c.seed + (uint32_t)(time_ * 10));
        // The patch that draws it most: strong and near, within what its nose and hunger reach.
        const Scent* best = nullptr;
        float bestScore = 0;
        for (const Scent& s : scents_) {
            float S = s.strength(c.sp.bottomFeeder);
            if (S <= 0) continue;
            float d = length(s.at - mouth);
            float range = Scent::radiusFor(S) * c.sp.smell * (0.35f + 0.65f * c.hunger);
            if (d > range) continue;
            float score = S / (d + 8.0f);
            if (score > bestScore) {
                bestScore = score;
                best = &s;
            }
        }
        f = nullptr;
        if (best && best->remote) {
            // Food on another screen: nothing here to snap at, so head for it.
            c.foodTarget = 0;
            c.scentCell = -1;
            foodAt = Vec3(best->at.x, best->at.y, best->z);
            return true;
        }
        if (best) {
            // The nearest piece it can eat in that patch or right next to it.
            int cx = best->cell % scentCols_, cy = best->cell / scentCols_;
            float bestD = 1e9f;
            for (int y = std::max(0, cy - 1); y <= std::min(scentRows_ - 1, cy + 1); y++) {
                for (int x = std::max(0, cx - 1); x <= std::min(scentCols_ - 1, cx + 1); x++) {
                    int cell = y * scentCols_ + x;
                    for (int k = foodCellStart_[cell]; k < foodCellStart_[cell + 1]; k++) {
                        Food& cand = food_[foodCellItems_[k]];
                        if (cand.mass <= 0) continue;
                        if (!c.sp.bottomFeeder && cand.state == FoodState::Resting) continue;
                        float d = length(cand.pos.xy() - mouth) + std::fabs(cand.pos.z - c.pos.z) * 0.5f;
                        if (d < bestD) {
                            bestD = d;
                            f = &cand;
                        }
                    }
                }
            }
        }
        c.foodTarget = f ? f->id : 0;
    }
    if (!f) {
        c.scentCell = -1;
        return false;
    }
    c.scentCell = scentCellOf(f->pos.xy());
    foodAt = f->pos;
    return true;
}

void World::tryBite(Creature& c, Vec2 mouth, float mouthZ, float reach) {
    // A ghost never eats: its owner's copy does, on the owner's word.
    if (c.ghost || !c.foodTarget || c.biteCooldown > 0) return;
    Food* f = findFoodMutable(c.foodTarget);
    if (!f) return;
    float d = length(f->pos.xy() - mouth);
    float dz = std::fabs(f->pos.z - mouthZ);
    if (d < reach + f->radius() * 0.6f && dz < reach * 1.5f + 3.0f) bite(c, *f);
}

void World::bite(Creature& c, Food& f) {
    c.biteCooldown = 0.22f + c.length * 0.008f;
    // A snap, louder for a big mouthful.
    sound(Sound::Bite, f.pos, clamp(c.sp.biteMg / 1.2f, 0.25f, 1.0f));
    int cell = scentCellOf(f.pos.xy());
    if (f.remote) {
        // Another device's food: ask it. It decides how much, and breaks the crumbs off.
        wants_.push_back({f.id, c.id, std::min(f.mass, c.sp.biteMg), c.sp.messiness});
        if (cell >= 0 && cell < (int)activity_.size()) activity_[cell] += 1.0f;
        return;
    }
    float eaten = std::min(f.mass, c.sp.biteMg);
    f.mass -= eaten;
    ledger_.eaten += eaten;
    c.eatenMg += eaten;
    c.hunger = std::max(0.0f, c.hunger - eaten / c.sp.stomachMg);
    if (cell >= 0 && cell < (int)activity_.size()) activity_[cell] += 1.0f;
    breakCrumbs(f, c.sp.messiness);
    if (f.mass <= 0) c.foodTarget = 0;
}

void World::breakCrumbs(Food& f, float messiness) {
    float crumbs = f.mass * messiness * rng_.range(0.6f, 1.0f);
    if (crumbs > FoodConstants::kMinMg * 0.5f) {
        f.mass -= crumbs;
        int n = 2 + (int)(rng_.uniform() * 3.0f);
        float left = crumbs;
        for (int i = 0; i < n; i++) {
            float m = i == n - 1 ? left : left * rng_.range(0.2f, 0.6f);
            left -= m;
            if (m < FoodConstants::kMinMg || (int)(food_.size() + pendingFood_.size()) >= FoodConstants::kMaxParticles) {
                ledger_.decomposed += m;  // dust
                continue;
            }
            Food k;
            k.id = newFoodId();
            k.kind = FoodKind::Crumb;
            k.state = f.state == FoodState::Resting ? FoodState::Resting : FoodState::Sinking;
            k.seed = rng_.next();
            float a = rng_.range(0, kTau);
            k.pos = Vec3(f.pos.x + std::cos(a) * 0.4f, f.pos.y + std::sin(a) * 0.4f, f.pos.z);
            k.vel = Vec2(std::cos(a), std::sin(a)) * rng_.range(8.0f, 26.0f);
            k.vz = rng_.range(-2.0f, 3.0f);
            k.mass = m;
            k.freshness = f.freshness;
            k.spin = rng_.range(0, kTau);
            k.spinRate = rng_.range(-3.0f, 3.0f);
            k.tumble = rng_.range(0, kTau);
            k.tumbleRate = rng_.range(3.0f, 7.0f);
            pendingFood_.push_back(k);
        }
    }
    if (f.mass < FoodConstants::kMinMg) {
        ledger_.decomposed += f.mass;
        f.mass = 0;
    }
}

}  // namespace dt
