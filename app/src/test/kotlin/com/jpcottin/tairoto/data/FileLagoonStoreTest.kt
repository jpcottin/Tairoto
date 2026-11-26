package com.jpcottin.tairoto.data

import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.TankSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class FileLagoonStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val scene = Population.defaultScene(TankSize(190f, 420f, 55f), EntityIdAllocator(0), Random(2))

    @Test fun `nothing saved yet reads as no lagoon`() = runTest {
        assertNull(FileLagoonStore(folder.root, Dispatchers.Unconfined).load())
    }

    @Test fun `a saved lagoon reads back the same, and the temporary file is gone`() = runTest {
        val store = FileLagoonStore(File(folder.root, "new/dir"), Dispatchers.Unconfined)
        store.save(scene)
        assertEquals(scene, store.load())
        store.save(scene.copy(creatures = scene.creatures.drop(3)))
        assertEquals(scene.creatures.size - 3, store.load()!!.creatures.size)
        assertFalse(File(folder.root, "new/dir/${FileLagoonStore.FILE_NAME}.tmp").exists())
    }

    @Test fun `a damaged file reads as no lagoon, and clearing forgets it`() = runTest {
        val store = FileLagoonStore(folder.root, Dispatchers.Unconfined)
        File(folder.root, FileLagoonStore.FILE_NAME).writeText("{\"version\":1,\"scene\":{\"si")
        assertNull(store.load())
        store.save(scene)
        store.clear()
        assertNull(store.load())
    }
}
