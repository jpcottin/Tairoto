package com.jpcottin.drifttank.world

/** How a creature moves. The native simulation has one controller per mode. */
enum class Locomotion(val nativeId: Int) {
    /** Free in x, y and z, driven by a tail beat (fish). */
    SWIM(0),

    /** Slow, rowing with flippers; rests on the sand and climbs up to breathe (turtle). */
    PADDLE(1),
}

/** Which water a species belongs to, so a tank stays realistic. */
enum class TankType { REEF, FRESHWATER }

/**
 * One species, described by data. The native side reads [nativeParams] and has
 * no per-species code beyond its look ([visualId]: the mesh profile and the
 * shader pattern).
 */
data class Species(
    val id: String,
    val commonName: String,
    val scientificName: String,
    val description: String,
    val tankType: TankType,
    val locomotion: Locomotion,
    val visualId: Int,
    /** Body length range on screen, nose to tail tip, in mm. */
    val lengthMm: ClosedFloatingPointRange<Float>,
    /** Cruising and burst speed in body lengths per second. */
    val cruiseBodyLengthsPerS: Float,
    val burstBodyLengthsPerS: Float,
    /** Preferred depth, as fractions of the tank depth (0 = surface, 1 = sand). */
    val depthBand: ClosedFloatingPointRange<Float>,
    /** 0 = solitary, 1 = tight school. */
    val schooling: Float,
    /** 0 = bold, 1 = flees from everything. */
    val shyness: Float,
    /** Fastest turn, in radians per second, at cruising speed. */
    val turnRateRadPerS: Float,
    /** Body width as a fraction of its length (seen from above). */
    val bodyWidth: Float,
    /** Tail beats per second at one body length per second. */
    val tailBeatHz: Float,
    /** How many come with a new tank, and how many one "add" releases. */
    val defaultCount: Int,
    val addBatch: Int,
) {
    init {
        require(id.matches(Regex("[a-z0-9_]+"))) { "species id must be snake_case: $id" }
        require(lengthMm.start > 0f && lengthMm.endInclusive >= lengthMm.start)
        require(cruiseBodyLengthsPerS > 0f && burstBodyLengthsPerS >= cruiseBodyLengthsPerS)
        require(depthBand.start in 0f..1f && depthBand.endInclusive in 0f..1f && depthBand.start <= depthBand.endInclusive)
        require(schooling in 0f..1f && shyness in 0f..1f)
        require(bodyWidth in 0.05f..1f)
        require(addBatch >= 1 && defaultCount >= 0)
    }

    /** The parameters handed to the native simulation, in [NativeParam] order. */
    fun nativeParams(): FloatArray = FloatArray(NativeParam.COUNT).also {
        it[NativeParam.CRUISE] = cruiseBodyLengthsPerS
        it[NativeParam.BURST] = burstBodyLengthsPerS
        it[NativeParam.DEPTH_MIN] = depthBand.start
        it[NativeParam.DEPTH_MAX] = depthBand.endInclusive
        it[NativeParam.SCHOOLING] = schooling
        it[NativeParam.SHYNESS] = shyness
        it[NativeParam.TURN_RATE] = turnRateRadPerS
        it[NativeParam.BODY_WIDTH] = bodyWidth
        it[NativeParam.TAIL_BEAT] = tailBeatHz
    }

    /** A creature's length, picked from the range by its seed so every device agrees. */
    fun lengthFor(seed: Int): Float {
        val t = ((seed.toLong() and 0xFFFF) / 65535.0).toFloat()
        return lengthMm.start + (lengthMm.endInclusive - lengthMm.start) * t
    }
}

/** Index of each value in [Species.nativeParams]; mirrored by `SpeciesParams` in sim/species.h. */
object NativeParam {
    const val CRUISE = 0
    const val BURST = 1
    const val DEPTH_MIN = 2
    const val DEPTH_MAX = 3
    const val SCHOOLING = 4
    const val SHYNESS = 5
    const val TURN_RATE = 6
    const val BODY_WIDTH = 7
    const val TAIL_BEAT = 8
    const val COUNT = 9
}

/** Every species the app knows. Visual ids match `Visual` in sim/species.h. */
object SpeciesCatalog {
    val GreenChromis = Species(
        id = "green_chromis",
        commonName = "Green chromis",
        scientificName = "Chromis viridis",
        description = "A small damselfish that hovers in loose, shimmering schools above the reef, " +
            "flashing apple green to blue as it turns in the light.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 0,
        lengthMm = 27f..35f,
        cruiseBodyLengthsPerS = 1.3f,
        burstBodyLengthsPerS = 5f,
        depthBand = 0.18f..0.62f,
        schooling = 0.85f,
        shyness = 0.75f,
        turnRateRadPerS = 3.0f,
        bodyWidth = 0.24f,
        tailBeatHz = 2.6f,
        defaultCount = 22,
        addBatch = 6,
    )

    val GreenSeaTurtle = Species(
        id = "green_sea_turtle",
        commonName = "Green sea turtle",
        scientificName = "Chelonia mydas",
        description = "Glides slowly on long front flippers, naps on the sand and rises to the " +
            "surface now and then to breathe. Its shell carries a sunburst on every scute.",
        tankType = TankType.REEF,
        locomotion = Locomotion.PADDLE,
        visualId = 1,
        lengthMm = 95f..120f,
        cruiseBodyLengthsPerS = 0.28f,
        burstBodyLengthsPerS = 0.9f,
        depthBand = 0.3f..0.75f,
        schooling = 0f,
        shyness = 0.2f,
        turnRateRadPerS = 0.55f,
        bodyWidth = 0.62f,
        tailBeatHz = 0.32f,
        defaultCount = 1,
        addBatch = 1,
    )

    val all: List<Species> = listOf(GreenChromis, GreenSeaTurtle)

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Species? = byId[id]

    fun require(id: String): Species = byId[id] ?: throw IllegalArgumentException("unknown species: $id")
}
