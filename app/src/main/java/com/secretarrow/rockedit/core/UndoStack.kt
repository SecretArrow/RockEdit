package com.secretarrow.rockedit.core

/**
 * Bounded undo/redo stack of full text snapshots.
 * Pure JVM class, aggressively unit tested.
 *
 * Semantics:
 *  - [commit] records the state *before* an edit happened.
 *  - [undo] takes the newest committed state and moves the current state to the redo path.
 *  - [redo] is the exact inverse of [undo].
 *  - Any [commit] invalidates the redo path (standard editor behaviour).
 */
class UndoStack(
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val past = ArrayDeque<String>()
    private val future = ArrayDeque<String>()

    fun canUndo(): Boolean = past.isNotEmpty()

    fun canRedo(): Boolean = future.isNotEmpty()

    /** Records [previousState] (the text before the newest edit). */
    fun commit(previousState: String) {
        past.addLast(previousState)
        while (past.size > limit) {
            past.removeFirst()
        }
        future.clear()
    }

    /**
     * Returns the state to restore after an undo, moving [currentState]
     * onto the redo path. Returns null when there is nothing to undo.
     */
    fun undo(currentState: String): String? {
        val previous = past.removeLastOrNull() ?: return null
        future.addLast(currentState)
        return previous
    }

    /**
     * Returns the state to restore after a redo, moving [currentState]
     * back onto the undo path. Returns null when there is nothing to redo.
     */
    fun redo(currentState: String): String? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(currentState)
        return next
    }

    /** Clears all history. */
    fun clear() {
        past.clear()
        future.clear()
    }

    fun size(): Int = past.size

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
