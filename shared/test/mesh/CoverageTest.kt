package org.meshtastic.aether.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoverageTest {
    private fun seen(n: Long, role: String?, hw: String?) = MeshEvent.NodeSeen(n, null, null, true, 0L, role = role, hardware = hw)

    private fun env(n: Long, temp: Float? = null, wind: Float? = null, gust: Float? = null) =
        MeshEvent.Environment(n, temp, null, null, wind, null, 0L, windGustMps = gust)

    @Test
    fun `an empty tally describes zeros and dashes`() {
        assertEquals(
            "coverage nodes=0 temp=0 hum=0 press=0 lux=0 wind=0 batt=0 volt=0 stats=no roles=- hw=-",
            Coverage().describe(),
        )
    }

    @Test
    fun `fields count distinct nodes and ignore repeats and nulls`() {
        val c = Coverage()
        listOf(
            seen(1, "CLIENT", "HELTEC_V3"), seen(2, "ROUTER", "RAK4631"), seen(3, "CLIENT", "HELTEC_V3"),
            env(1, temp = 20f), env(1, temp = 21f), env(2, temp = 5f), env(3, temp = null),
            env(2, wind = 3f), env(3, gust = 7f),
            MeshEvent.Health(1, 80, 4.0f, 10, 0L), MeshEvent.Health(1, 79, 3.9f, 20, 0L), MeshEvent.Health(2, 101, null, 5, 0L),
            MeshEvent.Health(3, null, null, 5, 0L),
        ).forEach(c::add)
        assertEquals(
            "coverage nodes=3 temp=2 hum=0 press=0 lux=0 wind=2 batt=2 volt=1 stats=no roles=CLIENT:2,ROUTER:1 hw=HELTEC_V3:2,RAK4631:1",
            c.describe(),
        )
    }

    @Test
    fun `a Stats event flips the stats flag`() {
        val c = Coverage()
        c.add(MeshEvent.Stats(1, 2, 3, 4, 5, -100, 6, 0L))
        assertTrue(c.describe().contains("stats=yes"))
    }

    @Test
    fun `only the five most common roles are listed with ties broken by name`() {
        val c = Coverage()
        var n = 0L
        for ((role, count) in listOf("A" to 3, "B" to 2, "C" to 2, "D" to 1, "E" to 1, "F" to 1, "G" to 1)) {
            repeat(count) { c.add(seen(++n, role, null)) }
        }
        assertTrue(c.describe().contains("roles=A:3,B:2,C:2,D:1,E:1 hw=-"), c.describe())
    }

    @Test
    fun `a node seen again counts once under its latest role and a gone node leaves the mix`() {
        val c = Coverage()
        c.add(seen(1, "CLIENT", "TBEAM"))
        c.add(seen(1, "ROUTER", "TBEAM"))
        c.add(seen(2, "CLIENT", "TBEAM"))
        c.add(MeshEvent.NodeGone(2, 0L))
        assertTrue(c.describe().startsWith("coverage nodes=1 "), c.describe())
        assertTrue(c.describe().contains("roles=ROUTER:1 hw=TBEAM:1"), c.describe())
    }

    @Test
    fun `reset returns the tally to empty`() {
        val c = Coverage()
        c.add(seen(1, "CLIENT", "TBEAM"))
        c.add(env(1, temp = 1f))
        c.add(MeshEvent.Stats(null, null, null, null, null, null, null, 0L))
        c.reset()
        assertEquals(Coverage().describe(), c.describe())
    }
}
