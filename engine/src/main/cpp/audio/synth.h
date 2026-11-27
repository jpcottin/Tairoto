#pragma once
// The lagoon's sound, made as it plays: no recordings. A bed of lapping water
// with the crackle of a reef under it (snapping shrimp, the sound every reef
// makes), and short sounds over it for what happens: a knock on the glass,
// flakes and pellets landing, fish snapping at food, the clam clapping shut,
// sand puffing up, the octopus jetting away, the turtle breathing.
//
// Plain C++ with no Android in it: the audio thread calls render(), and the
// host tests render it to measure (and to write a .wav to listen to).
// Not thread-safe: AudioOut feeds it cues from its own thread.
#include <cstdint>

namespace dt::audio {

enum class CueKind : int32_t { Tap, Flakes, Pellet, Bite, ClamShut, Sand, Ink, Breath, Scatter, Count };

/** One sound to play, placed on the glass. */
struct Cue {
    CueKind kind = CueKind::Tap;
    float pan = 0;     // -1 left .. 1 right
    float gain = 1;    // 0..1
    float muffle = 0;  // 0 at the surface .. 1 down on the sand: deeper sounds duller
    float size = 1;    // what it means depends on the kind: how many flakes, how big a bite
};

/** A small fast random generator, the same every run for a seed. */
struct Rand {
    uint32_t s = 0x9E3779B9u;
    explicit Rand(uint32_t seed = 1) : s(seed ? seed : 1) {}
    uint32_t next() {
        s ^= s << 13;
        s ^= s >> 17;
        s ^= s << 5;
        return s;
    }
    float unit() { return (float)(next() >> 8) * (1.0f / 16777216.0f); }  // 0..1
    float bi() { return unit() * 2.0f - 1.0f; }                           // -1..1
};

/** A state-variable filter (topology-preserving): low, band and high pass at once. */
struct Svf {
    float a1 = 0, a2 = 0, a3 = 0, k = 1, ic1 = 0, ic2 = 0;
    void set(float cutoffHz, float q, float sampleRate);
    float lp = 0, bp = 0, hp = 0;
    void run(float x);
};

/** A one-pole low pass, for smoothing and for muffling. */
struct OnePole {
    float a = 1, z = 0;
    void set(float cutoffHz, float sampleRate);
    float run(float x) { return z += a * (x - z); }
};

class Synth {
public:
    static constexpr int kVoices = 40;

    explicit Synth(float sampleRate, uint32_t seed = 7);

    /** Plays a cue from the next sample on. Too many of one kind at once are merged into the latest. */
    void play(const Cue& cue);

    /** Fills [frames] stereo frames, interleaved left, right. */
    void render(float* out, int frames);

    /** The whole mix, 0..1 (0 is silence). */
    void setVolume(float v) { volumeTarget_ = v < 0 ? 0 : (v > 1 ? 1 : v); }
    /** The water and the reef under everything, 0..1. */
    void setAmbience(float a) { ambienceTarget_ = a < 0 ? 0 : (a > 1 ? 1 : a); }

    float sampleRate() const { return sr_; }
    int activeVoices() const;
    /** The loudest sample written so far (to check the limiter). */
    float peak() const { return peak_; }

private:
    struct Voice {
        bool on = false;
        CueKind kind = CueKind::Tap;
        int64_t t = 0, len = 0;  // samples
        float gl = 0, gr = 0;   // gain on each side
        float size = 1;
        float phase = 0, freq = 0, env = 0;
        Rand rng;
        Svf f1, f2;
        OnePole muffle, muffle2;  // two in a row: water takes the highs away steeply
        float extra[8] = {0};
        int64_t started = 0;
    };

    float voiceSample(Voice& v);
    void ambience(float& l, float& r);

    float sr_;
    float inv_;
    Rand rng_;
    Voice voices_[kVoices];
    int64_t clock_ = 0;
    int64_t lastOf_[(int)CueKind::Count];

    float volume_ = 0, volumeTarget_ = 0.8f;
    float ambience_ = 0, ambienceTarget_ = 1.0f;
    float peak_ = 0;

    // The bed: brown noise on each side for the swell, white noise for the fizz, clicks for the reef.
    float brownL_ = 0, brownR_ = 0;
    OnePole swellL_, swellR_;
    Svf lowCutL_, lowCutR_;  // what phone speakers cannot play anyway
    Svf fizzL_, fizzR_;
    double swellPhase1_ = 0, swellPhase2_ = 0;
    struct Click {
        float env = 0, gl = 0, gr = 0;
        Svf f;
    };
    Click clicks_[12];
};

}  // namespace dt::audio
