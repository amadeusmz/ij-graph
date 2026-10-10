package dev.ijgraph

import com.intellij.openapi.util.Key
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogCellRenderer
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import com.intellij.vcs.log.visible.VisiblePack
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

class GraphCell(val layout: GraphLayout, val row: Int, val isHead: Boolean) {
    /** Colour of the commit's lane, or null for rows without a commit dot. */
    val color: Color? get() = if (row in 0 until layout.rowCount) color(layout.dotColor[row]) else null

    companion object {
        val EMPTY = GraphCell(GraphLayout.EMPTY, -1, false)
    }
}

/** The git log provider adds a ref named exactly `HEAD` for the checked-out commit of every root. */
internal const val HEAD = "HEAD"

internal fun graphCellAt(model: GraphTableModel, row: Int): GraphCell {
    val pack = model.visiblePack
    val commit = model.getId(row) ?: return GraphCell.EMPTY // e.g. a work-in-progress row
    val graphRow = pack.visibleGraph.getVisibleRowIndex(commit) ?: return GraphCell.EMPTY
    return GraphCell(layoutOf(pack), graphRow, pack.refs.refsToCommit(commit).any { it.name == HEAD })
}

class GitGraphColumn : VcsLogCustomColumn<GraphCell> {
    override val id: String = "ijgraph.graph"
    override val localizedName: String = "Graph"
    override val isDynamic: Boolean = false

    override fun getValue(model: GraphTableModel, row: Int): GraphCell = graphCellAt(model, row)

    override fun getStubValue(model: GraphTableModel): GraphCell = GraphCell.EMPTY

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        DefaultGraphHider.install(table, this)
        return GraphCellRenderer()
    }
}

private val LAYOUT_KEY = Key.create<GraphLayout>("ijgraph.layout")

/** Layout is computed once per [VisiblePack]; a new pack arrives on every refresh, filter or sort change. */
private fun layoutOf(pack: VisiblePack): GraphLayout {
    pack.getUserData(LAYOUT_KEY)?.let { return it }
    // ponytail: computed on EDT on first paint, move to a background task if huge repos stall the log
    val layout = GraphLayout.compute(parentRows(pack))
    pack.putUserData(LAYOUT_KEY, layout)
    return layout
}

private fun parentRows(pack: VisiblePack): Array<IntArray> {
    val graph = pack.visibleGraph
    // The visible graph does not promise git parent order, so remember the first parent of every merge
    val firstParentOfMerge = HashMap<Int, Int>()
    for (commit in pack.dataPack.permanentGraph.allCommits) {
        if (commit.parents.size > 1) firstParentOfMerge[commit.id] = commit.parents[0]
    }
    return Array(graph.visibleCommitCount) { row ->
        val info = graph.getRowInfo(row)
        val adjacent = info.getAdjacentRows(true).filter { it > row }.distinct()
        if (adjacent.size < 2) return@Array adjacent.toIntArray()
        val firstRow = firstParentOfMerge[info.commit]?.let(graph::getVisibleRowIndex)
        if (firstRow == null || firstRow !in adjacent) adjacent.toIntArray()
        else (listOf(firstRow) + (adjacent - firstRow)).toIntArray()
    }
}

/** Git Graph default palette (`git-graph.graph.colours`). */
private val COLORS = listOf(
    "#0085d9", "#d9008f", "#00d90a", "#d98500", "#a300d9", "#ff0000",
    "#00d9cc", "#e138e8", "#85d900", "#dc5b23", "#6f24d6", "#ffcc00",
).map(Color::decode)

private val LANE_WIDTH get() = JBUI.scale(16)
private val PADDING get() = JBUI.scale(12)

private class GraphCellRenderer : ColoredTableCellRenderer(), VcsLogCellRenderer {
    private var cell = GraphCell.EMPTY

    override fun customizeCellRenderer(table: JTable, value: Any?, selected: Boolean, hasFocus: Boolean, row: Int, column: Int) {
        cell = value as? GraphCell ?: GraphCell.EMPTY
        (table as? VcsLogGraphTable)?.applyHighlighters(this, row, column, hasFocus, selected)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val layout = cell.layout
        val row = cell.row
        if (row !in 0 until layout.rowCount) return

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            val h = height.toDouble()
            val mid = h / 2
            // Segments from the row above end in this cell, segments to the row below start in it
            drawSegments(g2, layout.segmentsBelow(row - 1), mid - h, h)
            drawSegments(g2, layout.segmentsBelow(row), mid, h)

            val r = JBUI.scale(4).toDouble()
            val dot = Ellipse2D.Double(laneX(layout.dotLane[row]) - r, mid - r, 2 * r, 2 * r)
            if (cell.isHead) {
                // Hollow dot for the checked-out commit, like Git Graph's `circle.current`
                g2.color = background
                g2.fill(dot)
                g2.color = cell.color
                g2.draw(dot)
            }
            else {
                g2.color = cell.color
                g2.fill(dot)
            }
        }
        finally {
            g2.dispose()
        }
    }

    private fun drawSegments(g: Graphics2D, segments: IntArray, y1: Double, h: Double) {
        val y2 = y1 + h
        val d = h * 0.8 // same curvature as Git Graph's rounded style
        for (k in segments.indices step 3) {
            val x1 = laneX(segments[k])
            val x2 = laneX(segments[k + 1])
            val path = Path2D.Double()
            path.moveTo(x1, y1)
            if (x1 == x2) path.lineTo(x2, y2) else path.curveTo(x1, y1 + d, x2, y2 - d, x2, y2)
            g.color = color(segments[k + 2])
            g.draw(path)
        }
    }

    override fun getPreferredWidth(): VcsLogCellRenderer.PreferredWidth = VcsLogCellRenderer.PreferredWidth.Fixed { table ->
        val lanes = (table as? VcsLogGraphTable)?.model?.visiblePack?.let(::layoutOf)?.laneCount ?: 1
        2 * PADDING + (maxOf(lanes, 1) - 1) * LANE_WIDTH
    }
}

private fun laneX(lane: Int): Double = (PADDING + lane * LANE_WIDTH).toDouble()

private fun color(index: Int): Color = COLORS[index % COLORS.size]
