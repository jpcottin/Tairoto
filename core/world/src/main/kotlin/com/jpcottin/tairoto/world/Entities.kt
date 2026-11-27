package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.random.Random

/** A device in a session. Slot 0 is the host; a device on its own is the host of a session of one. */
@Serializable
data class DeviceId(val slot: Int, val name: String) {
    init {
        require(slot in 0..MAX_SLOT) { "device slot out of range: $slot" }
    }

    companion object {
        const val MAX_SLOT = 0xFFFF
        val Solo = DeviceId(0, "this device")
    }
}

/**
 * The id of anything in the tank. The top bits are the slot of the device
 * that created it, so devices mint ids without asking the host.
 */
@Serializable
@JvmInline
value class EntityId(val raw: Long) {
    val creatorSlot: Int get() = (raw ushr SLOT_SHIFT).toInt()
    val serial: Long get() = raw and SERIAL_MASK

    override fun toString(): String = "E$creatorSlot:$serial"

    companion object {
        const val SLOT_SHIFT = 40
        const val SERIAL_MASK = (1L shl SLOT_SHIFT) - 1
    }
}

/** Mints [EntityId]s for one device. Not thread-safe: one per session, on its thread. */
class EntityIdAllocator(private val slot: Int, private var next: Long = 1L) {
    init {
        require(slot in 0..DeviceId.MAX_SLOT)
        require(next in 1..EntityId.SERIAL_MASK)
    }

    fun allocate(): EntityId {
        check(next <= EntityId.SERIAL_MASK) { "device $slot ran out of ids" }
        return EntityId((slot.toLong() shl EntityId.SLOT_SHIFT) or next++)
    }

    /** Carries on after the highest serial in a restored tank, so ids are never reused. */
    fun advancePast(ids: Iterable<EntityId>) {
        val highest = ids.filter { it.creatorSlot == slot }.maxOfOrNull { it.serial } ?: return
        if (highest >= next) next = highest + 1
    }
}

/**
 * Everything that never changes about a creature. Its motion lives in the
 * native simulation, on the device that owns it.
 */
@Serializable
data class CreatureSpec(
    val id: EntityId,
    val speciesId: String,
    val seed: Int,
    val lengthMm: Float,
    /** Where it was released: x and y in mm, depth in mm below the surface. */
    val spawnAt: Vec2,
    val spawnDepthMm: Float,
    /** The slot of the device whose own lagoon it came from, visiting a shared one: it goes home with it. */
    val home: Int? = null,
    /** A fish someone drew: the [Skin] of the lagoon that carries its colours. */
    val skin: String? = null,
)

/** The tank as a list of what is in it. */
@Serializable
data class TankScene(
    val size: TankSize,
    val creatures: List<CreatureSpec> = emptyList(),
    /** What stands on the sand: coral heads, branching coral, clams, stones. */
    val decor: List<DecorSpec> = emptyList(),
    /** The drawings its drawn fish wear, each once however many fish wear it. */
    val skins: List<Skin> = emptyList(),
) {
    fun skin(id: String?): Skin? = id?.let { i -> skins.firstOrNull { it.id == i } }

    /**
     * The scene carrying [skin] as well, making room by forgetting drawings no
     * fish wears any more; null when [MAX_SKINS] drawings are all worn.
     */
    fun withSkin(skin: Skin): TankScene? {
        if (skins.any { it.id == skin.id }) return this
        val worn = skinsWorn()
        if (worn.size >= MAX_SKINS) return null
        return copy(skins = worn + skin)
    }

    /** Only the drawings a fish wears: what is worth saving or sending. */
    fun withoutUnusedSkins(): TankScene = skinsWorn().let { if (it.size == skins.size) this else copy(skins = it) }

    private fun skinsWorn(): List<Skin> {
        val used = creatures.mapNotNullTo(HashSet()) { it.skin }
        return skins.filter { it.id in used }
    }

    fun count(speciesId: String): Int = creatures.count { it.speciesId == speciesId }
    fun countDecor(kindId: String): Int = decor.count { it.kindId == kindId }
    fun decor(id: EntityId): DecorSpec? = decor.firstOrNull { it.id == id }
    fun creature(id: EntityId): CreatureSpec? = creatures.firstOrNull { it.id == id }

    /** Every id in the scene, creatures and decorations, so new ones never reuse one. */
    val allIds: List<EntityId> get() = creatures.map { it.id } + decor.map { it.id }

    companion object {
        /** A soft cap that keeps the frame rate up on modest phones. */
        const val MAX_CREATURES = 160
        /** Enough to build a reef, few enough to leave sand to walk on. */
        const val MAX_DECOR = 24
        /** Drawn fish in one lagoon: the engine keeps this many drawings on the GPU. */
        const val MAX_SKINS = 16
    }
}

/** Builds creatures at sensible places for their species. */
object Population {
    /** A new lagoon: every reef species in its default number, on a floor of coral, a clam and stones. */
    fun defaultScene(size: TankSize, ids: EntityIdAllocator, random: Random): TankScene {
        val decor = DecorLayout.defaultDecor(size, ids, random)
        val creatures = SpeciesCatalog.all.filter { it.tankType == TankType.REEF }.flatMap { species ->
            release(species, species.defaultCount, size, ids, random)
        }
        return TankScene(size, creatures, decor)
    }

    /** A lagoon of just these creatures, species id to count (for tests and close looks). */
    fun sceneOf(counts: Map<String, Int>, size: TankSize, ids: EntityIdAllocator, random: Random): TankScene =
        TankScene(size, counts.flatMap { (id, n) ->
            release(SpeciesCatalog.require(id), n, size, ids, random, around = Vec2(size.widthMm / 2f, size.heightMm / 2f))
        })

    /**
     * [count] creatures of [species]. Schooling species are released together
     * around one point (near [around] if given), the others anywhere.
     */
    fun release(
        species: Species,
        count: Int,
        size: TankSize,
        ids: EntityIdAllocator,
        random: Random,
        around: Vec2? = null,
    ): List<CreatureSpec> {
        require(count >= 0)
        val margin = minOf(size.widthMm, size.heightMm) * 0.12f
        fun inside(p: Vec2) = Vec2(
            p.x.coerceIn(margin, size.widthMm - margin),
            p.y.coerceIn(margin, size.heightMm - margin),
        )
        fun anywhere() = Vec2(
            margin + random.nextFloat() * (size.widthMm - 2 * margin),
            margin + random.nextFloat() * (size.heightMm - 2 * margin),
        )
        val schoolCenter = inside(around ?: anywhere())
        return List(count) {
            val seed = random.nextInt()
            val length = species.lengthFor(seed)
            val at = if (species.schooling > 0.5f) {
                val spread = length * 4f
                inside(schoolCenter + Vec2((random.nextFloat() - 0.5f) * 2 * spread, (random.nextFloat() - 0.5f) * 2 * spread))
            } else {
                inside(around?.let { it + Vec2((random.nextFloat() - 0.5f) * length, (random.nextFloat() - 0.5f) * length) } ?: anywhere())
            }
            val band = species.depthBand
            val depth = size.depthMm * (band.start + random.nextFloat() * (band.endInclusive - band.start))
            CreatureSpec(ids.allocate(), species.id, seed, length, at, depth)
        }
    }
}
