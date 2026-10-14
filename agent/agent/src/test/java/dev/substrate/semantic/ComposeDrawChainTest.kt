package dev.substrate.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ComposeVisuals.read over a fake modifier chain. The fakes carry the simple class names and
 * backing fields the reader looks up by reflection, as Compose's element classes do.
 */
class ComposeDrawChainTest {

    class DrawBehindElement
    class DrawWithContentElement
    class DrawWithCacheElement
    class GraphicsLayerElement(private val alpha: Float)
    class PaddingElement(
        private val start: Float,
        private val top: Float,
        private val end: Float,
        private val bottom: Float,
    )

    class Info(private val modifier: Any) {
        fun getModifier(): Any = modifier
    }

    class Node(private vararg val elements: Any) {
        fun getModifierInfo(): List<Any> = elements.map { Info(it) }
    }

    private fun read(vararg elements: Any): ComposeDrawInfo =
        ComposeVisuals.read(Node(*elements), density = 1f, fontScale = 1f, resources = null, nodeBounds = null)

    private fun pad(dp: Float) = PaddingElement(dp, dp, dp, dp)

    @Test
    fun every_arbitrary_draw_modifier_marks_a_semantics_free_node_as_drawn() {
        assertFalse(read().draws)
        assertFalse(read(pad(8f)).draws)
        for (element in listOf(DrawBehindElement(), DrawWithContentElement(), DrawWithCacheElement())) {
            val v = read(element)
            assertTrue(element.javaClass.simpleName, v.customDraw)
            assertTrue(element.javaClass.simpleName, v.draws)
        }
    }

    @Test
    fun an_arbitrary_draw_sets_the_padding_boundary() {
        for (element in listOf(DrawBehindElement(), DrawWithContentElement(), DrawWithCacheElement())) {
            // padding(8).drawWithContent { … }.padding(4): 8 is outside the draw, 4 inside.
            val v = read(pad(8f), element, pad(4f))
            assertEquals(element.javaClass.simpleName, MarginInfo(8, 8, 8, 8), v.margin)
            assertEquals(element.javaClass.simpleName, PaddingInfo(4, 4, 4, 4), v.padding)
        }
    }

    @Test
    fun an_alpha_zero_layer_hides_an_arbitrary_draw_after_it_but_not_before_it() {
        for (element in listOf(DrawBehindElement(), DrawWithContentElement(), DrawWithCacheElement())) {
            val after = read(GraphicsLayerElement(0f), element)
            assertFalse(element.javaClass.simpleName, after.customDraw)
            assertFalse(element.javaClass.simpleName, after.draws)
            val before = read(element, GraphicsLayerElement(0f))
            assertTrue(element.javaClass.simpleName, before.draws)
            // With nothing drawn, every padding is the node's own.
            assertNull(read(GraphicsLayerElement(0f), pad(8f), element).margin)
        }
    }
}
