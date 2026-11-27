#pragma once
// The synthesizer on the device's speakers, through AAudio (low latency,
// float, stereo). The render thread posts cues; AAudio's own thread drains
// them into the synthesizer and renders. A stream lost to a change of
// output (headphones in or out) is reopened on the next frame.
#include <atomic>
#include <memory>

#include "audio/cues.h"
#include "audio/synth.h"

struct AAudioStreamStruct;

namespace dt::audio {

class AudioOut {
public:
    enum class State { Off = 0, Playing = 1, Unavailable = 2 };

    AudioOut() = default;
    ~AudioOut();

    /** Opens the stream and starts playing (no-op if playing). False if the device has no way out. */
    bool start();
    /** Stops and closes the stream: silence costs nothing. */
    void stop();
    /** Called each frame: reopens a stream that was lost. */
    void keepAlive();

    void post(const Cue& cue) { if (playing()) ring_.push(cue); }
    void setVolume(float v) { volume_.store(v); }
    bool playing() const { return stream_ != nullptr; }
    State state() const { return stream_ ? State::Playing : (failed_ ? State::Unavailable : State::Off); }
    int sampleRate() const { return sampleRate_; }

private:
    static int dataCallback(AAudioStreamStruct* stream, void* user, void* data, int32_t frames);
    static void errorCallback(AAudioStreamStruct* stream, void* user, int32_t error);

    AAudioStreamStruct* stream_ = nullptr;
    std::unique_ptr<Synth> synth_;
    SpscRing<Cue, 256> ring_;
    std::atomic<float> volume_{0.8f};
    std::atomic<bool> lost_{false};
    bool failed_ = false;
    int sampleRate_ = 0;
};

}  // namespace dt::audio
