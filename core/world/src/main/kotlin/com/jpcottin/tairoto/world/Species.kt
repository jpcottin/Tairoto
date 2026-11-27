package com.jpcottin.tairoto.world

/** How a creature moves. The native simulation has one controller per mode. */
enum class Locomotion(val nativeId: Int) {
    /** Free in x, y and z, driven by a tail beat (fish). */
    SWIM(0),

    /** Slow, rowing with flippers; rests on the sand and climbs up to breathe (turtle). */
    PADDLE(1),

    /** Flaps and glides on wide wings; settles and buries itself in the sand (eagle ray). */
    GLIDE(2),

    /** Walks on its arms over the sand, jets away when frightened (octopus). */
    CRAWL(3),

    /** Steps on its legs, never leaves the sand (hermit crab). */
    WALK(4),
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
    /**
     * The Tahitian name, checked against a reference before it is used; null when none was
     * confirmed. Written with the ʻeta (U+02BB), the letter for the glottal stop, not an apostrophe.
     */
    val tahitianName: String?,
    val commonName: String,
    val scientificName: String,
    val description: String,
    val tankType: TankType,
    val locomotion: Locomotion,
    val visualId: Int,
    /**
     * Body length range on screen, in mm: nose to tail tip for a fish or a turtle, snout to the root
     * of the tail for a ray, mantle to arm tips for an octopus, the shell for a hermit crab.
     */
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
    /** Body width as a fraction of its length, seen from above (a ray's span is wider than it is long). */
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
        require(bodyWidth in 0.05f..2f) { "body width is a fraction of the length, up to a ray's span" }
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

    /** What the app calls it: the Tahitian name when there is one, the English otherwise. */
    val displayName: String get() = tahitianName ?: commonName

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
        // Tetiaroa Society, after the Guide des poissons de Tahiti et ses îles (Bacchet, Zysman, Lefèvre).
        tahitianName = "ʻAtoti",
        commonName = "Green chromis",
        scientificName = "Chromis viridis",
        description = "A small damselfish that hovers in loose, shimmering schools over the coral heads " +
            "of the lagoon, flashing apple green to blue as it turns in the light.",
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
        defaultCount = 16,
        addBatch = 6,
    )

    val ConvictSurgeonfish = Species(
        id = "convict_surgeonfish",
        // Tetiaroa Society (convict surgeonfish, manini); Bishop Museum, Cook Islands database (Manini, Tahitian).
        tahitianName = "Manini",
        commonName = "Convict surgeonfish",
        scientificName = "Acanthurus triostegus",
        description = "A pale surgeonfish striped like a convict with six thin black bars. Schools of manini " +
            "sweep over the lagoon floor together, grazing the fine algae on the sand and the coral rubble.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 7,
        lengthMm = 20f..25f,
        cruiseBodyLengthsPerS = 1.0f,
        burstBodyLengthsPerS = 4f,
        depthBand = 0.35f..0.8f,
        schooling = 0.8f,
        shyness = 0.6f,
        turnRateRadPerS = 2.6f,
        bodyWidth = 0.2f,
        tailBeatHz = 2.2f,
        smell = 0.9f,
        biteMg = 0.6f,
        messiness = 0.4f,
        stomachMg = 6f,
        hungerPerSecond = 1f / 140f,
        bottomFeeder = true,
        defaultCount = 8,
        addBatch = 5,
    )

    val BluestripeSnapper = Species(
        id = "bluestripe_snapper",
        // Bishop Museum, Cook Islands database (Taape, Tahitian); NOAA (taape, brought to Hawaiʻi from the Marquesas and Moʻorea).
        tahitianName = "Taape",
        commonName = "Bluestripe snapper",
        scientificName = "Lutjanus kasmira",
        description = "Bright yellow with electric-blue lines down its sides. By day taape hang in tight, " +
            "slow schools near the coral heads; at night they spread out over the sand to hunt.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 8,
        lengthMm = 20f..26f,
        cruiseBodyLengthsPerS = 0.9f,
        burstBodyLengthsPerS = 4.5f,
        depthBand = 0.3f..0.7f,
        schooling = 0.95f,
        shyness = 0.5f,
        turnRateRadPerS = 2.4f,
        bodyWidth = 0.22f,
        tailBeatHz = 2.2f,
        smell = 1.1f,
        biteMg = 0.8f,
        messiness = 0.3f,
        stomachMg = 8f,
        hungerPerSecond = 1f / 160f,
        bottomFeeder = false,
        defaultCount = 9,
        addBatch = 5,
    )

    val YellowstripeGoatfish = Species(
        id = "yellowstripe_goatfish",
        // Tetiaroa Society (yellowstripe goatfish, ʻouma). The Bishop Museum database gives vete.
        tahitianName = "ʻOuma",
        commonName = "Yellowstripe goatfish",
        scientificName = "Mulloidichthys flavolineatus",
        description = "A slender silver fish with a yellow stripe from eye to tail. ʻOuma rest in loose " +
            "schools over the sand, then fan out to feel for food in it with the two barbels under their chin.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 9,
        lengthMm = 22f..28f,
        cruiseBodyLengthsPerS = 0.8f,
        burstBodyLengthsPerS = 4f,
        depthBand = 0.7f..0.92f,
        schooling = 0.75f,
        shyness = 0.55f,
        turnRateRadPerS = 2.2f,
        bodyWidth = 0.18f,
        tailBeatHz = 2f,
        smell = 1.3f,
        biteMg = 0.6f,
        messiness = 0.25f,
        stomachMg = 7f,
        hungerPerSecond = 1f / 150f,
        bottomFeeder = true,
        defaultCount = 6,
        addBatch = 4,
    )

    val ScissortailSergeant = Species(
        id = "scissortail_sergeant",
        // Bishop Museum, Cook Islands database (Mamo, Tahitian, for Abudefduf sexfasciatus).
        tahitianName = "Mamo",
        commonName = "Scissortail sergeant",
        scientificName = "Abudefduf sexfasciatus",
        description = "A damselfish with five broad black bars and a black stripe down each lobe of its " +
            "tail, like open scissors. Mamo school high in the water and are first to any food that falls in.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 10,
        lengthMm = 16f..20f,
        cruiseBodyLengthsPerS = 1.2f,
        burstBodyLengthsPerS = 4.8f,
        depthBand = 0.1f..0.5f,
        schooling = 0.85f,
        shyness = 0.65f,
        turnRateRadPerS = 2.9f,
        bodyWidth = 0.24f,
        tailBeatHz = 2.5f,
        smell = 1f,
        biteMg = 0.5f,
        messiness = 0.4f,
        stomachMg = 4.5f,
        hungerPerSecond = 1f / 140f,
        bottomFeeder = false,
        defaultCount = 8,
        addBatch = 5,
    )

    val GreenSeaTurtle = Species(
        id = "green_sea_turtle",
        // Tetiaroa Society; NOAA (Society Islands: Honu).
        tahitianName = "Honu",
        commonName = "Green sea turtle",
        scientificName = "Chelonia mydas",
        description = "The most common turtle of French Polynesia. It glides on long front flippers, naps on " +
            "the lagoon sand and rises to breathe; its shell carries a sunburst on every scute.",
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

    val SpottedEagleRay = Species(
        id = "spotted_eagle_ray",
        // Tetiaroa Society (spotted eagle ray, fai manu); the Pacific species is Aetobatus ocellatus.
        tahitianName = "Fai manu",
        commonName = "Ocellated eagle ray",
        scientificName = "Aetobatus ocellatus",
        description = "The ray that flies: it beats its wide wings like a bird over the sand, a dark back " +
            "covered in white spots, a tail like a whip. It digs in the sand for shellfish and lies under it to rest.",
        tankType = TankType.REEF,
        locomotion = Locomotion.GLIDE,
        visualId = 3,
        lengthMm = 30f..36f,
        cruiseBodyLengthsPerS = 0.5f,
        burstBodyLengthsPerS = 2.2f,
        depthBand = 0.35f..0.8f,
        schooling = 0f,
        shyness = 0.55f,
        turnRateRadPerS = 0.7f,
        bodyWidth = 1.8f,
        tailBeatHz = 0.45f,
        smell = 0.9f,
        biteMg = 1.2f,
        messiness = 0.5f,
        stomachMg = 20f,
        hungerPerSecond = 1f / 300f,
        bottomFeeder = true,
        defaultCount = 1,
        addBatch = 1,
    )

    val DayOctopus = Species(
        id = "day_octopus",
        // Pollex, FEKE (Tahitian feʻe, octopus); Octopus cyanea is the reef octopus of French Polynesia.
        tahitianName = "Feʻe",
        commonName = "Day octopus",
        scientificName = "Octopus cyanea",
        description = "Hunts by day, walking over the sand on its arms. It takes on the colours of what it " +
            "sits on, flashes dark when frightened, and leaves a cloud of ink behind as it jets away.",
        tankType = TankType.REEF,
        locomotion = Locomotion.CRAWL,
        visualId = 4,
        lengthMm = 30f..38f,
        cruiseBodyLengthsPerS = 0.22f,
        burstBodyLengthsPerS = 3f,
        depthBand = 0.92f..1f,
        schooling = 0f,
        shyness = 0.8f,
        turnRateRadPerS = 1.4f,
        bodyWidth = 0.45f,
        tailBeatHz = 0.9f,
        smell = 1.2f,
        biteMg = 0.8f,
        messiness = 0.2f,
        stomachMg = 12f,
        hungerPerSecond = 1f / 250f,
        bottomFeeder = true,
        defaultCount = 1,
        addBatch = 1,
    )

    val HermitCrab = Species(
        id = "hermit_crab",
        // Tetiaroa Society, invertebrates (uʻa, hermit crab).
        tahitianName = "Uʻa",
        commonName = "White-spotted hermit crab",
        scientificName = "Dardanus megistos",
        description = "A red hermit crab with black-ringed white spots, carrying a borrowed turban shell. It " +
            "scavenges whatever reaches the sand and slams into its shell at the first knock.",
        tankType = TankType.REEF,
        locomotion = Locomotion.WALK,
        visualId = 5,
        lengthMm = 13f..17f,
        cruiseBodyLengthsPerS = 0.35f,
        burstBodyLengthsPerS = 0.8f,
        depthBand = 1f..1f,
        schooling = 0f,
        shyness = 0.9f,
        turnRateRadPerS = 1.6f,
        bodyWidth = 0.7f,
        tailBeatHz = 2.2f,
        smell = 1f,
        biteMg = 0.25f,
        messiness = 0.45f,
        stomachMg = 3f,
        hungerPerSecond = 1f / 200f,
        bottomFeeder = true,
        defaultCount = 2,
        addBatch = 1,
    )

    val RaccoonButterflyfish = Species(
        id = "raccoon_butterflyfish",
        // Tetiaroa Society (raccoon butterflyfish, paraharaha); paraharaha names the butterflyfish in general
        // (GBIF, Chaetodon quadrimaculatus; Bishop Museum, Cook Islands database, for several Chaetodon).
        tahitianName = "Paraharaha",
        commonName = "Raccoon butterflyfish",
        scientificName = "Chaetodon lunula",
        description = "Golden yellow, with a black mask through the eye and a white band behind it, like a " +
            "raccoon. Paraharaha go two by two among the coral heads, picking at them, and are busiest at dusk.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 16,
        lengthMm = 24f..30f,
        cruiseBodyLengthsPerS = 0.8f,
        burstBodyLengthsPerS = 3.5f,
        depthBand = 0.3f..0.65f,
        schooling = 0.45f,
        shyness = 0.55f,
        turnRateRadPerS = 3.2f,
        // A disc on edge: seen from above, a narrow fish.
        bodyWidth = 0.17f,
        tailBeatHz = 2.0f,
        smell = 1f,
        biteMg = 0.7f,
        messiness = 0.3f,
        stomachMg = 7f,
        hungerPerSecond = 1f / 150f,
        bottomFeeder = false,
        defaultCount = 2,
        addBatch = 2,
    )

    val BlacktipReefShark = Species(
        id = "blacktip_reef_shark",
        // Tetiaroa Society (blacktip reef shark, maʻo mauri); Bishop Museum, Cook Islands database
        // (Mao Mauri, Tahitian); DIREN Polynésie française, Reconnaître les raies et requins du fenua (mauri).
        tahitianName = "Maʻo mauri",
        commonName = "Blacktip reef shark",
        scientificName = "Carcharhinus melanopterus",
        description = "A small reef shark, grey-brown with black-tipped fins. Maʻo mauri patrol the lagoon " +
            "shallows in slow, sweeping loops: shy of people, curious about anything that falls into the water.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 17,
        lengthMm = 110f..130f,
        cruiseBodyLengthsPerS = 0.45f,
        burstBodyLengthsPerS = 2.0f,
        depthBand = 0.35f..0.8f,
        schooling = 0f,
        shyness = 0.15f,
        turnRateRadPerS = 1.1f,
        bodyWidth = 0.15f,
        tailBeatHz = 1.1f,
        smell = 1.4f,
        biteMg = 3f,
        messiness = 0.4f,
        stomachMg = 60f,
        hungerPerSecond = 1f / 240f,
        bottomFeeder = false,
        defaultCount = 1,
        addBatch = 1,
    )

    /**
     * A fish someone coloured in on the printed page and scanned: it wears its
     * drawing ([CreatureSpec.skin]) and swims like a calm lagoon fish. Not in
     * [all]: it is never released from the list, only from a drawing.
     */
    val Drawn = Species(
        id = "drawn_fish",
        tahitianName = null,
        commonName = "Your fish",
        scientificName = "Piscis pictus",
        description = "A fish you coloured in yourself, swimming with the others.",
        tankType = TankType.REEF,
        locomotion = Locomotion.SWIM,
        visualId = 15,
        lengthMm = 36f..42f,
        cruiseBodyLengthsPerS = 0.75f,
        burstBodyLengthsPerS = 3.2f,
        // Near the top, where the water blurs it least.
        depthBand = 0.12f..0.45f,
        schooling = 0.25f,
        shyness = 0.3f,
        turnRateRadPerS = 2.0f,
        // Drawn from the side, shown from above: as wide as the page's outline, half its length.
        bodyWidth = 0.5f,
        tailBeatHz = 1.7f,
        smell = 1f,
        biteMg = 0.9f,
        messiness = 0.35f,
        stomachMg = 10f,
        hungerPerSecond = 1f / 170f,
        bottomFeeder = false,
        defaultCount = 0,
        addBatch = 1,
    )

    val all: List<Species> = listOf(
        GreenChromis, ConvictSurgeonfish, BluestripeSnapper, YellowstripeGoatfish, ScissortailSergeant,
        RaccoonButterflyfish, BlacktipReefShark,
        GreenSeaTurtle, SpottedEagleRay, DayOctopus, HermitCrab,
    )

    private val byId = (all + Drawn).associateBy { it.id }

    operator fun get(id: String): Species? = byId[id]

    fun require(id: String): Species = byId[id] ?: throw IllegalArgumentException("unknown species: $id")
}
