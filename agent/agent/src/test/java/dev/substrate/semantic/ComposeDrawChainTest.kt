package dev.substrate.semantic

import androidx.compose.ui.graphics.drawscope.ContentDrawScope
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
    class DrawWithContentElement(private val onDraw: (ContentDrawScope.() -> Unit)? = null)
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

    private fun border() = BorderModifierNodeElement(1f, ShaderBrush(), Any())

    @Test
    fun a_drawWithContent_that_skips_drawContent_hides_what_follows_it() {
        // drawWithContent { drawRect(…) }: the block's own draw replaces everything after it in
        // the chain (here a border; on a Text, the text) and the node's children.
        val replaced = read(DrawWithContentElement { drawRect(0L) }, border())
        assertTrue(replaced.contentSkipped)
        assertTrue(replaced.hidesContent(1f))
        assertNull(replaced.border)
        assertTrue(replaced.customDraw)
        assertTrue(replaced.draws)
        // drawWithContent {}: nothing at all is on screen.
        val empty = read(DrawWithContentElement {}, border())
        assertTrue(empty.hidesContent(1f))
        assertFalse(empty.customDraw)
        assertFalse(empty.draws)
        // A shadow after it is not drawn either.
        assertNull(read(DrawWithContentElement {}, ShadowGraphicsLayerElement(4f, Any())).elevation)
    }

    @Test
    fun what_comes_before_a_skipped_drawContent_still_draws() {
        val v = read(border(), DrawWithContentElement {})
        assertEquals(1f, v.border!!.width, 0f)
        assertTrue(v.draws)
        assertTrue(v.hidesContent(1f))
    }

    @Test
    fun a_drawWithContent_that_calls_drawContent_keeps_what_follows_it() {
        val v = read(DrawWithContentElement { drawRect(0L); drawContent() }, border())
        assertFalse(v.contentSkipped)
        assertFalse(v.hidesContent(1f))
        assertEquals(1f, v.border!!.width, 0f)
        assertTrue(v.customDraw)
    }

    @Test
    fun a_content_block_that_draws_nothing_sets_no_padding_boundary() {
        // padding(8).drawWithContent {}.padding(4): no draw, so all 12 are the node's own.
        val v = read(pad(8f), DrawWithContentElement {}, pad(4f))
        assertNull(v.margin)
        assertEquals(PaddingInfo(12, 12, 12, 12), v.padding)
    }

    @Test
    fun a_skipped_drawContent_hides_the_subtree_like_an_alpha_zero_layer() {
        assertFalse(ComposeDrawInfo().hidesContent(1f))
        assertTrue(ComposeDrawInfo().hidesContent(0f))
        assertTrue(ComposeDrawInfo(contentSkipped = true).hidesContent(1f))
    }

    // A drawWithCache node as Compose keeps it: the LayoutNode's NodeChain from its head through
    // each node's child, and the content block its CacheDrawScope last built (a DrawResult).
    open class ChainNode {
        var child: Any? = null
    }
    class TailModifierNode : ChainNode()
    class CacheDrawModifierNodeImpl(private val cacheDrawScope: Any) : ChainNode()
    class CacheDrawScope(private val drawResult: Any?)
    class DrawResult(private val block: ContentDrawScope.() -> Unit)
    class NodeChain(private val head: Any)

    class ChainedNode(private val nodes: Any, private vararg val elements: Any) {
        fun getModifierInfo(): List<Any> = elements.map { Info(it) }
    }

    private fun cacheNode(block: (ContentDrawScope.() -> Unit)?) =
        CacheDrawModifierNodeImpl(CacheDrawScope(block?.let { DrawResult(it) }))

    private fun chain(vararg nodes: ChainNode): NodeChain {
        val all = nodes.toList() + TailModifierNode()
        for (i in 0 until all.size - 1) all[i].child = all[i + 1]
        return NodeChain(all.first())
    }

    private fun readChained(chain: NodeChain, vararg elements: Any): ComposeDrawInfo =
        ComposeVisuals.read(ChainedNode(chain, *elements), density = 1f, fontScale = 1f, resources = null, nodeBounds = null)

    @Test
    fun a_drawWithCache_content_block_is_read_from_its_live_node() {
        // onDrawWithContent { drawRect(…) }, after an unrelated node: the content is skipped.
        val skips = readChained(chain(ChainNode(), cacheNode { drawRect(0L) }), DrawWithCacheElement(), border())
        assertTrue(skips.contentSkipped)
        assertNull(skips.border)
        assertTrue(skips.customDraw)
        // onDrawBehind's block draws, then calls drawContent().
        val behind = readChained(chain(cacheNode { drawRect(0L); drawContent() }), DrawWithCacheElement(), border())
        assertFalse(behind.contentSkipped)
        assertEquals(1f, behind.border!!.width, 0f)
        // A node that has not drawn yet (no DrawResult), or a chain whose cache nodes don't line
        // up with the elements, is unknown: the content is kept, as before.
        val unbuilt = readChained(chain(cacheNode(null)), DrawWithCacheElement(), border())
        assertFalse(unbuilt.contentSkipped)
        assertTrue(unbuilt.customDraw)
        val misaligned = readChained(chain(cacheNode {}), DrawWithCacheElement(), DrawWithCacheElement(), border())
        assertFalse(misaligned.contentSkipped)
        assertEquals(1f, misaligned.border!!.width, 0f)
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
    fun the_reported_corner_is_scaled_against_every_side_it_shares() {
        val none = DpCornerSize(0f)
        // RoundedCornerShape(topStart = 80px, topEnd = 80px) on a 100 px square: the start side
        // fits, the top side does not, and Compose draws both top corners at 50 px.
        val topHeavy = RoundedCornerShape(DpCornerSize(80f), DpCornerSize(80f), none, none)
        val shadow = readSized(null, 1f, SizedInfo(ShadowGraphicsLayerElement(4f, topHeavy), measured(100, 100)))
        assertEquals(50f, shadow.cornerRadius!!, 1e-4f)
        val border = readSized(null, 1f, SizedInfo(BorderModifierNodeElement(1f, ShaderBrush(), topHeavy), measured(100, 100)))
        assertEquals(50f, border.cornerRadius!!, 1e-4f)
        // A bottom side too short for its corners shrinks the top-start corner by the same factor.
        val bottomHeavy = RoundedCornerShape(DpCornerSize(10f), DpCornerSize(10f), DpCornerSize(60f), DpCornerSize(60f))
        val card = readSized(null, 1f, SizedInfo(ShadowGraphicsLayerElement(4f, bottomHeavy), measured(100, 80)))
        assertEquals(10f * 100f / 120f, card.cornerRadius!!, 1e-4f)
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

    @Test
    fun a_border_reports_the_width_compose_strokes() {
        val rect = Any()
        // border(100.dp) on a 40 dp-high node at density 2 is clamped to half of 80 px: 20 dp.
        val oversized = readSized(null, 2f, SizedInfo(BorderModifierNodeElement(100f, ShaderBrush(), rect), measured(200, 80)))
        assertEquals(20f, oversized.border!!.width, 0f)
        // border(1.dp) at density 2.625 is 2.625 px, stroked as 3.
        val fractional = readSized(null, 2.625f, SizedInfo(BorderModifierNodeElement(1f, ShaderBrush(), rect), measured(262, 105)))
        assertEquals(3f / 2.625f, fractional.border!!.width, 1e-6f)
        // The clamp is against the measured size, not window bounds an ancestor clips.
        val clipped = readSized(Bounds(0, 0, 10, 10), 1f, SizedInfo(BorderModifierNodeElement(8f, ShaderBrush(), rect), measured(100, 100)))
        assertEquals(8f, clipped.border!!.width, 0f)
    }

    // Text colors: a TextStyle's and a SpanStyle's color, brush and alpha, with Compose's getters.
    class SolidColor(private val value: Long)
    class LinearGradient
    class ColorTextStyle(private val color: Long, private val brush: Any? = null, private val alpha: Float = Float.NaN) {
        fun getFontSize(): Long = ComposeVisuals.packSp(14f)
        fun getColor(): Long = color
        fun getBrush(): Any? = brush
        fun getAlpha(): Float = alpha
    }
    class ColorSpanStyle(private val color: Long, private val brush: Any? = null) {
        fun getColor(): Long = color
        fun getBrush(): Any? = brush
        fun getAlpha(): Float = Float.NaN
    }

    private val red = 0xFFFF0000L shl 32
    private val blue = 0xFF0000FFL shl 32
    private val unspecified = ComposeVisuals.UNSPECIFIED_COLOR

    private fun textColor(style: Any, vararg spans: Range): String? =
        read(TextAnnotatedStringElement(AnnotatedString("Hello", *spans), style)).textColor

    @Test
    fun a_solid_text_brush_is_drawn_as_a_color_that_a_color_span_replaces() {
        val redBrush = ColorTextStyle(unspecified, SolidColor(red))
        assertEquals("#FF0000", textColor(redBrush))
        // A full-range SpanStyle(color = Blue) over it draws the text blue.
        assertEquals("#0000FF", textColor(redBrush, Range(ColorSpanStyle(blue), 0, 5)))
        // The brush alpha applies to the solid color, as AndroidTextPaint.setBrush applies it.
        assertEquals("#80FF0000", textColor(ColorTextStyle(unspecified, SolidColor(red), alpha = 0.5f)))
        // A plain color style, for comparison.
        assertEquals("#0000FF", textColor(ColorTextStyle(red), Range(ColorSpanStyle(blue), 0, 5)))
    }

    @Test
    fun a_shader_text_brush_keeps_drawing_under_a_color_span() {
        val gradient = ColorTextStyle(unspecified, LinearGradient())
        assertNull(textColor(gradient))
        assertNull(textColor(gradient, Range(ColorSpanStyle(blue), 0, 5)))
        // A solid span brush is a color span too: the shader still draws over it.
        assertNull(textColor(gradient, Range(ColorSpanStyle(unspecified, SolidColor(blue)), 0, 5)))
        // Over a color it sets its own color, and a shader span brush hides any one color.
        assertEquals("#0000FF", textColor(ColorTextStyle(red), Range(ColorSpanStyle(unspecified, SolidColor(blue)), 0, 5)))
        assertNull(textColor(ColorTextStyle(red), Range(ColorSpanStyle(unspecified, LinearGradient()), 0, 5)))
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
