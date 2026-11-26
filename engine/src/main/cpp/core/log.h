#pragma once
// Logging to logcat (tag DriftTank) on Android, to stderr on the host.
#if defined(__ANDROID__)
#include <android/log.h>
#define DT_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "DriftTank", __VA_ARGS__)
#define DT_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "DriftTank", __VA_ARGS__)
#define DT_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DriftTank", __VA_ARGS__)
#else
#include <cstdio>
#define DT_LOGI(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define DT_LOGW(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define DT_LOGE(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif
