package dev.ijgraph

import com.intellij.vcs.log.VcsCommitStyleFactory
import com.intellij.vcs.log.VcsLogAggregatedStoredRefs
import com.intellij.vcs.log.VcsLogDataPack
import com.intellij.vcs.log.VcsLogHighlighter
import com.intellij.vcs.log.VcsLogHighlighter.VcsCommitStyle
import com.intellij.vcs.log.VcsLogUi
import com.intellij.vcs.log.VcsShortCommitDetails
import com.intellij.vcs.log.data.VcsLogData
import com.intellij.vcs.log.ui.highlighters.VcsLogHighlighterFactory

/**
 * True while the Commit column renderer draws a cell (EDT only).
 * Highlighters get a column index whose meaning depends on the caller, so the Commit column is detected this way instead.
 */
internal var renderingCommitColumn = false

/** Bold subject for the checked-out commit, like Git Graph's `tr.commit.current`. */
class HeadHighlighterFactory : VcsLogHighlighterFactory {
    override fun createHighlighter(logData: VcsLogData, logUi: VcsLogUi): VcsLogHighlighter = HeadHighlighter()
    override fun getId(): String = "ijgraph.head"
    override fun getTitle(): String = "HEAD Commit"
    override fun showMenuItem(): Boolean = false
}

private class HeadHighlighter : VcsLogHighlighter {
    private var refs: VcsLogAggregatedStoredRefs? = null

    override fun getStyle(commitId: Int, details: VcsShortCommitDetails, column: Int, isSelected: Boolean): VcsCommitStyle =
        if (renderingCommitColumn && refs?.refsToCommit(commitId)?.any { it.name == HEAD } == true) VcsCommitStyleFactory.bold()
        else VcsCommitStyle.DEFAULT

    override fun update(dataPack: VcsLogDataPack, refreshHappened: Boolean) {
        refs = dataPack.refs
    }
}
