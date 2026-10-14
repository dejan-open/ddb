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

    // Shapes and corner sizes, with Compose's class names and backing fields.
    class DpCornerSize(private val size: Float)
    class PercentCornerSize(private val percent: Float)
    class RoundedCornerShape(
        private val topStart: Any,
        private val topEnd: Any,
        private val bottomEnd: Any,
        private val bottomStart: Any,
    ) {
        constructor(all: Any) : this(all, all, all, all)
    }
    class AbsoluteRoundedCornerShape(private val topStart: Any, private val bottomStart: Any)
    class CutCornerShape(private val topStart: Any, private val bottomStart: Any)
    class ShadowGraphicsLayerElement(private val elevation: Float, private val shape: Any)
    class ShaderBrush
    class BorderModifierNodeElement(private val width: Float, private val brush: Any, private val shape: Any)

    /** A coordinator with its measured size: an IntSize packed as Compose packs it. */
    class Coordinates(private val packed: Long) {
        fun getSize(): Long = packed
    }

    class SizedInfo(private val modifier: Any, private val coordinates: Any) {
        fun getModifier(): Any = modifier
        fun getCoordinates(): Any = coordinates
    }

    class SizedNode(private vararg val infos: SizedInfo) {
        fun getModifierInfo(): List<Any> = infos.toList()
    }

    private fun measured(width: Int, height: Int) = Coordinates((width.toLong() shl 32) or height.toLong())

    // Text: an AnnotatedString, its span ranges and the styles, with Compose's getters.
    class FontWeight(private val value: Int) {
        fun getWeight(): Int = value
    }
    class TextStyle(private val size: Long, private val weight: FontWeight? = null) {
        fun getFontSize(): Long = size
        fun getFontWeight(): Any? = weight
    }
    class SpanStyle(private val size: Long? = null, private val weight: FontWeight? = null) {
        fun getFontSize(): Long? = size
        fun getFontWeight(): Any? = weight
    }
    class Range(private val style: Any, private val from: Int, private val to: Int) {
        fun getItem(): Any = style
        fun getStart(): Int = from
        fun getEnd(): Int = to
    }
    class AnnotatedString(private val string: String, private vararg val ranges: Range) {
        fun getText(): String = string
        fun getSpanStyles(): List<Any> = ranges.toList()
    }
    class TextAnnotatedStringElement(private val text: Any, private val style: Any)
    class SelectableTextAnnotatedStringElement(private val text: Any, private val style: Any)
    class TextStringSimpleElement(private val text: String, private val style: Any)

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

    private fun readSized(nodeBounds: Bounds?, density: Float, vararg infos: SizedInfo): ComposeDrawInfo =
        ComposeVisuals.read(SizedNode(*infos), density = density, fontScale = 1f, resources = null, nodeBounds = nodeBounds)

    @Test
    fun percent_corners_resolve_against_the_measured_size_not_the_clipped_window_bounds() {
        // A 100 px circle (RoundedCornerShape(50)) whose window bounds an ancestor clips to 20 px wide.
        val circle = RoundedCornerShape(PercentCornerSize(50f))
        val clipped = Bounds(0, 0, 20, 100)
        val shadow = readSized(clipped, 2f, SizedInfo(ShadowGraphicsLayerElement(4f, circle), measured(100, 100)))
        assertEquals(25f, shadow.cornerRadius!!, 0f)
        val border = readSized(clipped, 2f, SizedInfo(BorderModifierNodeElement(1f, ShaderBrush(), circle), measured(100, 100)))
        assertEquals(25f, border.cornerRadius!!, 0f)
        // A coordinator with no readable size falls back to the node's bounds.
        val fallback = ComposeVisuals.read(Node(ShadowGraphicsLayerElement(4f, circle)), 1f, 1f, null, Bounds(0, 0, 40, 40))
        assertEquals(20f, fallback.cornerRadius!!, 0f)
    }

    @Test
    fun corners_larger_than_the_shape_report_the_radius_compose_draws() {
        // RoundedCornerShape(100.dp) on a 48x24 dp button at density 2 draws 12 dp corners.
        val pill = RoundedCornerShape(DpCornerSize(100f))
        assertEquals(12f, readSized(null, 2f, SizedInfo(ShadowGraphicsLayerElement(4f, pill), measured(96, 48))).cornerRadius!!, 1e-4f)
        // One that fits is reported as is.
        val card = RoundedCornerShape(DpCornerSize(8f))
        assertEquals(8f, readSized(null, 2f, SizedInfo(ShadowGraphicsLayerElement(4f, card), measured(96, 48))).cornerRadius!!, 0f)
    }

    @Test
    fun cut_corners_are_not_a_corner_radius() {
        val cut = CutCornerShape(DpCornerSize(8f), DpCornerSize(8f))
        val shadow = readSized(null, 1f, SizedInfo(ShadowGraphicsLayerElement(4f, cut), measured(100, 100)))
        assertNull(shadow.cornerRadius)
        // The shadow still draws; only the radius is left unset.
        assertEquals(4f, shadow.elevation!!, 0f)
        val border = readSized(null, 1f, SizedInfo(BorderModifierNodeElement(1f, ShaderBrush(), cut), measured(100, 100)))
        assertNull(border.cornerRadius)
        assertEquals(1f, border.border!!.width, 0f)
        // An absolute rounded shape is rounded.
        val absolute = AbsoluteRoundedCornerShape(DpCornerSize(8f), DpCornerSize(8f))
        assertEquals(8f, readSized(null, 1f, SizedInfo(ShadowGraphicsLayerElement(4f, absolute), measured(100, 100))).cornerRadius!!, 0f)
    }

    private fun em(value: Float): Long =
        ComposeVisuals.TEXT_UNIT_EM or (java.lang.Float.floatToRawIntBits(value).toLong() and 0xFFFFFFFFL)

    @Test
    fun a_full_range_span_style_sets_the_font_of_annotated_text() {
        val base = TextStyle(ComposeVisuals.packSp(14f), FontWeight(400))
        val boldTwenty = SpanStyle(ComposeVisuals.packSp(20f), FontWeight(700))
        val text = AnnotatedString("Hello", Range(boldTwenty, 0, 5))
        for (element in listOf(TextAnnotatedStringElement(text, base), SelectableTextAnnotatedStringElement(text, base))) {
            assertEquals(element.javaClass.simpleName, FontInfo("sans-serif", "bold", 20f), read(element).font)
        }
        // An em span scales the base size.
        val larger = AnnotatedString("Hello", Range(SpanStyle(em(1.5f)), 0, 5))
        assertEquals(FontInfo("sans-serif", "regular", 21f), read(TextAnnotatedStringElement(larger, base)).font)
        // No spans, a plain String, and a span Compose skips (past the end) leave the base font.
        val plain = FontInfo("sans-serif", "regular", 14f)
        assertEquals(plain, read(TextAnnotatedStringElement(AnnotatedString("Hello"), base)).font)
        assertEquals(plain, read(TextStringSimpleElement("Hello", base)).font)
        assertEquals(plain, read(TextAnnotatedStringElement(AnnotatedString("Hello", Range(boldTwenty, 0, 9)), base)).font)
    }

    @Test
    fun a_font_that_spans_mix_is_not_reported() {
        val base = TextStyle(ComposeVisuals.packSp(14f), FontWeight(400))
        // Bold on part of the text: the weight is mixed, so no one font describes it.
        val partlyBold = AnnotatedString("Hello", Range(SpanStyle(weight = FontWeight(700)), 0, 2))
        assertNull(read(TextAnnotatedStringElement(partlyBold, base)).font)
        val partlyLarge = AnnotatedString("Hello", Range(SpanStyle(ComposeVisuals.packSp(20f)), 3, 5))
        assertNull(read(TextAnnotatedStringElement(partlyLarge, base)).font)
        // Spans that set the same weight over every part leave it uniform.
        val allBold = AnnotatedString(
            "Hello",
            Range(SpanStyle(weight = FontWeight(700)), 0, 2),
            Range(SpanStyle(weight = FontWeight(700)), 2, 5),
        )
        assertEquals(FontInfo("sans-serif", "bold", 14f), read(TextAnnotatedStringElement(allBold, base)).font)
    }
}
