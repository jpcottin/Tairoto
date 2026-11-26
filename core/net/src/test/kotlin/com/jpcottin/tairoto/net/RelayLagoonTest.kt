package com.jpcottin.tairoto.net

import com.jpcottin.tairoto.world.CreatureSpec
import com.jpcottin.tairoto.world.EntityIdAllocator
import com.jpcottin.tairoto.world.FakeLagoon
import com.jpcottin.tairoto.world.Population
import com.jpcottin.tairoto.world.ScreenMm
import com.jpcottin.tairoto.world.ShareEvent
import com.jpcottin.tairoto.world.SpeciesCatalog
import com.jpcottin.tairoto.world.TankScene
import com.jpcottin.tairoto.world.Vec2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Three devices of a shared lagoon over a real relay, in real time: the whole stack but the native engine. */
class RelayLagoonTest {
    @Test fun `fish cross three screens over a real relay, never simulated twice`() = runBlocking {
        RelayServer(0).use { relay ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            val start = System.nanoTime()
            val lagoon = FakeLagoon(
                scope, { (System.nanoTime() - start) / 1_000_000 }, net = null,
                screens = listOf(ScreenMm(68f, 150f), ScreenMm(118f, 150f), ScreenMm(68f, 150f)),
                scene = { size ->
                    TankScene(size, Population.release(SpeciesCatalog.GreenChromis, 40, size, EntityIdAllocator(0), Random(2)).map {
                        val r = Random(it.seed)
                        it.copy(spawnAt = Vec2(10f + r.nextFloat() * (size.widthMm - 20f), 10f + r.nextFloat() * (size.heightMm - 20f)))
                    })
                },
                connect = { i, name -> RelayLink.connect("127.0.0.1", relay.port, "test", name, asHost = i == 0) },
            )
            withContext(scope.coroutineContext) { lagoon.start() }
            delay(8_000)
            withContext(scope.coroutineContext) {
                lagoon.devices.forEach { it.sim.frozen = true }
            }
            delay(1_500)
            withContext(scope.coroutineContext) {
                assertTrue(lagoon.monitor.violations.joinToString(), lagoon.monitor.violations.isEmpty())
                val handoffs = lagoon.devices.sumOf { d -> d.events.count { it is ShareEvent.Took } }
                assertTrue("only $handoffs handoffs", handoffs >= 10)
                val sims = lagoon.simulators()
                assertTrue("not exactly once: ${sims.filter { it.value.size != 1 }}", sims.all { it.value.size == 1 })
            }
            scope.cancel()
            lagoon.devices.forEach { (it.link as? RelayLink)?.close() }
        }
    }
}
