package dev.ijgraph

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.dualView.TableCellRendererWrapper
import com.intellij.ui.scale.ScaleContext
import com.intellij.util.ui.UIUtil
import com.intellij.vcs.log.VcsLogHighlighter
import com.intellij.vcs.log.graph.PrintElement
import com.intellij.vcs.log.paint.GraphCellPainter
import com.intellij.vcs.log.ui.render.GraphCommitCell
import com.intellij.vcs.log.ui.render.LabelIcon
import com.intellij.vcs.log.ui.render.LabelPainter
import com.intellij.vcs.log.ui.table.GraphCommitCellController
import com.intellij.vcs.log.ui.table.VcsLogCellController
import com.intellij.vcs.log.ui.table.VcsLogCellRenderer
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.Commit
import com.intellij.vcs.log.ui.table.column.VcsLogColumn
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import java.awt.Point
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Method
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.TableCellRenderer

/**
 * Hides the built-in graph painted at the start of the Commit column:
 * - the column renderer gets cells without print elements, so no graph is painted and the text starts at the left edge;
 * - the column's cell controller hit-tests no graph elements, so clicks on the old graph area do nothing.
 *
 * Reference labels of the Commit column and of their tooltip are recoloured with the commit's lane colour, like Git Graph.
 */
internal object DefaultGraphHider {
    private val LOG = logger<DefaultGraphHider>()

    fun install(table: VcsLogGraphTable, graphColumn: VcsLogColumn<*>) {
        // Renderers are created while the table builds its columns; patch once they are all in place
        SwingUtilities.invokeLater {
            if (!table.isColumnVisible(graphColumn)) return@invokeLater
            val commitColumn = table.commitColumn
            if (commitColumn.cellRenderer !is NoGraphCommitRenderer) {
                commitColumn.cellRenderer = NoGraphCommitRenderer(commitColumn.cellRenderer, table, labelRecolorer(table))
            }
            val graphIndex = table.getColumnViewIndex(graphColumn)
            val commitIndex = table.getColumnViewIndex(Commit)
            if (commitIndex in 0 until graphIndex) table.columnModel.moveColumn(graphIndex, commitIndex)
            table.repaint()
        }
    }

    private fun labelRecolorer(table: VcsLogGraphTable): LabelRecolorer? = try {
        val component = field(field(table, "myGraphCommitCellRenderer"), "component")
        LabelRecolorer(field(component, "referencePainter") as LabelPainter)
    }
    catch (e: ReflectiveOperationException) {
        LOG.warn("Cannot reach the Commit column label painter, labels keep their default colours", e)
        null
    }

    fun field(owner: Any, name: String): Any {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).accessible().get(owner)
            }
            catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}

private fun <T : AccessibleObject> T.accessible(): T = apply { isAccessible = true }

private object NoGraphPainter : GraphCellPainter {
    override fun paint(g2: Graphics2D, commitStyle: VcsLogHighlighter.VcsCommitStyle, printElements: Collection<PrintElement>) = Unit
    override fun getElementUnderCursor(scaleContext: ScaleContext, printElements: Collection<PrintElement>, x: Int, y: Int): PrintElement? = null
}

private fun withoutGraph(value: Any?): Any? = when {
    value is GraphCommitCell.RealCommit && value.printElements.isNotEmpty() ->
        GraphCommitCell.RealCommit(value.commitId, value.text, value.refsToThisCommit, value.bookmarksToThisCommit, emptyList(), value.isLoading)
    value is GraphCommitCell.NewCommit && value.printElements.isNotEmpty() -> GraphCommitCell.NewCommit(emptyList())
    else -> value
}

private fun renderWithoutGraph(renderer: TableCellRenderer, table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
    renderingCommitColumn = true
    try {
        return renderer.getTableCellRendererComponent(table, withoutGraph(value), isSelected, hasFocus, row, column)
    }
    finally {
        renderingCommitColumn = false
    }
}

private class NoGraphCommitRenderer(
    private val delegate: TableCellRenderer,
    private val table: VcsLogGraphTable,
    private val labels: LabelRecolorer?,
) : TableCellRenderer, VcsLogCellRenderer, TableCellRendererWrapper {
    // Issue links are looked up on the unwrapped renderer's SimpleColoredComponent (TableLinkMouseListener),
    // so it must also skip the graph, or the text fragments sit where the graph used to be
    private val base = TableCellRenderer { table, value, isSelected, hasFocus, row, column ->
        renderWithoutGraph((delegate as? TableCellRendererWrapper)?.baseRenderer ?: delegate, table, value, isSelected, hasFocus, row, column)
    }

    override fun getBaseRenderer(): TableCellRenderer = base

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        val component = renderWithoutGraph(delegate, table, value, isSelected, hasFocus, row, column)
        // Labels are laid out by the delegate call above and painted later, so recolour them in between
        if (labels != null && value is GraphCommitCell.RealCommit && value.refsToThisCommit.isNotEmpty() && table is VcsLogGraphTable) {
            graphCellAt(table.model, row).color?.let(labels::recolor)
        }
        return component
    }

    override fun getCellController(): VcsLogCellController? {
        val original = (delegate as? VcsLogCellRenderer)?.getCellController()
        if (original !is GraphCommitCellController || TOOLTIP == null || TOOLTIP_X == null) return original
        return NoGraphCellController(original, table, labels)
    }

    override fun getPreferredWidth(): VcsLogCellRenderer.PreferredWidth? = (delegate as? VcsLogCellRenderer)?.getPreferredWidth()
}

// The original controller computes tooltips through the renderer's private state; its protected hooks are reused by reflection
private val TOOLTIP: Method? = runCatching {
    GraphCommitCellController::class.java.getDeclaredMethod("getTooltip", Any::class.java, Point::class.java, Int::class.javaPrimitiveType).accessible()
}.getOrNull()
private val TOOLTIP_X: Method? = runCatching {
    GraphCommitCellController::class.java.getDeclaredMethod("getTooltipXCoordinate", Int::class.javaPrimitiveType).accessible()
}.getOrNull()

/** Commit column controller that sees no graph elements and recolours the label tooltip. */
private class NoGraphCellController(
    private val original: GraphCommitCellController,
    private val table: VcsLogGraphTable,
    private val labels: LabelRecolorer?,
) : GraphCommitCellController(table.logData, table, NoGraphPainter) {
    override fun getTooltipXCoordinate(row: Int): Int = TOOLTIP_X!!.invoke(original, row) as Int

    override fun getTooltip(value: Any, point: Point, row: Int): JComponent? {
        val tooltip = TOOLTIP!!.invoke(original, withoutGraph(value), point, row) as JComponent?
        val color = graphCellAt(table.model, row).color
        if (tooltip != null && labels != null && color != null) labels.recolorIcons(tooltip, color)
        return tooltip
    }
}

/**
 * Swaps label icons for icons of the same shape and width in one colour.
 * Icon colours otherwise come from the ref types (local branch, remote, tag), see `LabelPainter.getColors`.
 */
private class LabelRecolorer(private val painter: LabelPainter) {
    private val labelsField = LabelPainter::class.java.getDeclaredField("myLabels").accessible()
    private val iconComponent = DefaultGraphHider.field(painter, "myComponent") as JComponent
    private val presentation = Class.forName(LabelPainter::class.java.name + "\$Presentation", false, LabelPainter::class.java.classLoader)
    private val presentationParts = presentation.recordComponents.map { it.accessor.accessible() }
    private val presentationCtor = presentation.getDeclaredConstructor(*presentation.recordComponents.map { it.type }.toTypedArray()).accessible()
    private val iconPart = presentation.recordComponents.indexOfFirst { it.name == "icon" }
    private val iconSize = LabelIcon::class.java.getDeclaredField("mySize").accessible()
    private val iconBackground = LabelIcon::class.java.getDeclaredField("myBgColor").accessible()
    private val iconColors = LabelIcon::class.java.getDeclaredField("myColors").accessible()
    // ponytail: unbounded, but keys are few (icon height x width x background x colour count x 12 colours)
    private val icons = HashMap<List<Any>, LabelIcon>()

    /** Recolours the labels the painter has just laid out for a cell. */
    fun recolor(color: Color) {
        val labels = labelsField.get(painter) as? List<*> ?: return
        labelsField.set(painter, labels.map { label ->
            val parts = presentationParts.map { it.invoke(label) }.toTypedArray()
            val icon = parts[iconPart] as? LabelIcon ?: return@map label // bookmarks keep their own icon
            parts[iconPart] = recolored(icon, color)
            presentationCtor.newInstance(*parts)
        })
    }

    /** Recolours the label icons of a references tooltip. */
    fun recolorIcons(root: JComponent, color: Color) {
        for (label in UIUtil.findComponentsOfType(root, JLabel::class.java)) {
            val icon = label.icon as? LabelIcon ?: continue
            label.icon = recolored(icon, color)
        }
    }

    private fun recolored(icon: LabelIcon, color: Color): LabelIcon {
        val size = iconSize.getInt(icon)
        val background = iconBackground.get(icon) as Color
        val count = (iconColors.get(icon) as List<*>).size
        val width = icon.iconWidth // tooltip icons are padded to align labels
        return icons.getOrPut(listOf(size, width, background, count, color)) {
            FixedWidthLabelIcon(iconComponent, size, background, List(count) { color }, width)
        }
    }
}

private class FixedWidthLabelIcon(component: JComponent, size: Int, background: Color, colors: List<Color>, private val width: Int) :
    LabelIcon(component, size, background, colors) {
    // The super constructor already asks for the width to create its image, before `width` is assigned
    override fun getIconWidth(): Int = if (width > 0) width else super.getIconWidth()
}
