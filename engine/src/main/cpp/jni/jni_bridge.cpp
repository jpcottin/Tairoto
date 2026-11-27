// JNI entry points for com.jpcottin.tairoto.engine.NativeTank.
#include <android/native_window_jni.h>
#include <jni.h>

#include <cmath>
#include <cstring>
#include <vector>

#include "core/log.h"
#include "engine/engine.h"

using namespace dt;

namespace {
Engine* engine(jlong h) { return reinterpret_cast<Engine*>(h); }

// The numbers of one creature, one piece of food, one scent, in NetPacking.kt's order.
constexpr size_t kNetInHeader = 11;

template <typename F> void readState(NetState& s, int64_t id, F&& f) {
    s.id = id;
    s.pos.x = f(); s.pos.y = f(); s.pos.z = f();
    s.heading = f(); s.speed = f(); s.vz = f();
    s.behavior = (Behavior)(int)std::lround(f());
    s.stateTimer = f(); s.hunger = f(); s.cover = f(); s.rest = f(); s.alarm = f();
    s.target.x = f(); s.target.y = f(); s.target.z = f();
    s.den = f() > 0.5f;
    s.eatenMg = f(); s.startle = f();
}

void writeState(const NetState& s, std::vector<jfloat>& o) {
    float v[] = {s.pos.x, s.pos.y, s.pos.z, s.heading, s.speed, s.vz, (float)(int)s.behavior, s.stateTimer, s.hunger,
                 s.cover, s.rest, s.alarm, s.target.x, s.target.y, s.target.z, s.den ? 1.0f : 0.0f, s.eatenMg, s.startle};
    o.insert(o.end(), std::begin(v), std::end(v));
}

template <typename F> void readFood(Food& f, F&& g) {
    f.kind = (FoodKind)(int)std::lround(g());
    f.state = (FoodState)(int)std::lround(g());
    f.pos.x = g(); f.pos.y = g(); f.pos.z = g();
    f.vel.x = g(); f.vel.y = g(); f.vz = g();
    f.mass = g(); f.freshness = g();
    f.spin = (float)(f.seed % 628) / 100.0f;
    f.spinRate = ((float)(f.seed % 241) - 120.0f) / 100.0f;
    f.tumble = (float)(f.seed % 317) / 50.0f;
    f.tumbleRate = 2.0f + (float)(f.seed % 300) / 100.0f;
}

void writeFood(const Food& f, std::vector<jfloat>& o) {
    float v[] = {(float)(int)f.kind, (float)(int)f.state, f.pos.x, f.pos.y, f.pos.z, f.vel.x, f.vel.y, f.vz, f.mass, f.freshness};
    o.insert(o.end(), std::begin(v), std::end(v));
}

template <typename F> void readScent(Scent& s, F&& f) {
    s.at.x = f(); s.at.y = f(); s.z = f();
    s.waterMass = f(); s.sandMass = f();
    s.eaters = (int)std::lround(f());
    s.activity = f();
}

void writeScent(const Scent& s, std::vector<jfloat>& o) {
    float v[] = {s.at.x, s.at.y, s.z, s.waterMass, s.sandMass, (float)s.eaters, s.activity};
    o.insert(o.end(), std::begin(v), std::end(v));
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete engine(h);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetSurface(JNIEnv* env, jclass, jlong h,
                                                                                    jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    engine(h)->setWindow(w);
    if (w) ANativeWindow_release(w);  // the engine holds its own reference
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetTank(JNIEnv*, jclass, jlong h, jfloat w,
                                                                                 jfloat ht, jfloat d) {
    engine(h)->setTank({w, ht, d});
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetCamera(JNIEnv*, jclass, jlong h, jfloat cx,
                                                                                   jfloat cy, jfloat pxPerMm,
                                                                                   jfloat camHeight, jfloat ox,
                                                                                   jfloat oy, jfloat outW, jfloat outH,
                                                                                   jint turns) {
    CameraParams c;
    c.center = {cx, cy};
    c.pxPerMm = pxPerMm;
    c.camHeight = camHeight;
    c.origin = {ox, oy};
    c.outSize = {outW, outH};
    c.turns = turns;
    engine(h)->setCamera(c);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetQuality(JNIEnv*, jclass, jlong h, jint q) {
    engine(h)->setQuality(qualityFromId(q));
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetDeterministic(JNIEnv*, jclass, jlong h,
                                                                                          jboolean on, jlong seed) {
    engine(h)->setDeterministic(on, (uint64_t)seed);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetPaused(JNIEnv*, jclass, jlong h,
                                                                                   jboolean paused) {
    engine(h)->setPaused(paused);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSpawn(JNIEnv* env, jclass, jlong h, jlong id,
                                                                               jint visual, jint loco,
                                                                               jfloatArray params, jfloat length,
                                                                               jint seed, jfloat x, jfloat y, jfloat z,
                                                                               jboolean ghost) {
    jsize n = env->GetArrayLength(params);
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    SpeciesParams sp = SpeciesParams::fromArray(p, n);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    engine(h)->spawn(id, (Visual)visual, (Locomotion)loco, sp, length, (uint32_t)seed, Vec3(x, y, z), ghost);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetFoodSlot(JNIEnv*, jclass, jlong h, jint slot) {
    engine(h)->setFoodSlot(slot);
}

/**
 * One tick of a shared lagoon. The layout of the arrays is NetPacking.kt's:
 * a header of counts, then each section's ids in [longs] and numbers in
 * [floats], in order. Returns {int[] header, long[], float[], double[] ledger}.
 */
JNIEXPORT jobjectArray JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeExchange(JNIEnv* env, jclass, jlong h,
                                                                                          jintArray headerA,
                                                                                          jlongArray longsA,
                                                                                          jfloatArray floatsA) {
    std::vector<jint> hd(env->GetArrayLength(headerA));
    std::vector<jlong> L(env->GetArrayLength(longsA));
    std::vector<jfloat> F(env->GetArrayLength(floatsA));
    env->GetIntArrayRegion(headerA, 0, (jsize)hd.size(), hd.data());
    env->GetLongArrayRegion(longsA, 0, (jsize)L.size(), L.data());
    env->GetFloatArrayRegion(floatsA, 0, (jsize)F.size(), F.data());
    NetIn in;
    if (hd.size() >= kNetInHeader) {
        size_t li = 0, fi = 0;
        bool ok = true;
        auto lng = [&]() -> int64_t { if (li < L.size()) return L[li++]; ok = false; return 0; };
        auto flt = [&]() -> float { if (fi < F.size()) return F[fi++]; ok = false; return 0.0f; };
        auto state = [&](int64_t id) { NetState s; readState(s, id, flt); return s; };
        for (int i = 0; i < hd[0] && ok; i++) in.release.push_back(lng());
        std::vector<int64_t> takeIds, ghostIds;
        for (int i = 0; i < hd[1] && ok; i++) takeIds.push_back(lng());
        for (int i = 0; i < hd[2] && ok; i++) ghostIds.push_back(lng());
        std::vector<std::pair<uint32_t, uint32_t>> ghostFoodIds, adoptIds;
        for (int i = 0; i < hd[3] && ok; i++) { uint32_t a = (uint32_t)lng(); ghostFoodIds.push_back({a, (uint32_t)lng()}); }
        for (int i = 0; i < hd[4] && ok; i++) { uint32_t a = (uint32_t)lng(); adoptIds.push_back({a, (uint32_t)lng()}); }
        for (int i = 0; i < hd[5] && ok; i++) in.releaseFood.push_back((uint32_t)lng());
        std::vector<std::pair<uint32_t, int64_t>> biteIds;
        for (int i = 0; i < hd[6] && ok; i++) { uint32_t a = (uint32_t)lng(); biteIds.push_back({a, lng()}); }
        std::vector<int64_t> fedIds;
        for (int i = 0; i < hd[7] && ok; i++) fedIds.push_back(lng());
        for (int64_t id : takeIds) { bool has = flt() > 0.5f; in.take.push_back({state(id), has}); }
        for (int64_t id : ghostIds) { float age = flt(); in.ghosts.push_back({state(id), age}); }
        for (auto [id, seed] : ghostFoodIds) {
            float age = flt();
            Food f; f.id = id; f.seed = seed; readFood(f, flt);
            in.ghostFood.push_back({f, age});
        }
        for (auto [id, seed] : adoptIds) { Food f; f.id = id; f.seed = seed; readFood(f, flt); in.adopt.push_back(f); }
        for (auto [fid, cid] : biteIds) {
            BiteWant b; b.food = fid; b.creature = cid; b.wantMg = flt(); b.messiness = flt();
            in.bites.push_back(b);
        }
        for (int64_t id : fedIds) in.fed.push_back({id, flt()});
        in.hasScents = hd[8] != 0;
        for (int i = 0; i < hd[9] && ok; i++) { Scent s; readScent(s, flt); in.scents.push_back(s); }
        for (int i = 0; i < hd[10] && ok; i++) { float x = flt(); in.taps.push_back(Vec2(x, flt())); }
        if (!ok) DT_LOGW("exchange: arrays shorter than their header says; applying what fitted");
    }
    NetOut out = engine(h)->exchange(in);

    std::vector<jint> oh = {(jint)out.owned.size(), (jint)out.released.size(), (jint)out.food.size(),
                            (jint)out.releasedFood.size(), (jint)out.scents.size(), (jint)out.wants.size(),
                            (jint)out.granted.size()};
    std::vector<jlong> ol;
    std::vector<jfloat> of;
    for (auto* list : {&out.owned, &out.released}) for (const NetState& s : *list) ol.push_back(s.id);
    for (auto* list : {&out.food, &out.releasedFood}) for (const Food& f : *list) { ol.push_back((jlong)f.id); ol.push_back((jlong)f.seed); }
    for (const BiteWant& w : out.wants) { ol.push_back((jlong)w.food); ol.push_back(w.creature); }
    for (auto* list : {&out.owned, &out.released}) for (const NetState& s : *list) writeState(s, of);
    for (auto* list : {&out.food, &out.releasedFood}) for (const Food& f : *list) writeFood(f, of);
    for (const Scent& s : out.scents) writeScent(s, of);
    for (const BiteWant& w : out.wants) { of.push_back(w.wantMg); of.push_back(w.messiness); }
    for (float g : out.granted) of.push_back(g);
    double ledger[6] = {out.ledger.dropped, out.ledger.eaten, out.ledger.decomposed, out.ledger.sent,
                        out.ledger.received, out.inWater};

    jintArray rh = env->NewIntArray((jsize)oh.size());
    env->SetIntArrayRegion(rh, 0, (jsize)oh.size(), oh.data());
    jlongArray rl = env->NewLongArray((jsize)ol.size());
    env->SetLongArrayRegion(rl, 0, (jsize)ol.size(), ol.data());
    jfloatArray rf = env->NewFloatArray((jsize)of.size());
    env->SetFloatArrayRegion(rf, 0, (jsize)of.size(), of.data());
    jdoubleArray rd = env->NewDoubleArray(6);
    env->SetDoubleArrayRegion(rd, 0, 6, ledger);
    jobjectArray result = env->NewObjectArray(4, env->FindClass("java/lang/Object"), nullptr);
    env->SetObjectArrayElement(result, 0, rh);
    env->SetObjectArrayElement(result, 1, rl);
    env->SetObjectArrayElement(result, 2, rf);
    env->SetObjectArrayElement(result, 3, rd);
    return result;
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeRemove(JNIEnv*, jclass, jlong h, jlong id) {
    engine(h)->remove(id);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeClear(JNIEnv*, jclass, jlong h) {
    engine(h)->clearCreatures();
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativePlaceDecor(JNIEnv*, jclass, jlong h, jlong id,
                                                                                    jint kind, jint seed, jfloat x,
                                                                                    jfloat y, jfloat angle,
                                                                                    jfloat radius, jfloat height) {
    engine(h)->placeDecor(id, (DecorKind)kind, (uint32_t)seed, {x, y}, angle, radius, height);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeMoveDecor(JNIEnv*, jclass, jlong h, jlong id,
                                                                                   jfloat x, jfloat y, jfloat angle) {
    engine(h)->moveDecor(id, {x, y}, angle);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeRemoveDecor(JNIEnv*, jclass, jlong h,
                                                                                     jlong id) {
    engine(h)->removeDecor(id);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeTap(JNIEnv*, jclass, jlong h, jfloat x,
                                                                             jfloat y) {
    engine(h)->tap({x, y});
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeDropFood(JNIEnv*, jclass, jlong h, jfloat x,
                                                                                  jfloat y, jint kind, jint count) {
    engine(h)->dropFood({x, y}, kind, count);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetTilt(JNIEnv*, jclass, jlong h, jfloat x,
                                                                                 jfloat y) {
    engine(h)->setTilt({x, y});
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeStepForTest(JNIEnv*, jclass, jlong h,
                                                                                     jfloat seconds) {
    engine(h)->stepForTest(seconds);
}

/** Fills out with [fps, workMs, creatures, drawn, frames, quality, vulkan, sceneW, sceneH, w, h, simTime,
 *  food, foodMg, eatenMg, hunger, audio, audioRate]. */
JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeStats(JNIEnv* env, jclass, jlong h,
                                                                               jfloatArray out) {
    EngineStats s = engine(h)->stats();
    float v[18] = {s.fps, s.workMs, (float)s.creatures, (float)s.drawn, (float)s.frames, (float)s.quality,
                   (float)s.vulkan, (float)s.sceneWidth, (float)s.sceneHeight, (float)s.width, (float)s.height,
                   s.simTime, (float)s.food, s.foodMg, s.eatenMg, s.hunger, (float)s.audio, (float)s.audioRate};
    jsize n = env->GetArrayLength(out);
    env->SetFloatArrayRegion(out, 0, n < 18 ? n : 18, v);
}

JNIEXPORT void JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSetSound(JNIEnv*, jclass, jlong h,
                                                                                  jfloat volume) {
    engine(h)->setSound(volume);
}

JNIEXPORT jstring JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeGpuName(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(engine(h)->stats().gpu.c_str());
}

/** Four doubles a decoration: id, kind, x, y. */
JNIEXPORT jdoubleArray JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeDecor(JNIEnv* env, jclass, jlong h) {
    auto v = engine(h)->decorSnapshot();
    jdoubleArray out = env->NewDoubleArray((jsize)v.size());
    env->SetDoubleArrayRegion(out, 0, (jsize)v.size(), v.data());
    return out;
}

/** Six longs a creature: id, then the raw bits of x, y, z and speed, then the behaviour. */
JNIEXPORT jlongArray JNICALL Java_com_jpcottin_tairoto_engine_NativeTank_nativeSnapshot(JNIEnv* env, jclass,
                                                                                        jlong h) {
    auto snap = engine(h)->snapshot();
    const int stride = 6;
    std::vector<jlong> v;
    v.reserve(snap.size() * stride);
    auto bits = [](float f) {
        uint32_t u;
        std::memcpy(&u, &f, 4);
        return (jlong)u;
    };
    for (auto& c : snap) {
        v.push_back(c.id);
        v.push_back(bits(c.x));
        v.push_back(bits(c.y));
        v.push_back(bits(c.z));
        v.push_back(bits(c.speed));
        v.push_back(c.behavior);
    }
    jlongArray out = env->NewLongArray((jsize)v.size());
    env->SetLongArrayRegion(out, 0, (jsize)v.size(), v.data());
    return out;
}

}  // extern "C"
