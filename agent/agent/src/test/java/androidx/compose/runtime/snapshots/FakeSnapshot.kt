package androidx.compose.runtime.snapshots

/**
 * Test double for Compose's Snapshot, with the members ComposeVisuals.runInReadOnlySnapshot
 * reaches by reflection: Companion.takeSnapshot(readObserver), enter(block) and dispose().
 * Unit tests have no Compose runtime on the classpath. As in Compose, takeSnapshot() returns a
 * read-only snapshot, enter() makes it current for the block and restores the previous one, and
 * a state write while it is current throws ([FakeState]).
 */
class Snapshot private constructor() {
    var disposed = false
        private set

    fun <T> enter(block: () -> T): T {
        val previous = current
        current = this
        try {
            return block()
        } finally {
            current = previous
        }
    }

    fun dispose() {
        disposed = true
    }

    companion object {
        /** The entered snapshot, null for the global one. */
        var current: Snapshot? = null
            private set

        /** Every snapshot taken, in order. */
        val taken = mutableListOf<Snapshot>()

        fun takeSnapshot(readObserver: ((Any) -> Unit)? = null): Snapshot = Snapshot().also { taken += it }
    }
}

/** A state object like mutableStateOf's: a write inside a read-only snapshot throws, as in Compose. */
class FakeState<T>(initial: T) {
    var value: T = initial
        set(v) {
            check(Snapshot.current == null) { "Cannot modify a state object in a read-only snapshot" }
            field = v
        }
}
