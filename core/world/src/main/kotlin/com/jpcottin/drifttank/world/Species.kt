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
    /** How far food is smelled, relative to the others (1 = a keen nose). */
    val smell: Float,
    /** Food taken in one bite, in mg. */
    val biteMg: Float,
    /** Fraction of what is left of a piece that breaks off as crumbs at each bite. */
    val messiness: Float,
    /** Food that takes it from starving (hunger 1) to full (hunger 0), in mg. */
    val stomachMg: Float,
    /** Hunger gained per second; 0 = full, 1 = starving. */
    val hungerPerSecond: Float,
    /** Eats what has sunk to the sand, not only what drifts in the water. */
    val bottomFeeder: Boolean,
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
        require(smell >= 0f && biteMg > 0f && messiness in 0f..1f && stomachMg > 0f && hungerPerSecond >= 0f)
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
        it[NativeParam.SMELL] = smell
        it[NativeParam.BITE_MG] = biteMg
        it[NativeParam.MESSINESS] = messiness
        it[NativeParam.STOMACH_MG] = stomachMg
        it[NativeParam.HUNGER_PER_S] = hungerPerSecond
        it[NativeParam.BOTTOM_FEEDER] = if (bottomFeeder) 1f else 0f
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
    const val SMELL = 9
    const val BITE_MG = 10
    const val MESSINESS = 11
    const val STOMACH_MG = 12
    const val HUNGER_PER_S = 13
    const val BOTTOM_FEEDER = 14
    const val COUNT = 15
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
        lengthMm = 13.5f..17.5f,
        cruiseBodyLengthsPerS = 1.3f,
        burstBodyLengthsPerS = 5f,
        depthBand = 0.18f..0.62f,
        schooling = 0.85f,
        shyness = 0.75f,
        turnRateRadPerS = 3.0f,
        bodyWidth = 0.24f,
        tailBeatHz = 2.6f,
        smell = 1f,
        biteMg = 0.45f,
        messiness = 0.35f,
        stomachMg = 4f,
        hungerPerSecond = 1f / 150f,
        bottomFeeder = false,
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
        lengthMm = 48f..60f,
        cruiseBodyLengthsPerS = 0.28f,
        burstBodyLengthsPerS = 0.9f,
        depthBand = 0.3f..0.75f,
        schooling = 0f,
        shyness = 0.2f,
        turnRateRadPerS = 0.55f,
        bodyWidth = 0.62f,
        tailBeatHz = 0.32f,
        smell = 0.8f,
        biteMg = 3.5f,
        messiness = 0.6f,
        stomachMg = 40f,
        hungerPerSecond = 1f / 400f,
        bottomFeeder = true,
        defaultCount = 1,
        addBatch = 1,
    )

    val all: List<Species> = listOf(GreenChromis, GreenSeaTurtle)

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): Species? = byId[id]

    fun require(id: String): Species = byId[id] ?: throw IllegalArgumentException("unknown species: $id")
}
