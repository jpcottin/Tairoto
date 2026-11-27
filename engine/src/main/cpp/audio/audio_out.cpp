#include "audio/audio_out.h"

#include <aaudio/AAudio.h>

#include "core/log.h"

namespace dt::audio {

AudioOut::~AudioOut() { stop(); }

bool AudioOut::start() {
    if (stream_) return true;
    AAudioStreamBuilder* b = nullptr;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) {
        failed_ = true;
        return false;
    }
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    AAudioStreamBuilder_setDataCallback(b, reinterpret_cast<AAudioStream_dataCallback>(&AudioOut::dataCallback), this);
    AAudioStreamBuilder_setErrorCallback(b, reinterpret_cast<AAudioStream_errorCallback>(&AudioOut::errorCallback), this);
    AAudioStream* s = nullptr;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !s) {
        DT_LOGW("no sound: %s", AAudio_convertResultToText(r));
        failed_ = true;
        return false;
    }
    sampleRate_ = AAudioStream_getSampleRate(s);
    // The synthesizer lives on across streams at the same rate, so the swell does not start over.
    if (!synth_ || (int)synth_->sampleRate() != sampleRate_) synth_ = std::make_unique<Synth>((float)sampleRate_);
    // Twice the burst: low latency, and room for a late callback.
    AAudioStream_setBufferSizeInFrames(s, AAudioStream_getFramesPerBurst(s) * 2);
    stream_ = reinterpret_cast<AAudioStreamStruct*>(s);
    lost_ = false;
    r = AAudioStream_requestStart(s);
    if (r != AAUDIO_OK) {
        DT_LOGW("sound would not start: %s", AAudio_convertResultToText(r));
        stop();
        failed_ = true;
        return false;
    }
    failed_ = false;
    DT_LOGI("sound playing at %d Hz, %d frames a burst", sampleRate_, AAudioStream_getFramesPerBurst(s));
    return true;
}

void AudioOut::stop() {
    if (!stream_) return;
    auto* s = reinterpret_cast<AAudioStream*>(stream_);
    AAudioStream_requestStop(s);
    AAudioStream_close(s);
    stream_ = nullptr;
}

void AudioOut::keepAlive() {
    if (lost_.exchange(false) && stream_) {
        DT_LOGI("sound output changed: reopening");
        stop();
        start();
    }
}

int AudioOut::dataCallback(AAudioStreamStruct*, void* user, void* data, int32_t frames) {
    auto* self = static_cast<AudioOut*>(user);
    Synth& synth = *self->synth_;
    Cue cue;
    while (self->ring_.pop(&cue)) synth.play(cue);
    synth.setVolume(self->volume_.load(std::memory_order_relaxed));
    synth.render(static_cast<float*>(data), frames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void AudioOut::errorCallback(AAudioStreamStruct*, void* user, int32_t error) {
    // Not here (this is AAudio's thread): the next frame reopens it.
    if (error == AAUDIO_ERROR_DISCONNECTED) static_cast<AudioOut*>(user)->lost_ = true;
}

}  // namespace dt::audio
