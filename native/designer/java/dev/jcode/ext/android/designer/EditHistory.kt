package dev.jcode.ext.android.designer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Undo and redo for the designer, as whole-file snapshots.
 *
 * Snapshots rather than a command log because every edit here already *is* a whole new file: the
 * documents return spliced text, not a diff, so there is nothing cheaper to keep and nothing to
 * invert. A layout file is a few kilobytes and the depth is bounded, so the memory this costs is
 * not worth a more clever design.
 *
 * The file can also change without going through the designer — the same buffer is open in the
 * source view, and toggling back and forth is normal. [adopt] treats that as an edit anyone would
 * expect to be able to undo, and drops the redo stack, because a redo of a branch the file has
 * since left would silently discard what the user typed.
 */
internal class EditHistory(initial: String) {

    private val past = mutableStateListOf<String>()
    private val future = mutableStateListOf<String>()

    /** What the designer last emitted or accepted. */
    var current by mutableStateOf(initial)
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** An edit the designer made. */
    fun record(next: String) {
        if (next == current) return
        past.add(current)
        if (past.size > DEPTH) past.removeAt(0)
        future.clear()
        current = next
    }

    /** An edit that arrived from outside the designer. */
    fun adopt(source: String) = record(source)

    fun undo(): String? {
        val previous = past.removeLastOrNull() ?: return null
        future.add(current)
        current = previous
        return previous
    }

    fun redo(): String? {
        val next = future.removeLastOrNull() ?: return null
        past.add(current)
        current = next
        return next
    }

    private companion object {
        /** Deep enough to walk back through a session's worth of tweaks, bounded so it cannot grow. */
        const val DEPTH = 50
    }
}
