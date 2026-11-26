package com.jpcottin.tairoto.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.TankSize
import com.jpcottin.tairoto.world.Vec2
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** The native engine through JNI, without a surface: the simulation runs on its thread all the same. */
@RunWith(AndroidJUnit4::class)
class NativeTankTest {
    private val size = TankSize(200f, 320f, 110f)
    private lateinit var tank: NativeTank
    private lateinit var scene: TankScene

    @Before fun setUp() {
        tank = NativeTank()
        tank.setDeterministic(true, 7)
        scene = Population.defaultScene(size, EntityIdAllocator(0), Random(5))
        SceneSync(tank).apply(scene)
    }

    @After fun tearDown() = tank.close()

    @Test fun everyCreatureOfTheSceneIsInTheEngine() {
        val snap = tank.snapshot()
        assertEquals(scene.creatures.map { it.id }.toSet(), snap.map { it.id }.toSet())
        assertEquals(scene.creatures.size, tank.stats().creatures)
    }

    @Test fun withoutASurfaceNothingIsDrawnButTheTankIsThere() {
        val s = tank.stats()
        assertEquals(VulkanState.WAITING, s.vulkan)
        assertEquals(0L, s.frames)
    }

    @Test fun creaturesSwimAndStayInTheTank() {
        val before = tank.snapshot().associateBy { it.id }
        tank.stepForTest(20f)
        val after = tank.snapshot()
        var moved = 0
        after.forEach {
            assertTrue(it.x in 0f..size.widthMm && it.y in 0f..size.heightMm && it.z in 0f..size.depthMm)
            val b = before.getValue(it.id)
            if (Vec2(it.x - b.x, it.y - b.y).length > 5f) moved++
        }
        assertTrue("only $moved of ${after.size} moved", moved > after.size / 2)
        assertEquals(20f, tank.stats().simTimeS, 0.05f)
    }

    @Test fun removeAndClear() {
        val id = scene.creatures.first().id
        tank.remove(id)
        assertTrue(tank.snapshot().none { it.id == id })
        tank.clear()
        assertTrue(tank.snapshot().isEmpty())
    }

    @Test fun theSceneSyncOnlySendsWhatChanged() {
        val sync = SceneSync(tank)
        sync.apply(scene) // already in the engine through the first sync: spawns are refused natively
        val fewer = scene.copy(creatures = scene.creatures.drop(3))
        sync.apply(fewer)
        assertEquals(fewer.creatures.size, tank.snapshot().size)
        assertEquals(fewer.creatures.size, sync.appliedCount)
    }

    @Test fun theSameSeedGivesTheSameMotion() {
        val other = NativeTank()
        try {
            other.setDeterministic(true, 7)
            SceneSync(other).apply(scene)
            tank.stepForTest(10f)
            other.stepForTest(10f)
            val a = tank.snapshot().sortedBy { it.id.raw }
            val b = other.snapshot().sortedBy { it.id.raw }
            assertEquals(a, b)
        } finally {
            other.close()
        }
    }

    @Test fun aTapScattersTheSchool() {
        tank.stepForTest(2f)
        val fish = tank.snapshot().filter { s -> scene.creatures.first { it.id == s.id }.speciesId == SpeciesCatalog.GreenChromis.id }
        val cx = fish.map { it.x }.average().toFloat()
        val cy = fish.map { it.y }.average().toFloat()
        val calm = fish.map { it.speed }.average()
        tank.tap(Vec2(cx, cy))
        tank.stepForTest(0.3f)
        val scared = tank.snapshot().filter { s -> fish.any { it.id == s.id } }.map { it.speed }.average()
        assertTrue("calm $calm, after the tap $scared", scared > calm * 1.5)
    }

    @Test fun aDifferentSeedGivesADifferentTank() {
        val other = NativeTank()
        try {
            other.setDeterministic(true, 99)
            SceneSync(other).apply(scene)
            tank.stepForTest(10f)
            other.stepForTest(10f)
            assertNotEquals(tank.snapshot().sortedBy { it.id.raw }, other.snapshot().sortedBy { it.id.raw })
        } finally {
            other.close()
        }
    }

    @Test fun closingTwiceIsHarmless() {
        val t = NativeTank()
        t.close()
        t.close()
    }
}
