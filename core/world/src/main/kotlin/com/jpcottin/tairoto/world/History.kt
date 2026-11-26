package com.jpcottin.tairoto.world

/**
 * One change to the lagoon that can be taken back. Each knows enough to undo
 * itself and to do itself again: a netted creature keeps the place it was
 * netted, so undoing puts it back there, not where it was first released.
 */
sealed interface Edit {
    /** What the undo button says it will undo, for a person. */
    val label: String

    data class Released(val creatures: List<CreatureSpec>) : Edit {
        override val label: String
            get() {
                val species = creatures.firstOrNull()?.speciesId?.let { SpeciesCatalog[it] }
                val name = species?.displayName ?: "creatures"
                return if (creatures.size == 1) "release of a $name" else "release of ${creatures.size} $name"
            }
    }

    data class Netted(val creature: CreatureSpec) : Edit {
        override val label: String get() = "netting the " + (SpeciesCatalog[creature.speciesId]?.displayName ?: "creature")
    }

    data class Placed(val decor: DecorSpec) : Edit {
        override val label: String get() = "placing " + (DecorCatalog[decor.kindId]?.displayName ?: "a decoration")
    }

    data class Moved(val from: DecorSpec, val to: DecorSpec) : Edit {
        override val label: String get() = "moving " + (DecorCatalog[to.kindId]?.displayName ?: "a decoration")
    }

    data class Removed(val decor: DecorSpec) : Edit {
        override val label: String get() = "removing " + (DecorCatalog[decor.kindId]?.displayName ?: "a decoration")
    }
}

/** Whether there is anything to undo or redo, and what. */
data class UndoState(val undo: String? = null, val redo: String? = null) {
    val canUndo: Boolean get() = undo != null
    val canRedo: Boolean get() = redo != null
}

/** The edits made, newest last, and the ones undone, ready to be redone. */
class UndoHistory(private val limit: Int = 60) {
    private val done = ArrayDeque<Edit>()
    private val undone = ArrayDeque<Edit>()

    val state: UndoState get() = UndoState(done.lastOrNull()?.label, undone.lastOrNull()?.label)

    /** A new edit: whatever was undone can no longer be redone. */
    fun record(edit: Edit) {
        done.addLast(edit)
        while (done.size > limit) done.removeFirst()
        undone.clear()
    }

    fun popUndo(): Edit? = done.removeLastOrNull()?.also { undone.addLast(it) }

    fun popRedo(): Edit? = undone.removeLastOrNull()?.also { done.addLast(it) }

    fun clear() {
        done.clear()
        undone.clear()
    }
}
