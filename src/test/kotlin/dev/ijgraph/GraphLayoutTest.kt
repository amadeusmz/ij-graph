package dev.ijgraph

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class GraphLayoutTest {
    @Test
    fun `main stays straight in lane 0, merged feature gets its own lane`() {
        // 0 merge(main, feature) / 1 main / 2 feature / 3 base, fork point of both
        val layout = GraphLayout.compute(arrayOf(intArrayOf(1, 2), intArrayOf(3), intArrayOf(3), intArrayOf()))

        assertArrayEquals(intArrayOf(0, 0, 1, 0), layout.dotLane)
        assertArrayEquals(intArrayOf(0, 0, 1, 0), layout.dotColor)
        assertEquals(2, layout.laneCount)
        assertArrayEquals(intArrayOf(0, 0, 0, 0, 1, 1), layout.segmentsBelow(0))
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1, 1), layout.segmentsBelow(1))
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 0, 1), layout.segmentsBelow(2))
    }

    @Test
    fun `merge of a commit already on a lane curves into that lane`() {
        // 0 feature tip / 1 merge(base, feature commit 2) / 2 feature / 3 base
        val layout = GraphLayout.compute(arrayOf(intArrayOf(2), intArrayOf(3, 2), intArrayOf(3), intArrayOf()))

        assertArrayEquals(intArrayOf(0, 1, 0, 0), layout.dotLane)
        assertEquals(2, layout.laneCount)
        // row 1 -> 2: feature lane, merge's own lane, then the merge edge 1 -> 0 drawn in the feature colour
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1, 1, 1, 0, 0), layout.segmentsBelow(1))
    }
}
