// JNI entry points for com.jpcottin.drifttank.engine.NativeTank.
#include <android/native_window_jni.h>
#include <jni.h>

#include <cstring>
#include <vector>

#include "engine/engine.h"

using namespace dt;

namespace {
Engine* engine(jlong h) { return reinterpret_cast<Engine*>(h); }
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeCreate(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeDestroy(JNIEnv*, jclass, jlong h) {
    delete engine(h);
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetSurface(JNIEnv* env, jclass, jlong h,
                                                                                    jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    engine(h)->setWindow(w);
    if (w) ANativeWindow_release(w);  // the engine holds its own reference
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetTank(JNIEnv*, jclass, jlong h, jfloat w,
                                                                                 jfloat ht, jfloat d) {
    engine(h)->setTank({w, ht, d});
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetCamera(JNIEnv*, jclass, jlong h, jfloat cx,
                                                                                   jfloat cy, jfloat pxPerMm,
                                                                                   jfloat camHeight, jfloat ox,
                                                                                   jfloat oy, jfloat outW, jfloat outH) {
    CameraParams c;
    c.center = {cx, cy};
    c.pxPerMm = pxPerMm;
    c.camHeight = camHeight;
    c.origin = {ox, oy};
    c.outSize = {outW, outH};
    engine(h)->setCamera(c);
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetQuality(JNIEnv*, jclass, jlong h, jint q) {
    engine(h)->setQuality(qualityFromId(q));
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetDeterministic(JNIEnv*, jclass, jlong h,
                                                                                          jboolean on, jlong seed) {
    engine(h)->setDeterministic(on, (uint64_t)seed);
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetPaused(JNIEnv*, jclass, jlong h,
                                                                                   jboolean paused) {
    engine(h)->setPaused(paused);
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSpawn(JNIEnv* env, jclass, jlong h, jlong id,
                                                                               jint visual, jint loco,
                                                                               jfloatArray params, jfloat length,
                                                                               jint seed, jfloat x, jfloat y, jfloat z) {
    jsize n = env->GetArrayLength(params);
    jfloat* p = env->GetFloatArrayElements(params, nullptr);
    SpeciesParams sp = SpeciesParams::fromArray(p, n);
    env->ReleaseFloatArrayElements(params, p, JNI_ABORT);
    engine(h)->spawn(id, (Visual)visual, (Locomotion)loco, sp, length, (uint32_t)seed, Vec3(x, y, z));
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeRemove(JNIEnv*, jclass, jlong h, jlong id) {
    engine(h)->remove(id);
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeClear(JNIEnv*, jclass, jlong h) {
    engine(h)->clearCreatures();
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeTap(JNIEnv*, jclass, jlong h, jfloat x,
                                                                             jfloat y) {
    engine(h)->tap({x, y});
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSetTilt(JNIEnv*, jclass, jlong h, jfloat x,
                                                                                 jfloat y) {
    engine(h)->setTilt({x, y});
}

JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeStepForTest(JNIEnv*, jclass, jlong h,
                                                                                     jfloat seconds) {
    engine(h)->stepForTest(seconds);
}

/** Fills out with [fps, workMs, creatures, drawn, frames, quality, vulkan, sceneW, sceneH, w, h, simTime]. */
JNIEXPORT void JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeStats(JNIEnv* env, jclass, jlong h,
                                                                               jfloatArray out) {
    EngineStats s = engine(h)->stats();
    float v[12] = {s.fps, s.workMs, (float)s.creatures, (float)s.drawn, (float)s.frames, (float)s.quality,
                   (float)s.vulkan, (float)s.sceneWidth, (float)s.sceneHeight, (float)s.width, (float)s.height,
                   s.simTime};
    jsize n = env->GetArrayLength(out);
    env->SetFloatArrayRegion(out, 0, n < 12 ? n : 12, v);
}

JNIEXPORT jstring JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeGpuName(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(engine(h)->stats().gpu.c_str());
}

/** Six longs a creature: id, then the raw bits of x, y, z and speed, then the behaviour. */
JNIEXPORT jlongArray JNICALL Java_com_jpcottin_drifttank_engine_NativeTank_nativeSnapshot(JNIEnv* env, jclass,
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
