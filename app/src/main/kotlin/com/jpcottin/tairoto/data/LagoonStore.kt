package com.jpcottin.tairoto.data

import com.jpcottin.tairoto.world.SavedLagoon
import com.jpcottin.tairoto.world.TankScene
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Where the lagoon is kept between launches: a file on a device, memory in tests. */
interface LagoonStore {
    /** The lagoon last saved, or null if there is none (or it could not be read). */
    suspend fun load(): TankScene?
    suspend fun save(scene: TankScene)
    suspend fun clear()
}

/**
 * The lagoon as a JSON file in the app's own storage. Written to a temporary
 * file and renamed over the old one, so a lagoon is never half saved: if the
 * app dies while writing, the previous one is still there.
 */
class FileLagoonStore(
    private val dir: File,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LagoonStore {
    private val file = File(dir, FILE_NAME)

    override suspend fun load(): TankScene? = withContext(io) {
        try {
            if (file.isFile) SavedLagoon.decode(file.readText()) else null
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun save(scene: TankScene) = withContext(io) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(SavedLagoon.encode(scene))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // Out of space or storage gone: the lagoon carries on, unsaved this time.
        }
        Unit
    }

    override suspend fun clear() = withContext(io) {
        file.delete()
        Unit
    }

    companion object {
        const val FILE_NAME = "lagoon.json"
    }
}

class InMemoryLagoonStore(var scene: TankScene? = null) : LagoonStore {
    var saves = 0
        private set

    override suspend fun load(): TankScene? = scene
    override suspend fun save(scene: TankScene) {
        this.scene = scene
        saves++
    }
    override suspend fun clear() {
        scene = null
    }
}
