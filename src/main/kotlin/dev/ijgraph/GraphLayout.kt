package dev.ijgraph

/**
 * Lane layout of a commit graph, ported from `Graph.determinePath` in
 * [mhutchie/vscode-git-graph](https://github.com/mhutchie/vscode-git-graph/blob/develop/web/graph.ts).
 *
 * Rows are in display order (children above parents). Every branch walks down its first parents in one lane,
 * a lane is only reused below the row where its branch ended, so lines never cross a commit dot.
 */
class GraphLayout private constructor(
    /** Lane of the commit dot in each row. */
    val dotLane: IntArray,
    /** Colour index of the commit dot in each row. */
    val dotColor: IntArray,
    /** Per row: packed `(fromLane, toLane, color)` triples, one per segment going from that row to the next. */
    private val segments: Array<IntArray?>,
    /** Number of lanes needed to draw the widest row. */
    val laneCount: Int,
) {
    val rowCount: Int get() = dotLane.size

    fun segmentsBelow(row: Int): IntArray = segments.getOrNull(row) ?: NO_SEGMENTS

    companion object {
        private val NO_SEGMENTS = IntArray(0)
        val EMPTY = GraphLayout(IntArray(0), IntArray(0), emptyArray(), 0)

        /** [parents] holds, for each row, the rows of its parents; the first parent first, every parent row below its child. */
        fun compute(parents: Array<IntArray>): GraphLayout = Builder(parents).build()
    }

    private class Builder(private val parents: Array<IntArray>) {
        private val n = parents.size
        private val lane = IntArray(n)
        private val branch = IntArray(n) { NONE }
        private val nextParent = IntArray(n)
        private val nextLane = IntArray(n)

        // Lanes taken in a row, always 0 until nextLane: packed (connectsToRow, branch) pairs
        private val taken = arrayOfNulls<IntArray>(n)
        private val segments = arrayOfNulls<IntArray>(n)
        private val segmentCount = IntArray(n)
        private var branchColor = IntArray(16)
        private var branchCount = 0
        private val colorFreeFrom = ArrayList<Int>() // color -> last row of the branch that used it

        fun build(): GraphLayout {
            var i = 0
            while (i < n) {
                if (nextParentOf(i) != NONE || branch[i] == NONE) determinePath(i) else i++
            }
            for (row in 0 until n) segments[row] = segments[row]?.copyOf(segmentCount[row] * 3)
            return GraphLayout(lane, IntArray(n) { branchColor[branch[it]] }, segments, nextLane.maxOrNull() ?: 0)
        }

        private fun determinePath(startAt: Int) {
            var vertex = startAt
            var parent = nextParentOf(vertex)
            var lastLane = if (branch[vertex] == NONE) nextLane[vertex] else lane[vertex]

            if (parent != NONE && parents[vertex].size > 1 && branch[vertex] != NONE && branch[parent] != NONE) {
                // Merge between two commits already on branches: join the lane already heading to the parent, if any
                val parentBranch = branch[parent]
                for (i in startAt + 1 until n) {
                    var curLane = laneConnectingTo(i, parent, parentBranch)
                    val found = curLane != NONE
                    if (!found) curLane = nextLane[i]
                    addSegment(i - 1, lastLane, curLane, branchColor[parentBranch])
                    take(i, curLane, parent, parentBranch)
                    lastLane = curLane
                    if (found) {
                        nextParent[vertex]++
                        break
                    }
                }
                return
            }

            val b = newBranch(availableColor(startAt))
            addToBranch(vertex, b, lastLane)
            take(vertex, lastLane, vertex, b)
            var i = startAt
            // ponytail: upstream also runs this loop for commits without parents, drawing a lane to the bottom; skipped
            if (parent != NONE) {
                i = startAt + 1
                while (i < n) {
                    val curLane = if (parent == i && branch[parent] != NONE) lane[i] else nextLane[i]
                    addSegment(i - 1, lastLane, curLane, branchColor[b])
                    take(i, curLane, parent, b)
                    lastLane = curLane
                    if (parent == i) {
                        // Reached the parent: continue the branch along its first parent
                        nextParent[vertex]++
                        val parentWasOnBranch = branch[parent] != NONE
                        addToBranch(parent, b, curLane)
                        vertex = parent
                        parent = nextParentOf(vertex)
                        if (parent == NONE || parentWasOnBranch) break
                    }
                    i++
                }
            }
            colorFreeFrom[branchColor[b]] = i
        }

        private fun nextParentOf(row: Int): Int = parents[row].getOrElse(nextParent[row]) { NONE }

        private fun addToBranch(row: Int, b: Int, l: Int) {
            if (branch[row] == NONE) {
                branch[row] = b
                lane[row] = l
            }
        }

        /** Marks [l] as taken in [row] if it is the next free lane there. */
        private fun take(row: Int, l: Int, connectsTo: Int, b: Int) {
            if (l != nextLane[row]) return
            val arr = taken[row].ensureSize((l + 1) * 2)
            arr[l * 2] = connectsTo
            arr[l * 2 + 1] = b
            taken[row] = arr
            nextLane[row] = l + 1
        }

        private fun laneConnectingTo(row: Int, connectsTo: Int, b: Int): Int {
            val arr = taken[row] ?: return NONE
            for (l in 0 until nextLane[row]) {
                if (arr[l * 2] == connectsTo && arr[l * 2 + 1] == b) return l
            }
            return NONE
        }

        private fun addSegment(row: Int, from: Int, to: Int, color: Int) {
            val k = segmentCount[row]
            val arr = segments[row].ensureSize((k + 1) * 3)
            arr[k * 3] = from
            arr[k * 3 + 1] = to
            arr[k * 3 + 2] = color
            segments[row] = arr
            segmentCount[row] = k + 1
        }

        private fun newBranch(color: Int): Int {
            branchColor = branchColor.ensureSize(branchCount + 1)
            branchColor[branchCount] = color
            return branchCount++
        }

        private fun availableColor(startAt: Int): Int {
            val free = colorFreeFrom.indexOfFirst { startAt > it }
            if (free != -1) return free
            colorFreeFrom.add(0)
            return colorFreeFrom.size - 1
        }

        private fun IntArray?.ensureSize(size: Int): IntArray = when {
            this == null -> IntArray(maxOf(size, 4))
            this.size >= size -> this
            else -> copyOf(maxOf(size, this.size * 2))
        }
    }
}

private const val NONE = -1
