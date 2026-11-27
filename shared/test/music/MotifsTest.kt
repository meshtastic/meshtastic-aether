package org.meshtastic.aether.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotifsTest {
    private val nodes = listOf(7L, 42L, 0x1234ABCDL, 0xDEADBEEFL)

    private fun Motif.key(): String = roles.joinToString() + "|" + gaps.joinToString()

    /** Plays [node]'s motif as the composer does when its first note is heard. */
    private fun MotifMemory.play(node: Long): Motif = current(node).also { advance(node) }

    // Regression: a node's motif was a hash of its number and never developed, so the same phrase repeated for hours.
    @Test
    fun `a node's motif drifts while its opening note stays its own role`() {
        for (node in nodes) {
            val memory = MotifMemory()
            val plays = List(40) { memory.play(node) }
            assertEquals(Motifs.of(node).key(), plays[0].key(), "the first play is the original")
            for (m in plays) {
                assertEquals(Ornaments.role(node), m.roles[0])
                for (i in 1 until m.size) assertTrue(m.roles[i] != m.roles[i - 1], "neighbors repeat a role")
            }
            assertTrue(plays.map { it.key() }.toSet().size >= 3, "node $node never developed its motif")
        }
    }

    @Test
    fun `the original motif returns every eighth play`() {
        for (node in nodes) {
            val memory = MotifMemory()
            val plays = List(25) { memory.play(node) }
            val original = Motifs.of(node).key()
            for (i in listOf(8, 16, 24)) assertEquals(original, plays[i].key(), "node $node play $i")
        }
    }

    @Test
    fun `forgetting a node starts its motif over`() {
        val memory = MotifMemory()
        repeat(5) { memory.play(42L) }
        memory.forget(42L)
        assertEquals(Motifs.of(42L).key(), memory.play(42L).key())
    }
}
