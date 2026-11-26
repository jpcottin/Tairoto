#pragma once
// The engine: one render thread that owns the simulation and the renderer.
// The UI talks to it only through commands and reads it only through stats,
// so nothing is shared but two small mutex-guarded structures.
#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "render/camera.h"
#include "render/mesh.h"
#include "render/quality.h"
#include "render/renderer.h"
#include "sim/world.h"

struct ANativeWindow;

namespace dt {


class PerfHint;

struct EngineStats {
    float fps = 0;
    float workMs = 0;       // CPU per frame: simulation, meshes, recording
    int creatures = 0;
    int drawn = 0;
    uint64_t frames = 0;
    int quality = 1;
    int vulkan = 0;         // 0 waiting, 1 rendering, 2 not supported
    int sceneWidth = 0, sceneHeight = 0;
    int width = 0, height = 0;
    float simTime = 0;
    int food = 0;
    float foodMg = 0;
    float eatenMg = 0;
    float hunger = 0;       // mean over the creatures
    std::string gpu;
};

/** One creature's place, for tests and for saving. */
struct CreatureSnapshot {
    int64_t id;
    float x, y, z, heading, speed;
    int behavior;
};

class Engine {
public:
    Engine();
    ~Engine();

    /** Gives the engine a window to draw into (or takes it away with null). Blocks until done. */
    void setWindow(ANativeWindow* window);

    void setTank(const Tank& t);
    void setCamera(const CameraParams& c);
    void setQuality(Quality q);
    /** Fixed 1/60 s steps and a fixed seed, so frames are the same every run. */
    void setDeterministic(bool on, uint64_t seed);
    void setPaused(bool paused);
    void spawn(int64_t id, Visual v, Locomotion l, const SpeciesParams& sp, float length, uint32_t seed, Vec3 at);
    void remove(int64_t id);
    void clearCreatures();
    void placeDecor(int64_t id, DecorKind kind, uint32_t seed, Vec2 at, float angle, float radius, float height);
    void moveDecor(int64_t id, Vec2 at, float angle);
    void removeDecor(int64_t id);
    /** A tap on the glass at a point of the water surface. */
    void tap(Vec2 world);
    /** Drops food at a point of the surface (kind: 0 flake, 1 pellet). */
    void dropFood(Vec2 world, int kind, int count);
    void setTilt(Vec2 tilt);

    /** Advances the simulation without drawing, on the calling thread's behalf (tests). Blocks. */
    void stepForTest(float seconds);

    EngineStats stats();
    std::vector<CreatureSnapshot> snapshot();

private:
    void post(std::function<void()> fn);
    void run();
    void runOnThread(std::function<void()> fn);  // posts and waits
    void frame();

    std::thread thread_;
    std::mutex mutex_;
    std::condition_variable wake_;
    std::deque<std::function<void()>> queue_;
    bool quit_ = false;

    // Render-thread state.
    World world_;
    MeshBuilder meshBuilder_;
    Mesh mesh_;
    Renderer* renderer_ = nullptr;
    PerfHint* perf_ = nullptr;
    ANativeWindow* window_ = nullptr;
    CameraParams camera_;
    Quality quality_ = Quality::High;
    bool deterministic_ = false;
    bool paused_ = false;
    Ripple ripples_[8];
    int nextRipple_ = 0;
    Vec2 tilt_;
    int64_t lastFrameNs_ = 0;
    double fpsWindowStart_ = 0;
    int fpsFrames_ = 0;
    double workAccum_ = 0;

    std::mutex statsMutex_;
    EngineStats stats_;
    std::vector<CreatureSnapshot> snapshot_;
};

}  // namespace dt
