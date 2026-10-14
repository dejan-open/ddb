package dev.substrate.semantic

import androidx.compose.runtime.snapshots.FakeState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import dev.substrate.semantic.ComposeVisuals.ContentDraw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ComposeVisuals.probeContentDraw: whether a drawWithContent block draws the content under it,
 * seen by running it once on a recording scope (the test doubles in FakeDrawScope.kt) inside a
 * read-only snapshot (FakeSnapshot.kt).
 */
class ContentDrawProbeTest {

    private fun probe(
        block: ContentDrawScope.() -> Unit,
        sizePx: Pair<Int, Int>? = Pair(100, 40),
        density: Float = 2f,
    ): ContentDraw = ComposeVisuals.probeContentDraw(block, this, sizePx, density, fontScale = 1f, layoutDirection = null)

    @Test
    fun a_block_that_calls_drawContent_draws_the_content() {
        assertEquals(ContentDraw.CONTENT, probe({ drawContent() }))
        // Drawing before or around it does not change that.
        assertEquals(ContentDraw.CONTENT, probe({ drawRect(0L); drawContent() }))
        assertEquals(ContentDraw.CONTENT, probe({ drawContext.canvas.save(); drawContent(); drawContext.canvas.restore() }))
    }

    @Test
    fun a_block_that_skips_drawContent_hides_the_content() {
        // Modifier.drawWithContent {}: nothing at all is on screen.
        assertEquals(ContentDraw.NOTHING, probe({}))
        // It draws its own pixels instead, through the scope or the canvas.
        assertEquals(ContentDraw.OWN_ONLY, probe({ drawRect(0L) }))
        assertEquals(ContentDraw.OWN_ONLY, probe({ drawContext.canvas.drawCircle(4f) }))
    }

    @Test
    fun the_block_sees_the_node_size_and_density() {
        val expected = ComposeVisuals.packSize(100f, 40f)
        val onlyAtSize: ContentDrawScope.() -> Unit = { if (size == expected) drawContent() }
        assertEquals(ContentDraw.CONTENT, probe(onlyAtSize, sizePx = Pair(100, 40)))
        assertEquals(ContentDraw.NOTHING, probe(onlyAtSize, sizePx = Pair(10, 10)))
        val onlyAtDensity: ContentDrawScope.() -> Unit = { if (density == 2f) drawContent() }
        assertEquals(ContentDraw.CONTENT, probe(onlyAtDensity, density = 2f))
        assertEquals(ContentDraw.NOTHING, probe(onlyAtDensity, density = 3f))
    }

    @Test
    fun graphics_layer_record_runs_on_the_scope_not_the_layer() {
        // drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }
        val layer = Any()
        val recorded: ContentDrawScope.() -> Unit = scope@{ layer.record(0L) { this@scope.drawContent() } }
        assertEquals(ContentDraw.CONTENT, probe(recorded))
        val recordedNothing: ContentDrawScope.() -> Unit = { layer.record(0L) { drawRect(0L) } }
        assertEquals(ContentDraw.OWN_ONLY, probe(recordedNothing))
    }

    @Test
    fun nothing_after_drawContent_runs() {
        var after = false
        assertEquals(ContentDraw.CONTENT, probe({ drawContent(); after = true }))
        assertFalse(after)
    }

    @Test
    fun a_block_that_throws_before_drawContent_is_unknown() {
        // It reached for something the recording scope can't give (the platform canvas, say).
        assertEquals(ContentDraw.UNKNOWN, probe({ error("platform canvas") }))
        val fail = true
        assertEquals(ContentDraw.UNKNOWN, probe({ drawRect(0L); if (fail) error("platform canvas"); drawContent() }))
        // Throwing after drawContent() changes nothing: the content was drawn.
        var thrown = false
        val catchesEverything: ContentDrawScope.() -> Unit = {
            try {
                drawContent()
            } catch (e: Throwable) {
                thrown = true
            }
        }
        // A block that swallows the probe's stop still counts as drawing the content.
        assertEquals(ContentDraw.CONTENT, probe(catchesEverything))
        assertTrue(thrown)
    }

    @Test
    fun the_block_runs_in_a_read_only_snapshot_that_is_disposed() {
        val before = Snapshot.taken.size
        var inside: Snapshot? = null
        assertEquals(ContentDraw.CONTENT, probe({ inside = Snapshot.current; drawContent() }))
        // It ran inside the snapshot the probe took, which is left again and disposed, also
        // when the run ends at drawContent().
        assertEquals(before + 1, Snapshot.taken.size)
        assertSame(Snapshot.taken.last(), inside)
        assertNull(Snapshot.current)
        assertTrue(Snapshot.taken.last().disposed)
        // And when the block throws.
        assertEquals(ContentDraw.UNKNOWN, probe({ error("platform canvas") }))
        assertNull(Snapshot.current)
        assertTrue(Snapshot.taken.last().disposed)
    }

    @Test
    fun a_state_write_never_lands_and_keeps_the_content() {
        val state = FakeState(0)
        // A block that writes app state is stopped at the write: the state keeps its value, and
        // the block is unknown, so the content stays in the capture (never seen skipped).
        assertEquals(ContentDraw.UNKNOWN, probe({ state.value = 1; drawContent() }))
        assertEquals(ContentDraw.UNKNOWN, probe({ state.value = 2 }))
        assertEquals(ContentDraw.UNKNOWN, probe({ drawRect(0L); state.value = 3 }))
        assertEquals(0, state.value)
        // Reads are fine: the block still decides on what it reads.
        val show = FakeState(true)
        assertEquals(ContentDraw.CONTENT, probe({ if (show.value) drawContent() }))
        show.value = false
        assertEquals(ContentDraw.NOTHING, probe({ if (show.value) drawContent() }))
        // A write after drawContent() never runs: the probe stops there.
        assertEquals(ContentDraw.CONTENT, probe({ drawContent(); state.value = 4 }))
        assertEquals(0, state.value)
    }

    @Test
    fun with_no_read_only_snapshot_nothing_runs() {
        // A class loader with no Compose runtime on its path (the bootstrap loader).
        val noRuntime = object : ClassLoader(null) {}
        var ran = false
        assertFalse(ComposeVisuals.runInReadOnlySnapshot(noRuntime) { ran = true })
        assertFalse(ran)
        // With the runtime, the body runs, and its exception comes back as is.
        val loader = javaClass.classLoader
        assertTrue(ComposeVisuals.runInReadOnlySnapshot(loader) { ran = true })
        assertTrue(ran)
        val thrown = assertThrows(IllegalStateException::class.java) {
            ComposeVisuals.runInReadOnlySnapshot(loader) { FakeState(0).value = 1 }
        }
        assertEquals("Cannot modify a state object in a read-only snapshot", thrown.message)
    }

    @Test
    fun a_block_that_cannot_be_run_is_unknown() {
        assertEquals(
            ContentDraw.UNKNOWN,
            ComposeVisuals.probeContentDraw(null, this, Pair(1, 1), 1f, 1f, null),
        )
        assertEquals(
            ContentDraw.UNKNOWN,
            ComposeVisuals.probeContentDraw("not a block", this, Pair(1, 1), 1f, 1f, null),
        )
    }
}
