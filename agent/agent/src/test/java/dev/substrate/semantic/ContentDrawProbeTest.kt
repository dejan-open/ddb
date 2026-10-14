package dev.substrate.semantic

import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import dev.substrate.semantic.ComposeVisuals.ContentDraw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ComposeVisuals.probeContentDraw: whether a drawWithContent block draws the content under it,
 * seen by running it once on a recording scope (the test doubles in FakeDrawScope.kt).
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
