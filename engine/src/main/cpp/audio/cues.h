#pragma once
// From what the simulation heard to what this screen plays: a sound is heard
// on the screen that shows it (fading out a little past its edges, so one
// happening on the next screen of a shared lagoon is faint here), panned to
// where it is on the glass, duller the deeper it is.
#include <atomic>
#include <cmath>

#include "audio/synth.h"
#include "render/camera.h"
#include "sim/sound.h"

namespace dt::audio {

/** How far past the edge of the glass, as a share of its longer side, a sound still carries. */
constexpr float kCarry = 0.25f;

inline CueKind kindOf(Sound s) {
    switch (s) {
        case Sound::Tap: return CueKind::Tap;
        case Sound::Flakes: return CueKind::Flakes;
        case Sound::Pellet: return CueKind::Pellet;
        case Sound::Bite: return CueKind::Bite;
        case Sound::ClamShut: return CueKind::ClamShut;
        case Sound::Sand: return CueKind::Sand;
        case Sound::Ink: return CueKind::Ink;
        case Sound::Breath: return CueKind::Breath;
    }
    return CueKind::Tap;
}

/** The cue for [e] on the screen [cam] draws, or false if it is too far off to hear. */
inline bool cueFor(const SoundEvent& e, const CameraParams& cam, float floorDepth, Cue* out) {
    Vec2 s = worldToScreen(cam, e.at.xy(), e.at.z);
    float w = cam.outSize.x, h = cam.outSize.y;
    if (w <= 0 || h <= 0) return false;
    float reach = kCarry * std::fmax(w, h);
    float dx = s.x < 0 ? -s.x : (s.x > w ? s.x - w : 0);
    float dy = s.y < 0 ? -s.y : (s.y > h ? s.y - h : 0);
    float off = std::sqrt(dx * dx + dy * dy);
    if (off >= reach) return false;
    float fade = 1.0f - off / reach;
    out->kind = kindOf(e.kind);
    // Not hard left or right: a phone's two speakers are close, and a wide pan sounds wrong.
    float x = (s.x / w) * 2.0f - 1.0f;
    out->pan = (x < -1 ? -1 : (x > 1 ? 1 : x)) * 0.75f;
    out->gain = e.strength * fade * fade;
    out->muffle = floorDepth > 0 ? std::fmin(1.0f, std::fmax(0.0f, e.at.z / floorDepth)) : 0.0f;
    out->size = e.strength;
    return true;
}

/** A queue from one thread to one other without locks: the render thread posts, the audio thread takes. */
template <typename T, int N>
class SpscRing {
public:
    bool push(const T& v) {
        int head = head_.load(std::memory_order_relaxed);
        int next = (head + 1) % N;
        if (next == tail_.load(std::memory_order_acquire)) return false;  // full: dropped
        items_[head] = v;
        head_.store(next, std::memory_order_release);
        return true;
    }
    bool pop(T* v) {
        int tail = tail_.load(std::memory_order_relaxed);
        if (tail == head_.load(std::memory_order_acquire)) return false;
        *v = items_[tail];
        tail_.store((tail + 1) % N, std::memory_order_release);
        return true;
    }

private:
    T items_[N];
    std::atomic<int> head_{0}, tail_{0};
};

}  // namespace dt::audio
