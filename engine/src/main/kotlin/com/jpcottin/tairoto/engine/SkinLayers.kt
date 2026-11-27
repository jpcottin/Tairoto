package com.jpcottin.tairoto.engine

/**
 * Which layer of the renderer's drawings each drawing sits in. A drawing
 * keeps its layer while a fish wears it; a layer no fish wears is reused for
 * a new drawing, the longest unworn first. Not thread-safe: the engine's
 * caller drives it.
 */
class SkinLayers(private val capacity: Int = MAX) {
    private val layerOf = HashMap<String, Int>()
    private val skinIn = arrayOfNulls<String>(capacity)
    private val wearers = IntArray(capacity)
    private val idleSince = LongArray(capacity)
    private val wornBy = HashMap<Long, Int>()
    private var clock = 0L

    /** The layer for a drawing, and whether it must be uploaded; null when every layer is worn. */
    fun place(skinId: String): Pair<Int, Boolean>? {
        layerOf[skinId]?.let { return it to false }
        val free = (0 until capacity).filter { wearers[it] == 0 }.minByOrNull { if (skinIn[it] == null) Long.MIN_VALUE else idleSince[it] }
            ?: return null
        skinIn[free]?.let { layerOf.remove(it) }
        skinIn[free] = skinId
        layerOf[skinId] = free
        return free to true
    }

    /** A fish now wears the drawing in [layer]. */
    fun wear(creature: Long, layer: Int) {
        unwear(creature)
        wornBy[creature] = layer
        wearers[layer]++
    }

    /** The fish is gone. */
    fun unwear(creature: Long) {
        val layer = wornBy.remove(creature) ?: return
        if (--wearers[layer] == 0) idleSince[layer] = ++clock
    }

    /** Every fish gone (the drawings stay in their layers, ready to be worn again). */
    fun clearWearers() {
        for (layer in wornBy.values.toSet()) idleSince[layer] = ++clock
        wornBy.clear()
        wearers.fill(0)
    }

    companion object {
        /** Renderer::kMaxSkins. */
        const val MAX = 16
    }
}
