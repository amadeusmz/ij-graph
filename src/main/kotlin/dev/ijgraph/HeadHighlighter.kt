package dev.ijgraph

import com.intellij.vcs.log.VcsCommitStyleFactory
import com.intellij.vcs.log.VcsLogAggregatedStoredRefs
import com.intellij.vcs.log.VcsLogDataPack
import com.intellij.vcs.log.VcsLogHighlighter
import com.intellij.vcs.log.VcsLogHighlighter.VcsCommitStyle
import com.intellij.vcs.log.VcsLogUi
import com.intellij.vcs.log.VcsShortCommitDetails
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.ui.VcsLogUiEx
import com.intellij.vcs.log.ui.highlighters.VcsLogHighlighterFactory
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.Commit

/** Bold subject for the checked-out commit, like Git Graph's `tr.commit.current`. */
class HeadHighlighterFactory : VcsLogHighlighterFactory {
    override fun createHighlighter(logData: VcsLogData, logUi: VcsLogUi): VcsLogHighlighter = HeadHighlighter(logUi)
    override fun getId(): String = "ijgraph.head"
    override fun getTitle(): String = "HEAD Commit"
    override fun showMenuItem(): Boolean = false
}

private class HeadHighlighter(private val logUi: VcsLogUi) : VcsLogHighlighter {
    private var refs: VcsLogAggregatedStoredRefs? = null

    override fun getStyle(commitId: Int, details: VcsShortCommitDetails, column: Int, isSelected: Boolean): VcsCommitStyle {
        if (refs?.refsToCommit(commitId)?.any { it.name == HEAD } != true) return VcsCommitStyle.DEFAULT
        val table = (logUi as? VcsLogUiEx)?.table as? VcsLogGraphTable
        return if (table == null || table.getVcsLogColumn(column) == Commit) VcsCommitStyleFactory.bold() else VcsCommitStyle.DEFAULT
    }

    override fun update(dataPack: VcsLogDataPack, refreshHappened: Boolean) {
        refs = dataPack.refs
    }
}
