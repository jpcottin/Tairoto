#pragma once
// Quality tiers; mirrors QualityTier in core/world Quality.kt (same ids, same values).
#include <cstdint>

namespace dt {

enum class Quality : int32_t { Low = 0, High = 1, Ultra = 2, Ci = 3 };

struct QualitySettings {
    float renderScale;
    int motesPerLayer;
    bool refraction;
};

inline QualitySettings settingsFor(Quality q) {
    switch (q) {
        case Quality::Low: return {0.6f, 80, false};
        case Quality::Ultra: return {1.0f, 360, true};
        case Quality::Ci: return {0.5f, 150, true};
        case Quality::High:
        default: return {0.85f, 210, true};
    }
}

inline Quality qualityFromId(int id) {
    return (id >= 0 && id <= 3) ? (Quality)id : Quality::High;
}

}  // namespace dt
