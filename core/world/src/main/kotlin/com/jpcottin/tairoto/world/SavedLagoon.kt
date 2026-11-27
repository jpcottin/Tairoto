package com.jpcottin.tairoto.world

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a creature is at the moment the lagoon is saved, so it comes back there. */
data class CreaturePlace(val at: Vec2, val depthMm: Float)

/** The scene with every creature where it is now rather than where it was released. */
fun TankScene.withPlaces(places: Map<EntityId, CreaturePlace>): TankScene = copy(
    creatures = creatures.map { c ->
        val p = places[c.id] ?: return@map c
        if (!p.at.x.isFinite() || !p.at.y.isFinite() || !p.depthMm.isFinite()) return@map c
        c.copy(
            spawnAt = Vec2(p.at.x.coerceIn(0f, size.widthMm), p.at.y.coerceIn(0f, size.heightMm)),
            spawnDepthMm = p.depthMm.coerceIn(0f, size.depthMm),
        )
    },
)

/**
 * A lagoon as it is written to storage: the scene, and the format's version.
 * Reading is forgiving: a species or decoration this version does not know
 * (written by a newer one) is left out rather than losing the whole lagoon,
 * and a file that cannot be read at all gives null, never a crash.
 */
@Serializable
data class SavedLagoon(val version: Int = VERSION, val scene: TankScene) {
    companion object {
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun encode(scene: TankScene): String = json.encodeToString(serializer(), SavedLagoon(VERSION, scene.withoutUnusedSkins()))

        fun decode(text: String): TankScene? {
            val saved = try {
                json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                // Malformed JSON, or values the model refuses (a negative size): nothing to restore.
                return null
            }
            if (saved.version > VERSION || saved.version < 1) return null
            val s = saved.scene
            // A drawing that does not match its id (damaged) is dropped, and the fish that wore it.
            val skins = s.skins.distinctBy { it.id }.filter { it.isIntact }.take(TankScene.MAX_SKINS)
            val skinIds = skins.mapTo(HashSet()) { it.id }
            val creatures = s.creatures.filter { SpeciesCatalog[it.speciesId] != null }
                .filter { it.skin == null || it.skin in skinIds }
                .distinctBy { it.id }
                .take(TankScene.MAX_CREATURES)
            val decor = s.decor.filter { DecorCatalog[it.kindId] != null }.distinctBy { it.id }.take(TankScene.MAX_DECOR)
            return s.copy(creatures = creatures, decor = decor, skins = skins).withoutUnusedSkins()
        }
    }
}
