// The lagoon's sound, measured: loud enough, never clipping, placed where it
// happens, duller from deep down, the same every run. One test also writes a
// .wav of a scripted minute in the lagoon to listen to.
#include <gtest/gtest.h>

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "audio/cues.h"
#include "audio/synth.h"
#include "sim/world.h"

using namespace dt;
using namespace dt::audio;

namespace {

constexpr float kRate = 48000;

struct Render {
    std::vector<float> l, r;
    float rms(const std::vector<float>& x, size_t from = 0, size_t to = 0) const {
        if (to == 0 || to > x.size()) to = x.size();
        double s = 0;
        for (size_t i = from; i < to; i++) s += (double)x[i] * x[i];
        return to > from ? (float)std::sqrt(s / (double)(to - from)) : 0.0f;
    }
    float rmsBoth(size_t from = 0, size_t to = 0) const { return std::sqrt((rms(l, from, to) * rms(l, from, to) + rms(r, from, to) * rms(r, from, to)) / 2); }
};

Render render(Synth& s, float seconds) {
    int n = (int)(seconds * s.sampleRate());
    std::vector<float> buf(2 * (size_t)n);
    // In blocks, as a device asks for them.
    for (int at = 0; at < n; at += 192) s.render(buf.data() + 2 * at, std::min(192, n - at));
    Render out;
    out.l.resize(n);
    out.r.resize(n);
    for (int i = 0; i < n; i++) {
        out.l[i] = buf[2 * i];
        out.r[i] = buf[2 * i + 1];
    }
    return out;
}

/** Energy above about 3 kHz: what muffling takes away first. */
float highs(const std::vector<float>& x) {
    Svf f;
    f.set(3000, 0.7f, kRate);
    double s = 0;
    for (float v : x) {
        f.run(v);
        s += (double)f.hp * f.hp;
    }
    return (float)std::sqrt(s / (double)x.size());
}

Synth quiet() {
    Synth s(kRate);
    s.setAmbience(0);
    s.setVolume(1);
    render(s, 0.2f);  // let the volume ramp up
    return s;
}

}  // namespace

TEST(Synth, TheLagoonIsHeardButNeverClips) {
    Synth s(kRate);
    s.setVolume(1);
    Render a = render(s, 6.0f);
    float rms = a.rmsBoth(kRate * 1, kRate * 6);
    EXPECT_GT(rms, 0.01f) << "the water and the reef should be heard";
    EXPECT_LT(rms, 0.2f) << "a bed under everything, not a roar";
    // Every cue at once, many times over: still never past full scale.
    for (int i = 0; i < 200; i++) s.play({(CueKind)(i % (int)CueKind::Count), (float)((i % 7) - 3) / 3.0f, 1, 0, 1});
    Render b = render(s, 2.0f);
    EXPECT_LE(s.peak(), 1.0f);
    for (float v : b.l) ASSERT_TRUE(std::isfinite(v));
}

TEST(Synth, SilentAtVolumeZero) {
    Synth s(kRate);
    s.setVolume(0);
    render(s, 0.5f);
    s.play({CueKind::Tap, 0, 1, 0, 1});
    Render a = render(s, 0.5f);
    EXPECT_LT(a.rmsBoth(), 1e-4f);
}

TEST(Synth, EveryKindOfSoundIsHeard) {
    for (int k = 0; k < (int)CueKind::Count; k++) {
        Synth s = quiet();
        s.play({(CueKind)k, 0, 1, 0, 1});
        Render a = render(s, 1.5f);
        EXPECT_GT(a.rmsBoth(0, kRate / 2), 0.004f) << "kind " << k;
        EXPECT_EQ(0, s.activeVoices()) << "kind " << k << " should end";
    }
}

TEST(Synth, ASoundOnTheLeftOfTheGlassIsHeardOnTheLeft) {
    Synth s = quiet();
    s.play({CueKind::Tap, -1, 1, 0, 1});
    Render a = render(s, 0.4f);
    EXPECT_GT(a.rms(a.l), 4 * a.rms(a.r));
    Synth t = quiet();
    t.play({CueKind::Tap, 1, 1, 0, 1});
    Render b = render(t, 0.4f);
    EXPECT_GT(b.rms(b.r), 4 * b.rms(b.l));
}

TEST(Synth, FromDeepDownASoundIsDuller) {
    Synth s = quiet();
    s.play({CueKind::Sand, 0, 1, 0, 1});
    Render top = render(s, 0.6f);
    Synth t = quiet();
    t.play({CueKind::Sand, 0, 1, 1, 1});
    Render deep = render(t, 0.6f);
    EXPECT_LT(highs(deep.l), 0.3f * highs(top.l));
}

TEST(Synth, AFrenzyOfBitesIsAPatterNotARoar) {
    Synth s = quiet();
    // A thousand bites in a second, as in a feeding frenzy: voices merge, the limiter holds.
    for (int i = 0; i < 1000; i++) {
        s.play({CueKind::Bite, ((float)(i % 11) - 5) / 5.0f, 1, 0.3f, 1});
        render(s, 0.001f);
    }
    EXPECT_LE(s.activeVoices(), Synth::kVoices);
    EXPECT_LE(s.peak(), 1.0f);
}

TEST(Synth, TheSameSeedSoundsTheSame) {
    Synth a(kRate, 3), b(kRate, 3);
    a.play({CueKind::Ink, 0.2f, 1, 0.5f, 1});
    b.play({CueKind::Ink, 0.2f, 1, 0.5f, 1});
    Render ra = render(a, 1.0f), rb = render(b, 1.0f);
    for (size_t i = 0; i < ra.l.size(); i++) ASSERT_EQ(ra.l[i], rb.l[i]);
}

TEST(Cues, ASoundIsHeardOnTheScreenThatShowsItPannedToWhereItIs) {
    CameraParams cam;
    cam.center = {100, 150};
    cam.origin = cam.center;
    cam.pxPerMm = 16;
    cam.outSize = {1080, 2400};
    Cue c;
    // Left of the middle, at the surface.
    ASSERT_TRUE(cueFor({Sound::Tap, Vec3(80, 150, 0), 1}, cam, 55, &c));
    EXPECT_LT(c.pan, -0.3f);
    EXPECT_NEAR(1.0f, c.gain, 1e-4f);
    EXPECT_EQ(0.0f, c.muffle);
    // On the sand: muffled.
    ASSERT_TRUE(cueFor({Sound::Sand, Vec3(100, 150, 55), 0.5f}, cam, 55, &c));
    EXPECT_NEAR(1.0f, c.muffle, 1e-4f);
    EXPECT_NEAR(0.5f, c.gain, 1e-4f);
    // Just past the edge: fainter. Far away: not at all.
    ASSERT_TRUE(cueFor({Sound::Tap, Vec3(100 + 34 + 5, 150, 0), 1}, cam, 55, &c));
    EXPECT_LT(c.gain, 0.95f);
    EXPECT_GT(c.pan, 0.7f);
    EXPECT_FALSE(cueFor({Sound::Tap, Vec3(400, 150, 0), 1}, cam, 55, &c));
}

TEST(Cues, OnAScreenLyingTurnedTheTableSRightIsTheGlassSUp) {
    CameraParams cam;
    cam.center = {100, 150};
    cam.origin = cam.center;
    cam.pxPerMm = 16;
    cam.outSize = {1080, 2400};
    cam.turns = 1;
    Cue c;
    // To the table's right of the middle: up the glass, so neither side.
    ASSERT_TRUE(cueFor({Sound::Tap, Vec3(130, 150, 0), 1}, cam, 55, &c));
    EXPECT_NEAR(0.0f, c.pan, 0.05f);
    // Below the middle on the table: the glass's right... no, its left (a quarter clockwise).
    ASSERT_TRUE(cueFor({Sound::Tap, Vec3(100, 170, 0), 1}, cam, 55, &c));
    Vec2 s = worldToScreen(cam, Vec2(100, 170), 0);
    EXPECT_EQ(s.x < 540, c.pan < 0);
}

TEST(WorldSounds, WhatHappensInTheLagoonIsHeard) {
    World w;
    w.setTank({200, 300, 55});
    w.startle({50, 60});
    w.dropFood({100, 100}, FoodKind::Pellet, 1);
    w.dropFood({100, 100}, FoodKind::Flake, 5);
    auto heard = w.takeSounds();
    ASSERT_EQ(3u, heard.size());
    EXPECT_EQ(Sound::Tap, heard[0].kind);
    EXPECT_EQ(Sound::Pellet, heard[1].kind);
    EXPECT_EQ(Sound::Flakes, heard[2].kind);
    EXPECT_NEAR(0.5f, heard[2].strength, 1e-4f);
    EXPECT_TRUE(w.takeSounds().empty());
}

TEST(WorldSounds, ADinIsCapped) {
    World w;
    w.setTank({200, 300, 55});
    for (int i = 0; i < 1000; i++) w.sound(Sound::Bite, Vec3(10, 10, 10), 1);
    EXPECT_EQ(256u, w.takeSounds().size());
}

namespace {

void writeWav(const std::string& path, const std::vector<float>& l, const std::vector<float>& r, int rate) {
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return;
    auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, f); };
    uint32_t data = (uint32_t)l.size() * 4;
    std::fwrite("RIFF", 1, 4, f);
    u32(36 + data);
    std::fwrite("WAVEfmt ", 1, 8, f);
    u32(16);
    u16(1);
    u16(2);
    u32((uint32_t)rate);
    u32((uint32_t)rate * 4);
    u16(4);
    u16(16);
    std::fwrite("data", 1, 4, f);
    u32(data);
    for (size_t i = 0; i < l.size(); i++) {
        int16_t a = (int16_t)std::lround(std::fmax(-1.0f, std::fmin(1.0f, l[i])) * 32767);
        int16_t b = (int16_t)std::lround(std::fmax(-1.0f, std::fmin(1.0f, r[i])) * 32767);
        std::fwrite(&a, 2, 1, f);
        std::fwrite(&b, 2, 1, f);
    }
    std::fclose(f);
}

}  // namespace

/** Twenty seconds of the lagoon with something happening, to listen to: build/host-tests/lagoon.wav. */
TEST(Demo, WritesTwentySecondsOfTheLagoon) {
    Synth s(kRate, 11);
    s.setVolume(0.8f);
    std::vector<float> L, R;
    struct At {
        float t;
        Cue cue;
    };
    std::vector<At> script = {
        {2.0f, {CueKind::Tap, -0.3f, 1, 0, 1}},       {3.0f, {CueKind::Flakes, 0.2f, 0.9f, 0, 0.8f}},
        {3.8f, {CueKind::Bite, 0.1f, 0.7f, 0.2f, 0.5f}}, {4.0f, {CueKind::Bite, 0.3f, 0.6f, 0.25f, 0.5f}},
        {4.3f, {CueKind::Bite, -0.1f, 0.8f, 0.2f, 0.6f}}, {4.5f, {CueKind::Bite, 0.2f, 0.5f, 0.3f, 0.4f}},
        {6.0f, {CueKind::Pellet, -0.5f, 1, 0, 1}},    {8.0f, {CueKind::ClamShut, 0.5f, 1, 0.9f, 1}},
        {10.0f, {CueKind::Sand, -0.4f, 0.8f, 1, 0.8f}}, {10.2f, {CueKind::Sand, -0.35f, 0.5f, 1, 0.5f}},
        {12.0f, {CueKind::Ink, 0.3f, 1, 0.6f, 1}},   {15.0f, {CueKind::Breath, -0.2f, 1, 0, 1}},
        {17.5f, {CueKind::Tap, 0.4f, 1, 0, 1}},
    };
    size_t next = 0;
    const float block = 0.01f;
    for (float t = 0; t < 20.0f; t += block) {
        while (next < script.size() && script[next].t <= t) s.play(script[next++].cue);
        Render r = render(s, block);
        L.insert(L.end(), r.l.begin(), r.l.end());
        R.insert(R.end(), r.r.begin(), r.r.end());
    }
    EXPECT_LE(s.peak(), 1.0f);
    writeWav("build/host-tests/lagoon.wav", L, R, (int)kRate);
}
