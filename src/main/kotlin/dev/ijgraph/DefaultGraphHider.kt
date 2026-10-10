package dev.ijgraph

import com.intellij.openapi.diagnostic.logger
import com.intellij.ui.scale.ScaleContext
import com.intellij.vcs.log.VcsLogHighlighter
import com.intellij.vcs.log.graph.PrintElement
import com.intellij.vcs.log.paint.GraphCellPainter
import com.intellij.vcs.log.ui.render.GraphCommitCell
import com.intellij.vcs.log.ui.render.LabelIcon
import com.intellij.vcs.log.ui.render.LabelPainter
import com.intellij.vcs.log.ui.table.VcsLogCellController
import com.intellij.vcs.log.ui.table.VcsLogCellRenderer
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.Commit
import com.intellij.vcs.log.ui.table.column.VcsLogColumn
import java.awt.Color
import java.awt.Component
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.SwingUtilities
import javax.swing.table.TableCellRenderer

/**
 * Hides the built-in graph painted at the start of the Commit column:
 * - the column renderer gets cells without print elements, so no graph is painted and the text starts at the left edge;
 * - the painter used for mouse hit-testing is swapped (reflection) so clicks on the old graph area do nothing.
 *
 * Reference labels of the Commit column are recoloured with the commit's lane colour, like Git Graph.
 */
internal object DefaultGraphHider {
    private val LOG = logger<DefaultGraphHider>()

    fun install(table: VcsLogGraphTable, graphColumn: VcsLogColumn<*>) {
        // Renderers are created while the table builds its columns; patch once they are all in place
        SwingUtilities.invokeLater {
            if (!table.isColumnVisible(graphColumn)) return@invokeLater
            val labels = patchCommitRenderer(table)
            val commitColumn = table.commitColumn
            if (commitColumn.cellRenderer !is NoGraphCommitRenderer) {
                commitColumn.cellRenderer = NoGraphCommitRenderer(commitColumn.cellRenderer, labels)
            }
            val graphIndex = table.getColumnViewIndex(graphColumn)
            val commitIndex = table.getColumnViewIndex(Commit)
            if (commitIndex in 0 until graphIndex) table.columnModel.moveColumn(graphIndex, commitIndex)
            table.repaint()
        }
    }

    /** Disables graph hit-testing of the Commit column renderer; returns its label recolourer, if reachable. */
    private fun patchCommitRenderer(table: VcsLogGraphTable): LabelRecolorer? {
        try {
            val renderer = field(table, "myGraphCommitCellRenderer")
            val component = field(renderer, "component")
            component.javaClass.getDeclaredField("painter").apply { isAccessible = true }.set(component, NoGraphPainter)
            return LabelRecolorer(field(component, "referencePainter") as LabelPainter)
        }
        catch (e: ReflectiveOperationException) {
            LOG.warn("Cannot patch the Commit column renderer, old graph hit-testing and label colours stay as they are", e)
            return null
        }
    }

    fun field(owner: Any, name: String): Any {
        var cls: Class<*>? = owner.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(owner)
            }
            catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}

private object NoGraphPainter : GraphCellPainter {
    override fun paint(g2: Graphics2D, commitStyle: VcsLogHighlighter.VcsCommitStyle, printElements: Collection<PrintElement>) = Unit
    override fun getElementUnderCursor(scaleContext: ScaleContext, printElements: Collection<PrintElement>, x: Int, y: Int): PrintElement? = null
}

private class NoGraphCommitRenderer(
    private val delegate: TableCellRenderer,
    private val labels: LabelRecolorer?,
) : TableCellRenderer, VcsLogCellRenderer {
    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        renderingCommitColumn = true
        val component = try {
            delegate.getTableCellRendererComponent(table, withoutGraph(value), isSelected, hasFocus, row, column)
        }
        finally {
            renderingCommitColumn = false
        }
        // Labels are laid out by the delegate call above and painted later, so recolour them in between
        if (labels != null && value is GraphCommitCell.RealCommit && value.refsToThisCommit.isNotEmpty() && table is VcsLogGraphTable) {
            graphCellAt(table.model, row).color?.let(labels::recolor)
        }
        return component
    }

    override fun getCellController(): VcsLogCellController? = (delegate as? VcsLogCellRenderer)?.getCellController()

    override fun getPreferredWidth(): VcsLogCellRenderer.PreferredWidth? = (delegate as? VcsLogCellRenderer)?.getPreferredWidth()

    private fun withoutGraph(value: Any?): Any? = when {
        value is GraphCommitCell.RealCommit && value.printElements.isNotEmpty() ->
            GraphCommitCell.RealCommit(value.commitId, value.text, value.refsToThisCommit, value.bookmarksToThisCommit, emptyList(), value.isLoading)
        value is GraphCommitCell.NewCommit && value.printElements.isNotEmpty() -> GraphCommitCell.NewCommit(emptyList())
        else -> value
    }
}

/**
 * Swaps the icons of the labels a [LabelPainter] has just laid out for icons of the same shape in one colour.
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
    // ponytail: unbounded, but keys are few (icon height x background x colour count x 12 colours)
    private val icons = HashMap<List<Any>, LabelIcon>()

    fun recolor(color: Color) {
        val labels = labelsField.get(painter) as? List<*> ?: return
        labelsField.set(painter, labels.map { label ->
            val parts = presentationParts.map { it.invoke(label) }.toTypedArray()
            val icon = parts[iconPart] as? LabelIcon ?: return@map label // bookmarks keep their own icon
            parts[iconPart] = recolored(icon, color)
            presentationCtor.newInstance(*parts)
        })
    }

    private fun recolored(icon: LabelIcon, color: Color): LabelIcon {
        val size = iconSize.getInt(icon)
        val background = iconBackground.get(icon) as Color
        val count = (iconColors.get(icon) as List<*>).size
        return icons.getOrPut(listOf(size, background, count, color)) { LabelIcon(iconComponent, size, background, List(count) { color }) }
    }

    private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T = apply { isAccessible = true }
}
