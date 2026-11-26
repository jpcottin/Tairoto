#pragma once
#include "core/math.h"
#include "sim/species.h"

namespace dt {

/**
 * What a creature is doing. Fish only use Cruise and Flee. Hide is the ray
 * under the sand, the octopus camouflaged, the hermit crab in its shell;
 * Emerge is the way back out.
 */
enum class Behavior : int32_t {
    Cruise = 0, Descend = 1, Rest = 2, Surface = 3, Flee = 4, Forage = 5, Hide = 6, Emerge = 7,
};

/**
 * One creature's whole dynamic state. Positions are in mm on the table plane,
 * z in mm below the surface.
 */
struct Creature {
    static constexpr int kJoints = 12;
    static constexpr int kArms = 8;       // octopus
    static constexpr int kArmJoints = 10;
    static constexpr int kLegs = 4;       // hermit crab: two walking legs a side

    int64_t id = 0;
    Visual visual = Visual::GreenChromis;
    Locomotion loco = Locomotion::Swim;
    SpeciesParams sp;
    float length = 20;     // nose to tail tip, mm
    uint32_t seed = 0;

    Vec3 pos;              // head (fish) or shell centre (turtle)
    float heading = 0;     // radians, direction of travel on the table plane
    float speed = 0;       // mm/s along the heading
    float vz = 0;          // mm/s, positive is down
    float turnRate = 0;    // rad/s actually turning, for banking and asymmetric strokes
    float depthTarget = 0;
    float depthTimer = 0;

    // Spine (fish): joint[0] is the snout, the last is the caudal peduncle.
    Vec2 joint[kJoints];
    float segLen = 1;

    // Swimming
    float tailPhase = 0;
    float thrust = 0.5f;       // 0 coasting .. 1 full beat, drives the tail amplitude
    float burstTimer = 0;      // > 0 while bursting
    float glideTimer = 0;      // > 0 while coasting between beats
    float finAngle = 0;        // caudal fin flex, secondary motion
    float finVel = 0;
    float pectoralPhase = 0;
    float wanderSeed = 0;

    // Fright
    float startleTimer = 0;
    float startleDelay = 0;
    Vec2 startleFrom;

    // Turtle
    Behavior behavior = Behavior::Cruise;
    float stateTimer = 0;
    Vec3 target;
    float strokePhase = 0;
    float breathPhase = 0;
    float headSway = 0;
    float rest = 0;            // 0 swimming .. 1 settled on the sand (flippers tucked)

    // Feeding
    float hunger = 0.5f;       // 0 full .. 1 starving
    float biteCooldown = 0;
    uint32_t foodTarget = 0;   // id of the food being chased, 0 = none
    int scentCell = -1;        // the patch it is feeding at
    float retarget = 0;        // seconds until it looks for food again
    float eatenMg = 0;

    // Bottom dwellers
    float cover = 0;          // ray: sand over it; octopus: camouflage; crab: drawn into its shell (0 .. 1)
    float alarm = 0;          // octopus: the dark flash of fright (0 .. 1)
    float flapPhase = 0;      // ray wing beat
    float flapAmp = 0;        // 0 gliding .. 1 full beat
    float puffTimer = 0;      // seconds until the next cloud of sand
    float inkCooldown = 0;    // octopus: seconds until it can ink again
    float mantleAngle = 0;    // octopus: where the mantle points
    float jetPhase = 0;       // octopus: mantle pulses
    float gaitPhase = 0;      // octopus arm sway, crab claw picking
    int grabArm = -1;         // octopus: the arm reaching for food
    float wobble = 0;         // crab: the shell rocking after it slams in
    float threat = 0;         // crab: seconds since something big came close
    // Octopus arms: the joints (drawn), and the state that moves them: each
    // segment's angle on the table and how fast it turns.
    Vec2 arm[kArms][kArmJoints];
    float armAng[kArms][kArmJoints - 1] = {};
    float armSpin[kArms][kArmJoints - 1] = {};
    float armSeg[kArms] = {};
    float armDrag = 0.75f;  // how hard the sand or the water holds the arms back, eased
    float armCurl = 1.0f, armSpread = 1.0f, armMuscle = 50.0f, armJet = 0.0f;  // the pose, eased between behaviours
    float armLen[kArms] = {};
    Vec2 foot[kLegs];
    Vec2 footFrom[kLegs];
    float swing[kLegs] = {-1, -1, -1, -1};  // < 0 planted, 0 .. 1 stepping

    float age = 0;
};

}  // namespace dt
