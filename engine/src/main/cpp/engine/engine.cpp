#include "engine/engine.h"

#include <android/native_window.h>

#include <chrono>
#include <future>

#include "core/log.h"
#include "engine/perf_hint.h"
#include "render/renderer.h"

namespace dt {
namespace {
int64_t nowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
}  // namespace

Engine::Engine() {
    renderer_ = new Renderer();
    thread_ = std::thread([this] { run(); });
}

Engine::~Engine() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        quit_ = true;
    }
    wake_.notify_all();
    if (thread_.joinable()) thread_.join();
    delete renderer_;
}

void Engine::post(std::function<void()> fn) {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        queue_.push_back(std::move(fn));
    }
    wake_.notify_all();
}

void Engine::runOnThread(std::function<void()> fn) {
    auto done = std::make_shared<std::promise<void>>();
    auto f = done->get_future();
    post([fn = std::move(fn), done] {
        fn();
        done->set_value();
    });
    f.wait();
}

void Engine::setWindow(ANativeWindow* window) {
    if (window) ANativeWindow_acquire(window);
    // Blocks: when the surface goes away the window must be released before
    // the UI thread returns from its callback.
    runOnThread([this, window] {
        if (window_) {
            renderer_->detach();
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
        if (window) {
            window_ = window;
            if (!renderer_->attach(window)) DT_LOGW("renderer attach failed; will retry");
            lastFrameNs_ = 0;
        }
    });
}

void Engine::setTank(const Tank& t) { post([this, t] { world_.setTank(t); }); }
void Engine::setCamera(const CameraParams& c) { post([this, c] { camera_ = c; }); }
void Engine::setQuality(Quality q) { post([this, q] { quality_ = q; }); }
void Engine::setPaused(bool p) { post([this, p] { paused_ = p; lastFrameNs_ = 0; }); }
void Engine::setTilt(Vec2 t) { post([this, t] { tilt_ = t; }); }

void Engine::setDeterministic(bool on, uint64_t seed) {
    post([this, on, seed] {
        deterministic_ = on;
        world_.setSeed(seed);
    });
}

void Engine::spawn(int64_t id, Visual v, Locomotion l, const SpeciesParams& sp, float length, uint32_t seed, Vec3 at) {
    post([=] { world_.spawn(id, v, l, sp, length, seed, at); });
}

void Engine::remove(int64_t id) { post([this, id] { world_.remove(id); }); }
void Engine::clearCreatures() { post([this] { world_.clear(); }); }

void Engine::placeDecor(int64_t id, DecorKind kind, uint32_t seed, Vec2 at, float angle, float radius, float height) {
    post([=] { world_.placeDecor(id, kind, seed, at, angle, radius, height); });
}
void Engine::moveDecor(int64_t id, Vec2 at, float angle) { post([=] { world_.moveDecor(id, at, angle); }); }
void Engine::removeDecor(int64_t id) { post([=] { world_.removeDecor(id); }); }

void Engine::tap(Vec2 world) {
    post([this, world] {
        world_.startle(world);
        Ripple& r = ripples_[nextRipple_];
        nextRipple_ = (nextRipple_ + 1) % Renderer::kMaxRipples;
        r.at = world;
        r.start = world_.time();
        r.amplitude = 1.0f;
    });
}

void Engine::dropFood(Vec2 world, int kind, int count) {
    post([this, world, kind, count] {
        world_.dropFood(world, kind == 1 ? FoodKind::Pellet : FoodKind::Flake, count);
        // A small ring where it lands.
        Ripple& r = ripples_[nextRipple_];
        nextRipple_ = (nextRipple_ + 1) % Renderer::kMaxRipples;
        r.at = world;
        r.start = world_.time();
        r.amplitude = kind == 1 ? 0.5f : 0.25f;
    });
}

void Engine::stepForTest(float seconds) {
    runOnThread([this, seconds] {
        int steps = (int)(seconds * 60.0f + 0.5f);
        for (int i = 0; i < steps; i++) world_.step(1.0f / 60.0f);
    });
}

EngineStats Engine::stats() {
    // The snapshot and the counts are refreshed by the render thread; when it is
    // idle (no window) refresh them here through it so tests see fresh values.
    runOnThread([this] {
        std::lock_guard<std::mutex> lock(statsMutex_);
        stats_.creatures = (int)world_.creatures().size();
        stats_.simTime = world_.time();
        stats_.food = (int)world_.food().size();
        stats_.foodMg = (float)world_.foodMass();
        stats_.eatenMg = (float)world_.ledger().eaten;
        float hunger = 0;
        for (auto& c : world_.creatures()) hunger += c.hunger;
        stats_.hunger = world_.creatures().empty() ? 0.0f : hunger / world_.creatures().size();
        stats_.quality = (int)quality_;
        stats_.vulkan = renderer_->unsupported() ? 2 : (renderer_->ready() ? 1 : 0);
    });
    std::lock_guard<std::mutex> lock(statsMutex_);
    return stats_;
}

std::vector<CreatureSnapshot> Engine::snapshot() {
    std::vector<CreatureSnapshot> out;
    runOnThread([this, &out] {
        for (const Creature& c : world_.creatures())
            out.push_back({c.id, c.pos.x, c.pos.y, c.pos.z, c.heading, c.speed, (int)c.behavior});
    });
    return out;
}

void Engine::run() {
    while (true) {
        std::deque<std::function<void()>> work;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            bool canDraw = window_ && !paused_ && !renderer_->unsupported();
            if (queue_.empty() && !quit_) {
                if (!canDraw) wake_.wait(lock);
                else if (renderer_->needsRecovery()) wake_.wait_for(lock, std::chrono::milliseconds(250));
            }
            if (quit_) break;
            work.swap(queue_);
        }
        for (auto& fn : work) fn();
        if (window_ && !paused_) {
            if (renderer_->needsRecovery()) renderer_->tryRecover();
            if (renderer_->ready()) frame();
        }
    }
    renderer_->cleanup();
    if (window_) ANativeWindow_release(window_);
    window_ = nullptr;
    delete perf_;
    perf_ = nullptr;
}

void Engine::frame() {
    int64_t start = nowNs();
    float dt;
    if (deterministic_) {
        dt = 1.0f / 60.0f;
    } else {
        dt = lastFrameNs_ ? (float)((start - lastFrameNs_) / 1e9) : 1.0f / 60.0f;
        dt = clamp(dt, 0.0f, 0.1f);
    }
    lastFrameNs_ = start;

    world_.step(dt);
    camera_.outSize = Vec2((float)renderer_->width(), (float)renderer_->height());
    meshBuilder_.build(world_, camera_, mesh_);

    FrameInput in;
    in.camera = camera_;
    in.tank = world_.tank();
    in.time = world_.time();
    in.quality = quality_;
    in.mesh = &mesh_;
    in.ripples = ripples_;
    in.tilt = tilt_;
    int64_t beforeDraw = nowNs();
    bool shown = renderer_->draw(in);
    int64_t end = nowNs();

    // Work is the CPU time of this frame, not counting the wait for the next
    // swapchain image (that is the display pacing us, not work).
    double work = (double)(beforeDraw - start) + (double)(end - beforeDraw) * 0.35;
    if (!perf_) perf_ = new PerfHint(16'666'667);
    perf_->report((int64_t)work);

    fpsFrames_ += shown ? 1 : 0;
    workAccum_ += work;
    double t = end / 1e9;
    if (fpsWindowStart_ == 0) fpsWindowStart_ = t;
    if (t - fpsWindowStart_ >= 0.5) {
        std::lock_guard<std::mutex> lock(statsMutex_);
        double span = t - fpsWindowStart_;
        stats_.fps = (float)(fpsFrames_ / span);
        stats_.workMs = fpsFrames_ ? (float)(workAccum_ / fpsFrames_ / 1e6) : 0.0f;
        fpsFrames_ = 0;
        workAccum_ = 0;
        fpsWindowStart_ = t;
    }
    std::lock_guard<std::mutex> lock(statsMutex_);
    stats_.frames = renderer_->framesPresented();
    stats_.drawn = mesh_.creaturesDrawn;
    stats_.creatures = (int)world_.creatures().size();
    stats_.sceneWidth = renderer_->sceneWidth();
    stats_.sceneHeight = renderer_->sceneHeight();
    stats_.width = renderer_->width();
    stats_.height = renderer_->height();
    stats_.gpu = renderer_->gpuName();
    stats_.quality = (int)quality_;
    stats_.vulkan = 1;
    stats_.simTime = world_.time();
    stats_.food = (int)world_.food().size();
    stats_.foodMg = (float)world_.foodMass();
    stats_.eatenMg = (float)world_.ledger().eaten;
}

}  // namespace dt
