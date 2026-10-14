package dev.substrate.semantic

import androidx.compose.runtime.snapshots.FakeState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ReusableGraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.PlacedCoordinates
import androidx.compose.ui.layout.WindowRect
import coil3.compose.AsyncImagePainter
import coil3.compose.FakeImageState
import coil3.compose.FakeMutableState
import coil3.compose.FakeStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    class GraphicsLayerElement(
        private val alpha: Float,
        private val clip: Boolean = false,
        private val shape: Any? = null,
        private val scaleX: Float = 1f,
        private val scaleY: Float = 1f,
    )
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
    class ShadowGraphicsLayerElement(private val elevation: Float, private val shape: Any, private val clip: Boolean = false)
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
    class TextStringSimpleElement(private val text: String, private val style: Any, private val color: Any? = null)

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

    enum class LayoutDirection { Ltr, Rtl }

    class DirectedNode(private val direction: LayoutDirection, private vararg val infos: SizedInfo) {
        fun getModifierInfo(): List<Any> = infos.toList()
        fun getLayoutDirection(): Any = direction
    }

    private fun readDirected(direction: LayoutDirection, vararg infos: SizedInfo): ComposeDrawInfo =
        ComposeVisuals.read(DirectedNode(direction, *infos), density = 1f, fontScale = 1f, resources = null, nodeBounds = null)

    @Test
    fun the_reported_corner_is_the_physical_top_left_in_rtl() {
        val none = DpCornerSize(0f)
        // RoundedCornerShape(topStart = 16.dp, topEnd = 0.dp): in RTL Compose draws topEnd at the
        // top left, so there is no top-left radius; in LTR it is topStart's 16.
        val startOnly = RoundedCornerShape(DpCornerSize(16f), none, none, none)
        assertNull(readDirected(LayoutDirection.Rtl, SizedInfo(ShadowGraphicsLayerElement(4f, startOnly), measured(100, 100))).cornerRadius)
        assertEquals(16f, readDirected(LayoutDirection.Ltr, SizedInfo(ShadowGraphicsLayerElement(4f, startOnly), measured(100, 100))).cornerRadius!!, 0f)
        // topStart = 0, topEnd = 16 in RTL: the top-left corner is 16.
        val endOnly = RoundedCornerShape(none, DpCornerSize(16f), none, none)
        assertEquals(16f, readDirected(LayoutDirection.Rtl, SizedInfo(ShadowGraphicsLayerElement(4f, endOnly), measured(100, 100))).cornerRadius!!, 0f)
        // An AbsoluteRoundedCornerShape is physical in either direction: its topLeft (stored as topStart).
        val absolute = AbsoluteRoundedCornerShape(DpCornerSize(8f), none)
        assertEquals(8f, readDirected(LayoutDirection.Rtl, SizedInfo(ShadowGraphicsLayerElement(4f, absolute), measured(100, 100))).cornerRadius!!, 0f)
        assertEquals(8f, readDirected(LayoutDirection.Ltr, SizedInfo(ShadowGraphicsLayerElement(4f, absolute), measured(100, 100))).cornerRadius!!, 0f)
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

    /** A Compose Color in a List: boxed, with the `unbox-impl` getter the reader calls. */
    @JvmInline value class StopColor(val value: Long)
    class LinearGradient(private val colors: List<StopColor>? = null)
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

    // A background with Compose's fields: a solid color (or Unspecified with a brush) and a shape.
    class BackgroundElement(private val color: Long, private val shape: Any, private val brush: Any? = null)

    @Test
    fun a_clip_or_shadow_layer_after_the_first_draw_does_not_round_it() {
        val rounded = RoundedCornerShape(DpCornerSize(8f))
        val square = Any()
        // background(Red).clip(RoundedCornerShape(8.dp)): the background is drawn square, and
        // the clip shapes only what comes after it.
        assertNull(read(BackgroundElement(red, square), GraphicsLayerElement(1f, clip = true, shape = rounded)).cornerRadius)
        // background(Red).shadow(4.dp, RoundedCornerShape(8.dp)): the same; the shadow is still drawn.
        val shadowAfter = read(BackgroundElement(red, square), ShadowGraphicsLayerElement(4f, rounded))
        assertNull(shadowAfter.cornerRadius)
        assertEquals(4f, shadowAfter.elevation!!, 0f)
        // A clip or shadow before the first draw encloses it, and rounds it.
        assertEquals(8f, read(GraphicsLayerElement(1f, clip = true, shape = rounded), BackgroundElement(red, square)).cornerRadius!!, 0f)
        assertEquals(8f, read(ShadowGraphicsLayerElement(4f, rounded), BackgroundElement(red, square)).cornerRadius!!, 0f)
        assertEquals(8f, read(ShadowGraphicsLayerElement(0f, rounded, clip = true), BackgroundElement(red, square)).cornerRadius!!, 0f)
        // A layer that neither clips nor draws a shadow (shadow(0.dp, shape), clip = false) shapes nothing.
        assertNull(read(ShadowGraphicsLayerElement(0f, rounded), BackgroundElement(red, square)).cornerRadius)
        assertNull(read(GraphicsLayerElement(1f, shape = rounded), BackgroundElement(red, square)).cornerRadius)
    }

    // graphicsLayer { … }: the block, run on the scope in FakeGraphicsLayerScope.kt. The node
    // carries a Compose Density, which the layer scope is given before the block runs.
    class BlockGraphicsLayerElement(private val block: (ReusableGraphicsLayerScope) -> Unit)
    class DensityNode(private vararg val elements: Any) {
        fun getModifierInfo(): List<Any> = elements.map { Info(it) }
        fun getDensity(): Any = Any()
    }

    private fun readLayered(vararg elements: Any): ComposeDrawInfo =
        ComposeVisuals.read(DensityNode(*elements), density = 1f, fontScale = 1f, resources = null, nodeBounds = null)

    @Test
    fun a_graphicsLayer_block_runs_in_a_read_only_snapshot() {
        // It runs inside a snapshot, which is disposed afterwards, and what it sets is read.
        var inside: Snapshot? = null
        val faded = readLayered(BlockGraphicsLayerElement { inside = Snapshot.current; it.alpha = 0f }, BackgroundElement(red, Any()))
        assertNotNull(inside)
        assertTrue(inside!!.disposed)
        assertNull(Snapshot.current)
        assertNull(faded.background)
        assertEquals(0f, faded.alpha!!, 0f)
        // A block that writes app state is refused there: the write never lands, and the layer
        // is unreadable, so the content under it is kept and no alpha is reported for it.
        val counter = FakeState(0)
        val writes = readLayered(
            BlockGraphicsLayerElement { counter.value = counter.value + 1; it.alpha = 0f },
            BackgroundElement(red, Any()),
        )
        assertEquals(0, counter.value)
        assertEquals("#FF0000", writes.background)
        assertNull(writes.alpha)
        // A block that throws for any other reason is unreadable the same way.
        val throws = readLayered(BlockGraphicsLayerElement { it.alpha = 0f; error("boom") }, BackgroundElement(red, Any()))
        assertEquals("#FF0000", throws.background)
        assertNull(throws.alpha)
    }

    // Painters: Modifier.paint / Image, and Coil 3's AsyncImage elements, with their fields.
    class BitmapPainter
    class ColorPainter(private val color: Long)
    class VectorPainter
    class PainterElement(private val painter: Any?, private val colorFilter: Any? = null)

    // ColorFilter.tint(color, blendMode): a BlendModeColorFilter, with the packed color and the
    // BlendMode's value (the fake BlendMode in FakeBlendMode.kt names them).
    class BlendModeColorFilter(private val color: Long, private val blendMode: Int)
    class ContentPainterElement
    class SubcomposeContentPainterElement(private val painter: Any?)
    class ContentPainterNode(private val painter: Any?) : ChainNode()

    private fun asyncPainter(current: Any?) = AsyncImagePainter(FakeMutableState(current))

    private fun asyncImage(painter: AsyncImagePainter?, vararg around: Any): ComposeDrawInfo {
        val nodes = if (painter == null) chain() else chain(ContentPainterNode(painter))
        return readChained(nodes, *around, ContentPainterElement())
    }

    @Test
    fun a_coil_async_image_is_an_image() {
        // AsyncImage(model = url): a ContentPainterElement, whose painter is on its live node.
        val loaded = asyncImage(asyncPainter(BitmapPainter()))
        assertEquals("loaded", loaded.imageType)
        assertTrue(loaded.draws)
        // SubcomposeAsyncImage keeps the painter on the element.
        assertEquals("loaded", read(SubcomposeContentPainterElement(asyncPainter(BitmapPainter()))).imageType)
        // A node that can't be found is still an image, as before its painter could be read.
        assertEquals("loaded", asyncImage(null).imageType)
        // It sets the padding boundary like any image.
        val padded = asyncImage(asyncPainter(BitmapPainter()), pad(8f))
        assertEquals(MarginInfo(8, 8, 8, 8), padded.margin)
    }

    @Test
    fun a_coil_painter_with_nothing_to_draw_is_no_image() {
        // State.Empty, or Loading with no placeholder: AsyncImagePainter draws nothing.
        val empty = asyncImage(asyncPainter(null))
        assertNull(empty.imageType)
        assertFalse(empty.draws)
        // No draw, so no padding boundary: all of it is the node's own.
        val padded = asyncImage(asyncPainter(null), pad(8f))
        assertNull(padded.margin)
        assertEquals(PaddingInfo(8, 8, 8, 8), padded.padding)
        // The same through Image(painter = rememberAsyncImagePainter(url)) and SubcomposeAsyncImage.
        assertNull(read(PainterElement(asyncPainter(null))).imageType)
        assertNull(read(SubcomposeContentPainterElement(asyncPainter(null))).imageType)
        // Without the painter property, the current state's painter is read instead.
        val fromState = { painter: Any? -> AsyncImagePainter(null, FakeStateFlow(FakeImageState(painter))) }
        assertNull(read(PainterElement(fromState(null))).imageType)
        assertEquals("loaded", read(PainterElement(fromState(BitmapPainter()))).imageType)
        // A painter whose current painter can't be read at all is still an image.
        assertEquals("loaded", read(PainterElement(AsyncImagePainter(null))).imageType)
    }

    @Test
    fun a_coil_color_placeholder_is_a_background() {
        // placeholder = ColorPainter(Gray) while loading: a fill, not an image.
        val v = asyncImage(asyncPainter(ColorPainter(red)))
        assertEquals("#FF0000", v.background)
        assertNull(v.imageType)
        // Other painters keep their own types.
        assertEquals("raster", read(PainterElement(BitmapPainter())).imageType)
    }

    // A text field's layout: its TextLayoutResult and the input it was laid out from.
    class TextLayoutInput(private val text: Any, private val style: Any) {
        fun getText(): Any = text
        fun getStyle(): Any = style
    }
    class TextLayoutResult(private val input: Any) {
        fun getLayoutInput(): Any = input
    }

    @Test
    fun a_text_field_reports_the_font_and_color_of_its_layout() {
        // BasicTextField(textStyle = TextStyle(color = Red)): no text element in the chain, but
        // its semantics expose the layout, with the style the text is drawn in.
        val layout = TextLayoutResult(TextLayoutInput(AnnotatedString("Hello"), ColorTextStyle(red)))
        val field = ComposeVisuals.withLayoutText(ComposeDrawInfo(), layout, null, 1f, null, alpha = 1f)
        assertEquals(FontInfo("sans-serif", "regular", 14f), field.font)
        assertEquals("#FF0000", field.textColor)
        // The node's alpha applies to the color, as it does to a text element's.
        assertEquals("#80FF0000", ComposeVisuals.withLayoutText(ComposeDrawInfo(), layout, null, 1f, null, alpha = 0.5f).textColor)
        // A full-range color span in the field's text draws over the style.
        val spanned = TextLayoutResult(TextLayoutInput(AnnotatedString("Hello", Range(ColorSpanStyle(blue), 0, 5)), ColorTextStyle(red)))
        assertEquals("#0000FF", ComposeVisuals.withLayoutText(ComposeDrawInfo(), spanned, null, 1f, null, alpha = 1f).textColor)
        // What the modifier chain gave is kept, and with no layout nothing changes.
        val chain = ComposeDrawInfo(font = FontInfo("serif", "bold", 20f), textColor = "#00FF00")
        assertEquals(chain, ComposeVisuals.withLayoutText(chain, layout, null, 1f, null, alpha = 1f))
        assertEquals(ComposeDrawInfo(), ComposeVisuals.withLayoutText(ComposeDrawInfo(), null, null, 1f, null, alpha = 1f))
    }

    /** Color.Transparent, packed: ARGB 0 in the high 32 bits. */
    private val transparent = 0L

    private fun tint(color: Long, mode: Int = BlendMode.SrcIn) = BlendModeColorFilter(color, mode)

    @Test
    fun a_transparent_tint_that_replaces_the_painter_hides_it() {
        // paint(vectorPainter, colorFilter = ColorFilter.tint(Color.Transparent)): SrcIn turns every
        // pixel the painter draws transparent, so nothing is on screen: no image, no tint, no draw.
        val hidden = read(PainterElement(VectorPainter(), tint(transparent)))
        assertNull(hidden.imageType)
        assertNull(hidden.imageTint)
        assertFalse(hidden.draws)
        // Src replaces the pixels the same way.
        assertNull(read(PainterElement(VectorPainter(), tint(transparent, BlendMode.Src))).imageType)
        // Nothing drawn, so no padding boundary: all of it is the node's own.
        assertNull(read(pad(8f), PainterElement(VectorPainter(), tint(transparent)), pad(4f)).margin)
        // SrcAtop draws the tint over the painter's pixels: a transparent one leaves them as they are.
        assertEquals("vector", read(PainterElement(VectorPainter(), tint(transparent, BlendMode.SrcAtop))).imageType)
        // A visible tint keeps the image, in the tint's color.
        val tinted = read(PainterElement(VectorPainter(), tint(red)))
        assertEquals("vector", tinted.imageType)
        assertEquals("#FF0000", tinted.imageTint)
    }

    @Test
    fun a_layer_scaled_to_zero_hides_what_follows_it() {
        // size(12.dp).scale(0f).background(Red): the layer has no area, so the background is not
        // on screen and the node is skipped, as at alpha 0.
        // The walk skips a node whose subtree alpha (inherited × its own layers', `alpha`) hides it.
        fun skipped(v: ComposeDrawInfo) = v.hidesContent(v.alpha ?: 1f)
        val collapsed = read(GraphicsLayerElement(1f, scaleX = 0f, scaleY = 0f), BackgroundElement(red, Any()))
        assertNull(collapsed.background)
        assertFalse(collapsed.draws)
        assertTrue(skipped(collapsed))
        // One axis at 0 is enough.
        assertNull(read(GraphicsLayerElement(1f, scaleY = 0f), BackgroundElement(red, Any())).background)
        // scale(0.5f) shrinks it, but it is still drawn.
        val half = read(GraphicsLayerElement(1f, scaleX = 0.5f, scaleY = 0.5f), BackgroundElement(red, Any()))
        assertEquals("#FF0000", half.background)
        assertFalse(skipped(half))
        // graphicsLayer { scaleX = 0f } is read from the block the same way.
        val block = readLayered(BlockGraphicsLayerElement { it.scaleX = 0f }, BackgroundElement(red, Any()))
        assertNull(block.background)
        assertTrue(skipped(block))
        val halfBlock = readLayered(BlockGraphicsLayerElement { it.scaleX = 0.5f; it.scaleY = 0.5f }, BackgroundElement(red, Any()))
        assertEquals("#FF0000", halfBlock.background)
    }

    // Material 3's minimumInteractiveComponentSize(): a layout modifier that grows the node's box.
    class MinimumInteractiveModifier

    private fun placed(left: Int, top: Int, size: Int) =
        PlacedCoordinates(WindowRect(left.toFloat(), top.toFloat(), (left + size).toFloat(), (top + size).toFloat()))

    @Test
    fun a_minimum_interactive_size_reports_the_drawn_shape_and_taps_the_whole_box() {
        // Surface(onClick = …, modifier = Modifier.size(40.dp)) in Material 3: the node is 48 × 48,
        // and the surface's background is drawn 40 × 40 in the middle of it.
        val node = Bounds(0, 0, 48, 48)
        val v = readSized(
            node, 1f,
            SizedInfo(MinimumInteractiveModifier(), placed(0, 0, 48)),
            SizedInfo(BackgroundElement(red, Any()), placed(4, 4, 40)),
        )
        val drawn = Bounds(4, 4, 40, 40)
        assertEquals(drawn, v.drawBounds)
        assertNull(v.margin)
        // The element is the 40 dp shape; a tap anywhere in the 48 dp box still lands on it.
        assertEquals(
            ViewTreeWalker.ComposeBox(drawn, null, tapTarget = node),
            ViewTreeWalker.composeBox(touchable = true, bounds = node, drawBounds = v.drawBounds, margin = v.margin),
        )
    }

    // Modifier.clickable { }: the element that gives the node its click.
    class ClickableElement

    // A modifier is attached to the coordinator of the next layout modifier inward: one before
    // the padding sits on the padding's 64 dp box, one after it on the 48 dp box inside.
    private fun clickAndPadding(vararg first: Any): ComposeDrawInfo {
        val padAt = first.indexOfFirst { it is PaddingElement }
        return readSized(
            Bounds(0, 0, 64, 64), 1f,
            *first.mapIndexed { i, e -> SizedInfo(e, if (i <= padAt) placed(0, 0, 64) else placed(8, 8, 48)) }.toTypedArray(),
            SizedInfo(BackgroundElement(red, Any()), placed(8, 8, 48)),
        )
    }

    // Layout modifiers the reader doesn't read: size and wrapContentSize.
    class SizeElement
    class WrapContentElement

    @Test
    fun a_click_inside_a_smaller_layout_box_taps_that_box_only() {
        // Modifier.size(96.dp).wrapContentSize().clickable { }.size(40.dp).background(Red): the
        // click is attached to the centred 40 dp box, which is also the drawn box, so the rest of
        // the 96 dp node is no tap target.
        val node = Bounds(0, 0, 96, 96)
        val inner = Bounds(28, 28, 40, 40)
        val v = readSized(
            node, 1f,
            SizedInfo(SizeElement(), placed(0, 0, 96)),
            SizedInfo(WrapContentElement(), placed(0, 0, 96)),
            SizedInfo(ClickableElement(), placed(28, 28, 40)),
            SizedInfo(SizeElement(), placed(28, 28, 40)),
            SizedInfo(BackgroundElement(red, Any()), placed(28, 28, 40)),
        )
        assertEquals(inner, v.clickBounds)
        assertEquals(inner, v.drawBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(inner, null),
            ViewTreeWalker.composeBox(true, node, v.drawBounds, v.margin, v.clickBounds),
        )
        // Material 3's Surface(onClick = …) puts the click after minimumInteractiveComponentSize():
        // Compose's minimum touch target makes the whole 48 dp box it grew tappable.
        val surfaceNode = Bounds(0, 0, 48, 48)
        val surface = readSized(
            surfaceNode, 1f,
            SizedInfo(MinimumInteractiveModifier(), placed(0, 0, 48)),
            SizedInfo(BackgroundElement(red, Any()), placed(4, 4, 40)),
            SizedInfo(ClickableElement(), placed(4, 4, 40)),
        )
        assertEquals(surfaceNode, surface.clickBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(Bounds(4, 4, 40, 40), null, tapTarget = surfaceNode),
            ViewTreeWalker.composeBox(true, surfaceNode, surface.drawBounds, surface.margin, surface.clickBounds),
        )
    }

    @Test
    fun a_click_outside_the_padding_taps_the_whole_box_margin_included() {
        // Modifier.clickable { }.padding(8.dp).background(Red) on a 64 × 64 node: the background
        // is drawn 48 × 48 inside an 8 dp margin, and the click covers the whole 64 dp box.
        val node = Bounds(0, 0, 64, 64)
        val v = clickAndPadding(ClickableElement(), pad(8f))
        assertEquals(MarginInfo(8, 8, 8, 8), v.margin)
        assertEquals(node, v.clickBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(Bounds(8, 8, 48, 48), MarginInfo(8, 8, 8, 8), tapTarget = node),
            ViewTreeWalker.composeBox(true, node, v.drawBounds, v.margin, v.clickBounds),
        )
    }

    @Test
    fun a_click_inside_the_padding_sets_no_tap_target() {
        // Modifier.padding(8.dp).clickable { }.background(Red): the click covers only the 48 dp
        // box inside the padding, which is the drawn box, so there is no larger tap target.
        val node = Bounds(0, 0, 64, 64)
        val v = clickAndPadding(pad(8f), ClickableElement())
        assertEquals(MarginInfo(8, 8, 8, 8), v.margin)
        assertEquals(Bounds(8, 8, 48, 48), v.clickBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(Bounds(8, 8, 48, 48), MarginInfo(8, 8, 8, 8)),
            ViewTreeWalker.composeBox(true, node, v.drawBounds, v.margin, v.clickBounds),
        )
    }

    @Test
    fun a_click_between_two_paddings_taps_the_box_inside_the_outer_one() {
        // Modifier.padding(4.dp).clickable { }.padding(8.dp).background(Red) on a 64 dp node at
        // density 2 (128 px): the background is drawn inside 12 dp of margin, and the click covers
        // the box inside the outer 4 dp, the inner 8 dp ring around the background included.
        val node = Bounds(0, 0, 128, 128)
        val v = readSized(
            node, 2f,
            SizedInfo(pad(4f), placed(0, 0, 128)),
            SizedInfo(ClickableElement(), placed(8, 8, 112)),
            SizedInfo(pad(8f), placed(8, 8, 112)),
            SizedInfo(BackgroundElement(red, Any()), placed(24, 24, 80)),
        )
        assertEquals(MarginInfo(12, 12, 12, 12), v.margin)
        assertEquals(Bounds(8, 8, 112, 112), v.clickBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(Bounds(24, 24, 80, 80), MarginInfo(12, 12, 12, 12), tapTarget = Bounds(8, 8, 112, 112)),
            ViewTreeWalker.composeBox(true, node, v.drawBounds, v.margin, v.clickBounds),
        )
    }

    // Compose's ViewConfiguration, with minimumTouchTargetSize as a DpSize packed as Compose packs it.
    class TouchViewConfiguration(private val widthDp: Float, private val heightDp: Float) {
        fun getMinimumTouchTargetSize(): Long =
            (java.lang.Float.floatToRawIntBits(widthDp).toLong() shl 32) or
                (java.lang.Float.floatToRawIntBits(heightDp).toLong() and 0xFFFFFFFFL)
    }

    class TouchNode(private val configuration: Any, private vararg val infos: SizedInfo) {
        fun getModifierInfo(): List<Any> = infos.toList()
        fun getViewConfiguration(): Any = configuration
    }

    @Test
    fun a_click_smaller_than_the_minimum_touch_target_taps_the_centered_minimum_box() {
        // Modifier.size(24.dp).clickable { }.background(Red) at density 2: the icon is 48 px,
        // and Compose hit-tests it over the 48 dp (96 px) box centered on it.
        val icon = Bounds(24, 24, 48, 48)
        val v = ComposeVisuals.read(
            TouchNode(
                TouchViewConfiguration(48f, 48f),
                SizedInfo(ClickableElement(), placed(24, 24, 48)),
                SizedInfo(BackgroundElement(red, Any()), placed(24, 24, 48)),
            ),
            density = 2f, fontScale = 1f, resources = null, nodeBounds = icon,
        )
        val touch = Bounds(0, 0, 96, 96)
        assertEquals(touch, v.clickBounds)
        assertEquals(
            ViewTreeWalker.ComposeBox(icon, null, tapTarget = touch),
            ViewTreeWalker.composeBox(true, icon, v.drawBounds, v.margin, v.clickBounds),
        )
        // A click at least that large keeps its own box.
        val button = ComposeVisuals.read(
            TouchNode(TouchViewConfiguration(48f, 48f), SizedInfo(ClickableElement(), placed(0, 0, 120))),
            density = 2f, fontScale = 1f, resources = null, nodeBounds = Bounds(0, 0, 120, 120),
        )
        assertEquals(Bounds(0, 0, 120, 120), button.clickBounds)
    }

    @Test
    fun a_small_click_half_outside_its_parent_taps_the_box_centered_on_it_clipped_to_the_parent() {
        // A 24 dp (48 px at density 2) icon laid out at x 76..124 in a 100 px parent: half of it
        // is clipped. The 96 px target is centered on the whole icon (x 52..148, y 2..98), then
        // clipped to the parent.
        val parent = PlacedCoordinates(WindowRect(0f, 0f, 100f, 100f))
        val icon = PlacedCoordinates(WindowRect(76f, 26f, 100f, 74f), unclipped = WindowRect(76f, 26f, 124f, 74f), parent = parent)
        val v = ComposeVisuals.read(
            TouchNode(TouchViewConfiguration(48f, 48f), SizedInfo(ClickableElement(), icon)),
            density = 2f, fontScale = 1f, resources = null, nodeBounds = Bounds(76, 26, 24, 48),
        )
        assertEquals(Bounds(52, 2, 48, 96), v.clickBounds)
    }

    // A BasicText color producer, and one that writes app state when it is asked for its color.
    class ColorProducer(private val color: Long) {
        fun invoke(): Long = color
    }
    class WritingColorProducer(private val state: FakeState<Int>, private val color: Long) {
        fun invoke(): Long {
            state.value = state.value + 1
            return color
        }
    }

    @Test
    fun a_text_color_producer_runs_in_a_read_only_snapshot() {
        fun text(producer: Any) = ComposeVisuals.read(
            Node(TextStringSimpleElement("Hello", ColorTextStyle(unspecified), producer)),
            density = 1f, fontScale = 1f, resources = null, nodeBounds = null,
        )
        assertEquals("#FF0000", text(ColorProducer(red)).textColor)
        // A producer that writes state is refused: no color, and the state is unchanged.
        val state = FakeState(0)
        assertNull(text(WritingColorProducer(state, red)).textColor)
        assertEquals(0, state.value)
        assertNull(Snapshot.current)
    }

    // Compose's RectangleShape, the default background shape.
    object RectangleShape

    @Test
    fun an_inner_opaque_background_over_the_same_box_replaces_the_outer_one() {
        // Modifier.background(Red).background(Blue): blue is drawn over the whole red box.
        val box = placed(0, 0, 40)
        fun fills(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        assertEquals("#0000FF", fills(BackgroundElement(red, RectangleShape), BackgroundElement(blue, RectangleShape)).background)
        // A translucent inner fill lets the outer one through: it stays the background.
        val halfBlue = 0x800000FFL shl 32
        assertEquals("#FF0000", fills(BackgroundElement(red, RectangleShape), BackgroundElement(halfBlue, RectangleShape)).background)
        // A rounded inner fill leaves the outer one showing at its corners.
        val rounded = RoundedCornerShape(DpCornerSize(8f))
        assertEquals("#FF0000", fills(BackgroundElement(red, RectangleShape), BackgroundElement(blue, rounded)).background)
    }

    @Test
    fun an_inner_opaque_background_covers_an_outer_painter() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        // Modifier.paint(bitmap).background(Blue): the blue fill hides the image.
        val covered = chain(PainterElement(BitmapPainter()), BackgroundElement(blue, RectangleShape))
        assertNull(covered.imageType)
        assertEquals("#0000FF", covered.background)
        // Modifier.background(Blue).paint(bitmap): the image is drawn over the blue fill.
        val shown = chain(BackgroundElement(blue, RectangleShape), PainterElement(BitmapPainter()))
        assertEquals("raster", shown.imageType)
        assertEquals("#0000FF", shown.background)
    }

    @Test
    fun an_inner_opaque_gradient_covers_an_outer_painter_and_fill() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        val green = 0xFF00FF00L shl 32
        val opaque = LinearGradient(listOf(StopColor(blue), StopColor(green)))
        // Modifier.paint(bitmap).background(Brush.linearGradient(listOf(Blue, Green))): only the gradient shows.
        val covered = chain(PainterElement(BitmapPainter()), BackgroundElement(unspecified, RectangleShape, opaque))
        assertNull(covered.imageType)
        assertEquals(GradientInfo("linear", listOf("#0000FF", "#00FF00"), null), covered.gradient)
        // It replaces an earlier fill the same way.
        val filled = chain(BackgroundElement(red, RectangleShape), BackgroundElement(unspecified, RectangleShape, opaque))
        assertNull(filled.background)
        assertEquals(listOf("#0000FF", "#00FF00"), filled.gradient!!.colors)
        // A gradient with a translucent stop lets the image through.
        val fade = LinearGradient(listOf(StopColor(blue), StopColor(0x8000FF00L shl 32)))
        assertEquals("raster", chain(PainterElement(BitmapPainter()), BackgroundElement(unspecified, RectangleShape, fade)).imageType)
    }

    @Test
    fun an_inner_opaque_rectangular_background_clears_the_covered_corner_radius() {
        // Modifier.background(Red, RoundedCornerShape(8.dp)).background(Blue): a blue rectangle.
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        val rounded = RoundedCornerShape(DpCornerSize(8f))
        val covered = chain(BackgroundElement(red, rounded), BackgroundElement(blue, RectangleShape))
        assertEquals("#0000FF", covered.background)
        assertNull(covered.cornerRadius)
        // Without the covering fill the rounded red one keeps its corners.
        assertEquals(8f, chain(BackgroundElement(red, rounded)).cornerRadius!!, 0f)
    }

    @Test
    fun an_inner_opaque_background_covers_inside_a_translucent_layer() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        // Modifier.graphicsLayer(alpha = 0.5f).background(Red).background(Blue): blue covers red
        // inside the layer, and the layer fades the blue.
        val layered = chain(GraphicsLayerElement(0.5f), BackgroundElement(red, RectangleShape), BackgroundElement(blue, RectangleShape))
        assertEquals("#800000FF", layered.background)
    }

    @Test
    fun an_inner_opaque_background_covers_a_painter_inside_a_translucent_layer() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        // Modifier.graphicsLayer(alpha = 0.5f).paint(bitmap).background(Blue): no image shows.
        val painted = chain(GraphicsLayerElement(0.5f), PainterElement(BitmapPainter()), BackgroundElement(blue, RectangleShape))
        assertNull(painted.imageType)
        assertEquals("#800000FF", painted.background)
    }

    @Test
    fun a_layer_between_two_fills_keeps_the_outer_one_showing() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        // A layer between the two fades the inner fill over the outer one: red still shows.
        val between = chain(BackgroundElement(red, RectangleShape), GraphicsLayerElement(0.5f), BackgroundElement(blue, RectangleShape))
        assertEquals("#FF0000", between.background)
    }

    @Test
    fun an_inner_opaque_color_painter_covers_the_outer_fill() {
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        // Modifier.background(Red).paint(ColorPainter(Blue)): a blue box.
        assertEquals("#0000FF", chain(BackgroundElement(red, RectangleShape), PainterElement(ColorPainter(blue))).background)
        // It also replaces a covered image and the covered fill's corner radius.
        val image = chain(PainterElement(BitmapPainter()), PainterElement(ColorPainter(blue)))
        assertNull(image.imageType)
        assertEquals("#0000FF", image.background)
        val rounded = chain(BackgroundElement(red, RoundedCornerShape(DpCornerSize(8f))), PainterElement(ColorPainter(blue)))
        assertNull(rounded.cornerRadius)
        // A translucent one lets the outer fill through.
        val halfBlue = 0x800000FFL shl 32
        assertEquals("#FF0000", chain(BackgroundElement(red, RectangleShape), PainterElement(ColorPainter(halfBlue))).background)
    }

    // ColorFilter.lighting(…): a LightingColorFilter, which the tint rule doesn't model.
    class LightingColorFilter

    @Test
    fun a_color_filter_recolors_a_color_painter_fill() {
        // paint(ColorPainter(Red), colorFilter = ColorFilter.tint(Blue)): a blue box.
        assertEquals("#0000FF", read(PainterElement(ColorPainter(red), tint(blue))).background)
        // Src replaces it too.
        assertEquals("#0000FF", read(PainterElement(ColorPainter(red), tint(blue, BlendMode.Src))).background)
        // A filter that can't be decoded (lighting, or a mode that isn't modelled) leaves the
        // color unknown, not the unfiltered red.
        val lit = read(PainterElement(ColorPainter(red), LightingColorFilter()))
        assertNull(lit.background)
        assertTrue(lit.draws)
        assertNull(read(PainterElement(ColorPainter(red), tint(blue, BlendMode.SrcOver))).background)
        // Resolved to an opaque color, it covers the fill under it; an unknown one doesn't.
        val box = placed(0, 0, 40)
        fun chain(vararg elements: Any) = readSized(Bounds(0, 0, 40, 40), 1f, *elements.map { SizedInfo(it, box) }.toTypedArray())
        assertEquals("#0000FF", chain(BackgroundElement(red, RectangleShape), PainterElement(ColorPainter(0xFF00FF00L shl 32), tint(blue))).background)
        assertEquals("#FF0000", chain(BackgroundElement(red, RectangleShape), PainterElement(ColorPainter(blue), LightingColorFilter())).background)
    }
}
