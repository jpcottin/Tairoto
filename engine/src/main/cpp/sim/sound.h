#pragma once
// What the lagoon sounds like, as the simulation hears it: things that happen
// at a place, with a strength. The engine turns those on this screen into
// cues for the synthesizer (audio/), panned to where they are on the glass.
#include <cstdint>

#include "core/math.h"

namespace dt {

enum class Sound : int32_t {
    Tap = 0,       // a finger knocks on the glass
    Flakes = 1,    // a pinch of flakes lands on the water (strength: how many, 0..1)
    Pellet = 2,    // a pellet plops in
    Bite = 3,      // a fish snaps at food (strength: the bite's size)
    ClamShut = 4,  // the giant clam claps its valves shut
    Sand = 5,      // sand puffed up: a ray settling, a crab digging, a startled fish
    Ink = 6,       // the octopus inks and jets away
    Breath = 7,    // the turtle breathes at the surface
    Scatter = 8,   // a school darts off at once: the rush of many tails (strength: how many)
};

struct SoundEvent {
    Sound kind = Sound::Tap;
    Vec3 at;            // mm on the table, z the depth
    float strength = 1;  // 0..1
};

}  // namespace dt
