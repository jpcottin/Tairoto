#pragma once
// ADPF performance hints (Android 13+): tells the scheduler how long each
// frame's work took against its budget, so it can raise or lower the CPU
// clocks before frames are dropped. Looked up at run time with dlsym, so the
// app still runs on Android 11 and 12 (minSdk 30) where it is not there.
#include <dlfcn.h>
#include <sys/types.h>
#include <unistd.h>

#include <cstdint>

#include "core/log.h"

namespace dt {

class PerfHint {
public:
    explicit PerfHint(int64_t targetNs) {
        void* lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (!lib) return;
        getManager_ = (GetManager)dlsym(lib, "APerformanceHint_getManager");
        createSession_ = (CreateSession)dlsym(lib, "APerformanceHint_createSession");
        report_ = (Report)dlsym(lib, "APerformanceHint_reportActualWorkDuration");
        update_ = (Update)dlsym(lib, "APerformanceHint_updateTargetWorkDuration");
        close_ = (Close)dlsym(lib, "APerformanceHint_closeSession");
        if (!getManager_ || !createSession_ || !report_ || !update_ || !close_) return;
        void* manager = getManager_();
        if (!manager) return;
        int32_t tid = (int32_t)gettid();
        session_ = createSession_(manager, &tid, 1, targetNs);
        target_ = targetNs;
        if (session_) DT_LOGI("ADPF hint session started, target %.1f ms", targetNs / 1e6);
    }
    ~PerfHint() {
        if (session_ && close_) close_(session_);
    }
    bool active() const { return session_ != nullptr; }
    void report(int64_t actualNs) {
        if (session_ && actualNs > 0) report_(session_, actualNs);
    }
    void setTarget(int64_t ns) {
        if (session_ && ns > 0 && ns != target_) {
            update_(session_, ns);
            target_ = ns;
        }
    }

private:
    using GetManager = void* (*)();
    using CreateSession = void* (*)(void*, const int32_t*, size_t, int64_t);
    using Report = int (*)(void*, int64_t);
    using Update = int (*)(void*, int64_t);
    using Close = void (*)(void*);
    GetManager getManager_ = nullptr;
    CreateSession createSession_ = nullptr;
    Report report_ = nullptr;
    Update update_ = nullptr;
    Close close_ = nullptr;
    void* session_ = nullptr;
    int64_t target_ = 0;
};

}  // namespace dt
