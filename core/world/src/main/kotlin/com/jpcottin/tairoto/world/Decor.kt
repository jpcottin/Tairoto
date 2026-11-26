package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.random.Random

/**
 * Something that stands on the sand and does not swim: a coral head, a
 * thicket of branching coral, a giant clam, a stone. The native simulation
 * treats it as an obstacle (and some creatures as shelter); the native look is
 * picked by [nativeId].
 */
data class DecorKind(
    val id: String,
    /** The Tahitian name, checked against a reference, written with the ʻeta (U+02BB). */
    val tahitianName: String,
    val commonName: String,
    /** The genus or species it is modelled on; null for a stone. */
    val scientificName: String?,
    val description: String,
    /** Matches DecorKind in engine/src/main/cpp/sim/decor.h. */
    val nativeId: Int,
    /** Footprint radius on the sand, mm (half the length, for a clam). */
    val radiusMm: ClosedFloatingPointRange<Float>,
    /** How far its top stands above the sand, mm. */
    val heightMm: ClosedFloatingPointRange<Float>,
    /** What creatures do with it, for the picker. */
    val traits: List<String>,
) {
    init {
        require(id.matches(Regex("[a-z0-9_]+"))) { "decor id must be snake_case: $id" }
        require(radiusMm.start > 0f && radiusMm.endInclusive >= radiusMm.start)
        require(heightMm.start > 0f && heightMm.endInclusive >= heightMm.start)
        require(heightMm.endInclusive < TankSize.DEFAULT_DEPTH_MM * 0.8f) { "$id must leave room to swim over it" }
    }

    val displayName: String get() = tahitianName

    fun radiusFor(spec: DecorSpec): Float = radiusMm.start + (radiusMm.endInclusive - radiusMm.start) * spec.size
    fun heightFor(spec: DecorSpec): Float = heightMm.start + (heightMm.endInclusive - heightMm.start) * spec.size
}

/** One decoration in the lagoon: where it stands, which way it is turned, and how big it grew. */
@Serializable
data class DecorSpec(
    val id: EntityId,
    val kindId: String,
    val seed: Int,
    val at: Vec2,
    /** Radians on the table plane. */
    val angle: Float,
    /** 0 the smallest of its kind .. 1 the largest. */
    val size: Float,
) {
    init {
        require(size in 0f..1f) { "size out of range: $size" }
    }

    val kind: DecorKind get() = DecorCatalog.require(kindId)
    val radiusMm: Float get() = kind.radiusFor(this)
    val heightMm: Float get() = kind.heightFor(this)

    /** True if p (mm) is on its footprint, give or take [slopMm]. */
    fun covers(p: Vec2, slopMm: Float = 0f): Boolean = (p - at).length <= radiusMm + slopMm
}

object DecorCatalog {
    val CoralHead = DecorKind(
        id = "coral_head",
        // Pollex, TOKA.1: Tahitian toʻa, "bloc de corail, « patate » de corail" (Lemaître 1973).
        tahitianName = "Toʻa",
        commonName = "Coral head",
        scientificName = "Porites lobata",
        description = "A boulder of lobe coral, grown a few millimetres a year for centuries. Fish swim " +
            "over it, the turtle may rest on it, and the octopus makes its den against its side.",
        nativeId = 0,
        radiusMm = 22f..32f,
        heightMm = 20f..30f,
        traits = listOf("Turtles rest on it", "Octopus den"),
    )

    val BranchingCoral = DecorKind(
        id = "branching_coral",
        // "Te mau toʻapū o te tairoto", the corals of the lagoon: the bilingual panels of Pointe Vénus,
        // Haut-commissariat de la République en Polynésie française.
        tahitianName = "Toʻapū",
        commonName = "Branching coral",
        scientificName = "Acropora",
        description = "A thicket of staghorn branches, pale at their growing tips. Green chromis live " +
            "above it, and dive in among the branches the moment something frightens them.",
        nativeId = 1,
        radiusMm = 24f..34f,
        heightMm = 18f..26f,
        traits = listOf("Shelter for chromis"),
    )

    val GiantClam = DecorKind(
        id = "giant_clam",
        // Pahua: the giant clam of French Polynesia (Ministry of Marine Resources, French Polynesia;
        // Bishop Museum, Cook Islands Biodiversity: pa'ua / pahua, Tridacna maxima).
        tahitianName = "Pahua",
        commonName = "Giant clam",
        scientificName = "Tridacna maxima",
        description = "Its mantle glows blue, green or gold between fluted valves, fed by the algae " +
            "living in it. A shadow or a knock and it snaps shut with a puff of water, then opens again slowly.",
        nativeId = 2,
        radiusMm = 14f..19f,
        heightMm = 5f..7f,
        traits = listOf("Shuts when startled"),
    )

    val Stone = DecorKind(
        id = "stone",
        // Pollex: Tahitian ʔoofaʔi, "pierre" (Lemaître 1973).
        tahitianName = "ʻŌfaʻi",
        commonName = "Stone",
        scientificName = null,
        description = "A low lump of basalt crusted with pink coralline algae and fuzzed with green turf. " +
            "Fish swim over it; crabs and the octopus walk round it.",
        nativeId = 3,
        radiusMm = 11f..22f,
        heightMm = 8f..13f,
        traits = listOf("Octopus den"),
    )

    val all: List<DecorKind> = listOf(CoralHead, BranchingCoral, GiantClam, Stone)

    private val byId = all.associateBy { it.id }

    operator fun get(id: String): DecorKind? = byId[id]

    fun require(id: String): DecorKind = byId[id] ?: throw IllegalArgumentException("unknown decoration: $id")

    /** A new decoration of a kind at a point, its size and angle picked at random. */
    fun make(kind: DecorKind, at: Vec2, ids: EntityIdAllocator, random: Random): DecorSpec = DecorSpec(
        id = ids.allocate(),
        kindId = kind.id,
        seed = random.nextInt(),
        at = at,
        angle = (random.nextFloat() * 2f * PI).toFloat(),
        size = random.nextFloat(),
    )
}

/** Where the decorations of a new lagoon go. */
object DecorLayout {
    /** A new lagoon's floor: two coral heads, a branching coral, a clam and a few stones, never on top of each other. */
    val defaultKinds: List<DecorKind> = with(DecorCatalog) {
        listOf(CoralHead, BranchingCoral, CoralHead, GiantClam, Stone, Stone, Stone)
    }

    /** These kinds side by side around the middle of the lagoon (for tests and close looks). */
    fun around(kinds: List<DecorKind>, size: TankSize, ids: EntityIdAllocator, random: Random): List<DecorSpec> {
        // In a column down the middle, each clear of the one before: a phone shows them all.
        val list = kinds.take(TankScene.MAX_DECOR)
        val tall = list.sumOf { (2 * it.radiusMm.endInclusive + 6f).toDouble() }.toFloat()
        var y = size.center.y - tall / 2
        return list.mapIndexed { i, kind ->
            val r = kind.radiusMm.endInclusive
            y += r
            val at = Vec2(size.center.x + if (i % 2 == 0) -6f else 6f, y)
            y += r + 6f
            DecorCatalog.make(kind, Vec2(at.x.coerceIn(0f, size.widthMm), at.y.coerceIn(0f, size.heightMm)), ids, random)
        }
    }

    fun defaultDecor(size: TankSize, ids: EntityIdAllocator, random: Random): List<DecorSpec> {
        val placed = mutableListOf<DecorSpec>()
        for (kind in defaultKinds) {
            val r = kind.radiusMm.endInclusive
            val margin = r + 8f
            if (size.widthMm < 2 * margin || size.heightMm < 2 * margin) continue
            for (attempt in 0 until 40) {
                val at = Vec2(
                    margin + random.nextFloat() * (size.widthMm - 2 * margin),
                    margin + random.nextFloat() * (size.heightMm - 2 * margin),
                )
                if (placed.none { (it.at - at).length < it.radiusMm + r + 14f }) {
                    placed += DecorCatalog.make(kind, at, ids, random)
                    break
                }
            }
        }
        return placed
    }
}
