#include "audio/synth.h"

#include <cmath>

namespace dt::audio {

namespace {

constexpr float kPi = 3.14159265f;
constexpr float kTau = 6.28318531f;

/** How long each kind lasts, in seconds, and how soon another of the same kind starts a voice of its own. */
constexpr float kLength[] = {0.45f, 0.7f, 0.3f, 0.05f, 0.75f, 0.6f, 0.95f, 1.4f, 0.55f};
constexpr float kApart[] = {0.02f, 0.06f, 0.03f, 0.015f, 0.2f, 0.07f, 0.3f, 0.5f, 0.15f};

float smooth(float a, float b, float x) {
    float t = (x - a) / (b - a);
    t = t < 0 ? 0 : (t > 1 ? 1 : t);
    return t * t * (3 - 2 * t);
}

/**
 * The phase of a tone gliding from f0 up to f0 * (1 + rise), approaching it
 * with time constant tau: a bubble's ring rises as it shrinks (Minnaert).
 */
float glide(float t, float f0, float rise, float tau) {
    return kTau * f0 * (t + rise * (t - tau * (1.0f - std::exp(-t / tau))));
}

/** A small bubble popping at [t0], seconds into a voice: a rising ring. */
float bubble(float t, float t0, float f0, float amp) {
    float u = t - t0;
    if (u < 0 || u > 0.08f) return 0;
    return std::sin(glide(u, f0, 1.3f, 0.02f)) * std::exp(-u / 0.022f) * smooth(0, 0.0015f, u) * amp;
}

/** Soft above 0.8, never past 1. */
float limit(float x) {
    float a = std::fabs(x);
    if (a <= 0.8f) return x;
    float y = 0.8f + 0.2f * std::tanh((a - 0.8f) / 0.2f);
    return x < 0 ? -y : y;
}

}  // namespace

void Svf::set(float cutoffHz, float q, float sampleRate) {
    float fc = cutoffHz < 10 ? 10 : (cutoffHz > sampleRate * 0.45f ? sampleRate * 0.45f : cutoffHz);
    float g = std::tan(kPi * fc / sampleRate);
    k = 1.0f / q;
    a1 = 1.0f / (1.0f + g * (g + k));
    a2 = g * a1;
    a3 = g * a2;
}

void Svf::run(float x) {
    float v3 = x - ic2;
    float v1 = a1 * ic1 + a2 * v3;
    float v2 = ic2 + a2 * ic1 + a3 * v3;
    ic1 = 2 * v1 - ic1;
    ic2 = 2 * v2 - ic2;
    lp = v2;
    bp = v1;
    hp = x - k * v1 - v2;
}

void OnePole::set(float cutoffHz, float sampleRate) { a = 1.0f - std::exp(-kTau * cutoffHz / sampleRate); }

Synth::Synth(float sampleRate, uint32_t seed) : sr_(sampleRate), inv_(1.0f / sampleRate), rng_(seed) {
    for (auto& t : lastOf_) t = -1'000'000'000;
    swellL_.set(900, sr_);
    swellR_.set(950, sr_);
    lowCutL_.set(170, 0.7f, sr_);
    lowCutR_.set(170, 0.7f, sr_);
    fizzL_.set(3200, 0.6f, sr_);
    fizzR_.set(3600, 0.6f, sr_);
    // Seeds the swell part way in, so the first second is not a fade from nothing.
    swellPhase1_ = 0.37;
    swellPhase2_ = 0.71;
}

int Synth::activeVoices() const {
    int n = 0;
    for (const auto& v : voices_) n += v.on;
    return n;
}

void Synth::play(const Cue& cue) {
    int k = (int)cue.kind;
    if (k < 0 || k >= (int)CueKind::Count || cue.gain <= 0.0005f) return;
    float pan = cue.pan < -1 ? -1 : (cue.pan > 1 ? 1 : cue.pan);
    float gain = cue.gain > 1 ? 1 : cue.gain;
    float gl = std::cos((pan + 1) * kPi / 4) * gain;
    float gr = std::sin((pan + 1) * kPi / 4) * gain;

    // Another of the same kind a moment ago: one sound, as loud as the louder.
    if (clock_ - lastOf_[k] < (int64_t)(kApart[k] * sr_)) {
        Voice* latest = nullptr;
        for (auto& v : voices_)
            if (v.on && v.kind == cue.kind && (!latest || v.started > latest->started)) latest = &v;
        if (latest) {
            latest->gl = std::fmax(latest->gl, gl);
            latest->gr = std::fmax(latest->gr, gr);
            latest->size = std::fmax(latest->size, cue.size);
            return;
        }
    }
    lastOf_[k] = clock_;

    // A free voice, or the one nearest its end.
    Voice* v = nullptr;
    float most = -1;
    for (auto& c : voices_) {
        if (!c.on) {
            v = &c;
            break;
        }
        float done = (float)c.t / (float)c.len;
        if (done > most) {
            most = done;
            v = &c;
        }
    }
    *v = Voice();
    v->on = true;
    v->kind = cue.kind;
    v->len = (int64_t)(kLength[k] * (cue.kind == CueKind::Flakes ? (0.6f + 0.6f * cue.size) : 1.0f) * sr_);
    v->gl = gl;
    v->gr = gr;
    v->size = cue.size < 0 ? 0 : (cue.size > 1 ? 1 : cue.size);
    v->rng = Rand(rng_.next());
    v->started = clock_;
    float m = cue.muffle < 0 ? 0 : (cue.muffle > 1 ? 1 : cue.muffle);
    // Through more water, the highs go first: 10 kHz at the surface, about 1 kHz on the sand.
    v->muffle.set(10000.0f * std::pow(0.1f, m), sr_);
    v->muffle2 = v->muffle;
    switch (cue.kind) {
        case CueKind::Tap:
            v->f1.set(2500, 0.7f, sr_);
            v->f2.set(420, 0.7f, sr_);
            break;
        case CueKind::Flakes:
            v->f1.set(4200, 2.2f, sr_);
            v->extra[1] = std::exp(-1.0f / (0.0012f * sr_));  // how fast each tick dies
            break;
        case CueKind::Pellet:
            v->f1.set(2200, 0.7f, sr_);
            v->freq = 330.0f + 90.0f * v->rng.unit();
            break;
        case CueKind::Bite:
            v->f1.set(1700.0f + 1500.0f * v->rng.unit(), 4.0f, sr_);
            break;
        case CueKind::ClamShut:
            v->f1.set(700, 0.7f, sr_);
            v->f2.set(1800, 2.0f, sr_);
            break;
        case CueKind::Sand:
            v->f1.set(900.0f + 500.0f * v->rng.unit(), 0.8f, sr_);
            break;
        case CueKind::Ink:
            v->f1.set(300, 0.7f, sr_);
            for (int i = 0; i < 4; i++) v->extra[i] = 0.25f + 0.55f * v->rng.unit();
            break;
        case CueKind::Scatter:
            v->f1.set(900, 0.9f, sr_);
            v->f2.set(2600, 1.2f, sr_);
            break;
        case CueKind::Breath:
            v->f1.set(850, 1.3f, sr_);
            v->f2.set(2200, 0.7f, sr_);
            for (int i = 0; i < 5; i++) v->extra[i] = 0.12f + 0.11f * (float)i + 0.05f * v->rng.unit();
            break;
        case CueKind::Count:
            break;
    }
}

float Synth::voiceSample(Voice& v) {
    float t = (float)v.t * inv_;
    float n = v.rng.bi();
    float out = 0;
    switch (v.kind) {
        case CueKind::Tap: {
            // A knuckle on glass over water: a dull knock, a click, and the water stirring.
            float knock = std::sin(glide(t, 150.0f, -0.35f, 0.015f)) * std::exp(-t / 0.07f) * smooth(0, 0.001f, t);
            v.f1.run(n);
            float click = v.f1.lp * std::exp(-t / 0.003f);
            v.f2.run(n);
            float slosh = v.f2.bp * (1 - std::exp(-t / 0.04f)) * std::exp(-t / 0.17f) * 1.6f;
            out = (knock * 0.9f + click * 0.9f + slosh) * 0.85f;
            break;
        }
        case CueKind::Flakes: {
            // A pinch of flakes pattering on the water: tiny ticks, fewer as they settle.
            float lenS = (float)v.len * inv_;
            float rate = (30.0f + 90.0f * v.size) * (1.0f - smooth(0.05f, lenS * 0.8f, t));
            if (v.rng.unit() < rate * inv_) v.env = 0.35f + 0.65f * v.rng.unit();
            v.env *= v.extra[1];
            v.f1.run(n);
            out = v.f1.bp * v.env * 2.2f;
            break;
        }
        case CueKind::Pellet: {
            // A plop: a bubble's ring rising, and a splash.
            float ring = std::sin(glide(t, v.freq, 1.8f, 0.025f)) * smooth(0, 0.002f, t) * std::exp(-t / 0.05f);
            v.f1.run(n);
            float splash = v.f1.hp * std::exp(-t / 0.008f) * 0.45f;
            out = (ring + splash) * 0.8f + bubble(t, 0.11f, 900.0f, 0.18f);
            break;
        }
        case CueKind::Bite: {
            // A snap: a short ringing click.
            v.f1.run(n);
            float snap = v.f1.bp * std::exp(-t / 0.005f) * 3.2f;
            float tick = std::sin(kTau * 2900.0f * t) * std::exp(-t / 0.0025f) * 0.25f;
            out = (snap + tick) * (0.4f + 0.6f * v.size);
            break;
        }
        case CueKind::ClamShut: {
            // Two valves clapping: a clack, a deep thump, a puff of water blown out.
            float thump = std::sin(glide(t, 72.0f, -0.3f, 0.03f)) * smooth(0, 0.002f, t) * std::exp(-t / 0.1f);
            v.f1.run(n);
            float puff = v.f1.lp * (1 - std::exp(-t / 0.012f)) * std::exp(-t / 0.22f) * 1.3f;
            v.f2.run(n);
            float clack = v.f2.bp * std::exp(-t / 0.006f) * 1.6f;
            out = (thump + puff + clack) * 0.8f + bubble(t, 0.09f, 650.0f, 0.15f) + bubble(t, 0.2f, 800.0f, 0.1f);
            break;
        }
        case CueKind::Sand: {
            // A soft hiss of sand thrown up and settling.
            v.f1.run(n);
            out = v.f1.bp * (1 - std::exp(-t / 0.03f)) * std::exp(-t / 0.16f) * 2.4f * (0.35f + 0.65f * v.size);
            break;
        }
        case CueKind::Ink: {
            // A jet: water rushing through the siphon, rising and falling, and bubbles in the wake.
            float lenS = (float)v.len * inv_;
            float shape = std::sin(kPi * t / lenS);
            if ((v.t & 31) == 0) v.f1.set(260.0f + 1500.0f * shape * shape, 0.7f, sr_);
            v.f1.run(n);
            out = v.f1.lp * std::pow(shape, 1.5f) * 1.5f;
            for (int i = 0; i < 4; i++) out += bubble(t, v.extra[i], 700.0f + 300.0f * (float)i, 0.12f);
            break;
        }
        case CueKind::Scatter: {
            // A school bolting: a rush of water, falling in pitch as it goes, flickering with the tails.
            if ((v.t & 31) == 0) v.f1.set(1100.0f * std::exp(-t / 0.25f) + 300.0f, 0.9f, sr_);
            v.f1.run(n);
            v.f2.run(n);
            float rush = v.f1.bp * smooth(0, 0.02f, t) * std::exp(-t / 0.14f) * 2.2f;
            float flicker = 0.6f + 0.4f * std::sin(kTau * (28.0f + 10.0f * v.size) * t);
            out = (rush * flicker + v.f2.bp * std::exp(-t / 0.04f) * 0.8f) * (0.5f + 0.5f * v.size);
            break;
        }
        case CueKind::Breath: {
            // The turtle at the surface: a breath blown out, then bubbles.
            float e = smooth(0, 0.12f, t) * (t < 0.55f ? 1.0f : std::exp(-(t - 0.55f) / 0.12f));
            v.f1.run(n);
            v.f2.run(v.f1.bp);
            out = v.f2.lp * e * 1.5f;
            for (int i = 0; i < 5; i++) out += bubble(t, 0.55f + v.extra[i], 600.0f + 220.0f * (float)i, 0.14f);
            break;
        }
        case CueKind::Count:
            break;
    }
    return v.muffle2.run(v.muffle.run(out));
}

void Synth::ambience(float& l, float& r) {
    // The swell: brown noise, dull, slowly rising and falling as waves pass over.
    brownL_ = brownL_ * 0.997f + rng_.bi() * 0.035f;
    brownR_ = brownR_ * 0.997f + rng_.bi() * 0.035f;
    swellPhase1_ += 0.09 * inv_;
    swellPhase2_ += 0.23 * inv_;
    if (swellPhase1_ > 1) swellPhase1_ -= 1;
    if (swellPhase2_ > 1) swellPhase2_ -= 1;
    float m = 0.55f + 0.25f * std::sin(kTau * (float)swellPhase1_) + 0.2f * std::sin(kTau * (float)swellPhase2_ + 1.3f);
    float m2 = 0.55f + 0.25f * std::sin(kTau * (float)swellPhase1_ + 0.9f) + 0.2f * std::sin(kTau * (float)swellPhase2_ + 2.4f);
    // Above 150 Hz: below that a phone's speaker plays nothing, and headphones would only hear a rumble.
    float sl = swellL_.run(brownL_);
    float sr = swellR_.run(brownR_);
    lowCutL_.run(sl);
    lowCutR_.run(sr);
    l = lowCutL_.hp * m * 1.8f;
    r = lowCutR_.hp * m2 * 1.8f;
    // The fizz of the surface, louder as a wave breaks overhead.
    fizzL_.run(rng_.bi());
    fizzR_.run(rng_.bi());
    l += fizzL_.bp * 0.018f * m * m;
    r += fizzR_.bp * 0.018f * m2 * m2;
    // The reef: snapping shrimp, a sparse crackle all round, a few loud ones near.
    if (rng_.unit() < 24.0f * inv_) {
        for (auto& c : clicks_) {
            if (c.env > 0.0005f) continue;
            float u = rng_.unit();
            c.env = 0.01f + 0.2f * u * u * u;
            float pan = rng_.bi();
            c.gl = std::cos((pan + 1) * kPi / 4);
            c.gr = std::sin((pan + 1) * kPi / 4);
            c.f.set(2500.0f + 3500.0f * rng_.unit(), 1.4f, sr_);
            break;
        }
    }
    const float decay = std::exp(-inv_ / 0.0013f);
    for (auto& c : clicks_) {
        if (c.env <= 0.0005f) continue;
        c.f.run(rng_.bi() * c.env + 1e-20f);
        l += c.f.bp * c.gl * 2.0f;
        r += c.f.bp * c.gr * 2.0f;
        c.env *= decay;
    }
}

void Synth::render(float* out, int frames) {
    for (int i = 0; i < frames; i++) {
        volume_ += (volumeTarget_ - volume_) * 0.0008f;
        ambience_ += (ambienceTarget_ - ambience_) * 0.0003f;
        float l = 0, r = 0;
        ambience(l, r);
        l *= ambience_;
        r *= ambience_;
        for (auto& v : voices_) {
            if (!v.on) continue;
            float s = voiceSample(v);
            l += s * v.gl;
            r += s * v.gr;
            if (++v.t >= v.len) v.on = false;
        }
        l = limit(l * volume_);
        r = limit(r * volume_);
        float a = std::fmax(std::fabs(l), std::fabs(r));
        if (a > peak_) peak_ = a;
        out[2 * i] = l;
        out[2 * i + 1] = r;
        clock_++;
    }
}

}  // namespace dt::audio
