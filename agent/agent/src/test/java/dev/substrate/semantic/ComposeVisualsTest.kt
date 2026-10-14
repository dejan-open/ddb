package dev.substrate.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeVisualsTest {
    private fun pack(x: Float, y: Float): Long =
        (java.lang.Float.floatToRawIntBits(x).toLong() shl 32) or
            (java.lang.Float.floatToRawIntBits(y).toLong() and 0xFFFFFFFFL)

    @Test
    fun unpackOffset_reads_x_high_and_y_low() {
        assertEquals(Pair(12.5f, -3f), ComposeVisuals.unpackOffset(pack(12.5f, -3f)))
        val inf = ComposeVisuals.unpackOffset(pack(0f, Float.POSITIVE_INFINITY))
        assertEquals(Float.POSITIVE_INFINITY, inf.second, 0f)
    }

    @Test
    fun vertical_and_horizontal_gradients_use_view_path_names() {
        // Brush.verticalGradient: Offset(0, 0) -> Offset(0, Infinity)
        assertEquals("top_bottom", ComposeVisuals.gradientOrientation(Pair(0f, 0f), Pair(0f, Float.POSITIVE_INFINITY)))
        // Brush.horizontalGradient: Offset(0, 0) -> Offset(Infinity, 0)
        assertEquals("left_right", ComposeVisuals.gradientOrientation(Pair(0f, 0f), Pair(Float.POSITIVE_INFINITY, 0f)))
        assertEquals("bottom_top", ComposeVisuals.gradientOrientation(Pair(0f, 400f), Pair(0f, 0f)))
        assertEquals("right_left", ComposeVisuals.gradientOrientation(Pair(300f, 0f), Pair(0f, 10f)))
    }

    @Test
    fun diagonal_gradient_and_degenerate_gradient() {
        assertEquals("tl_br", ComposeVisuals.gradientOrientation(Pair(0f, 0f), Pair(100f, 100f)))
        assertEquals("bl_tr", ComposeVisuals.gradientOrientation(Pair(0f, 100f), Pair(100f, 0f)))
        assertNull(ComposeVisuals.gradientOrientation(Pair(5f, 5f), Pair(5f, 5f)))
        assertNull(ComposeVisuals.gradientOrientation(Pair(Float.NaN, 0f), Pair(0f, 0f)))
    }

    @Test
    fun weight_names_match_the_view_path() {
        assertEquals("thin", ComposeVisuals.weightName(100))
        assertEquals("light", ComposeVisuals.weightName(300))
        assertEquals("regular", ComposeVisuals.weightName(400))
        assertEquals("medium", ComposeVisuals.weightName(500))
        assertEquals("semibold", ComposeVisuals.weightName(600))
        assertEquals("bold", ComposeVisuals.weightName(700))
        assertEquals("black", ComposeVisuals.weightName(900))
    }

    @Test
    fun argb_hex_drops_alpha_only_when_opaque() {
        assertEquals("#FF0000", ComposeVisuals.argbToHex(0xFFFF0000.toInt()))
        assertEquals("#80112233", ComposeVisuals.argbToHex(0x80112233.toInt()))
    }

    @Test
    fun transparent_colors_drop_except_as_gradient_stops() {
        assertNull(ComposeVisuals.argbHex(0x00000000, keepTransparent = false))
        assertEquals("#00000000", ComposeVisuals.argbHex(0x00000000, keepTransparent = true))
        assertEquals("#FF0000", ComposeVisuals.argbHex(0xFFFF0000.toInt(), keepTransparent = false))
    }

    @Test
    fun hairline_border_is_one_physical_pixel() {
        assertEquals(1f / 2.625f, ComposeVisuals.borderWidthDp(0f, 2.625f, null)!!, 1e-6f)
        assertEquals(1f / 2.625f, ComposeVisuals.borderWidthDp(0f, 2.625f, Pair(105, 105))!!, 1e-6f)
        assertEquals(2f, ComposeVisuals.borderWidthDp(2f, 3f, null)!!, 0f)
        assertNull(ComposeVisuals.borderWidthDp(-1f, 3f, null))
        assertNull(ComposeVisuals.borderWidthDp(Float.NaN, 3f, null))
        assertNull(ComposeVisuals.borderWidthDp(null, 3f, null))
    }

    @Test
    fun border_width_rounds_up_to_whole_pixels() {
        // border(1.dp) at density 2.625 strokes ceil(2.625) = 3 px.
        assertEquals(3f / 2.625f, ComposeVisuals.borderWidthDp(1f, 2.625f, null)!!, 1e-6f)
        assertEquals(3f / 2.625f, ComposeVisuals.borderWidthDp(1f, 2.625f, Pair(262, 105))!!, 1e-6f)
        // border(0.5.dp) at density 3 is 1.5 px, drawn as 2.
        assertEquals(2f / 3f, ComposeVisuals.borderWidthDp(0.5f, 3f, null)!!, 1e-6f)
        // A width that is already whole pixels is reported as is.
        assertEquals(2f, ComposeVisuals.borderWidthDp(2f, 2f, Pair(200, 80))!!, 0f)
    }

    @Test
    fun border_wider_than_half_the_smaller_side_is_clamped_to_it() {
        // border(100.dp) on a 100x40 dp node at density 2: half of 80 px is 40 px, so 20 dp.
        assertEquals(20f, ComposeVisuals.borderWidthDp(100f, 2f, Pair(200, 80))!!, 0f)
        // Half an odd side rounds up: ceil(41 / 2) = 21 px.
        assertEquals(21f, ComposeVisuals.borderWidthDp(100f, 1f, Pair(41, 100))!!, 0f)
        // No measured size: nothing to clamp against.
        assertEquals(100f, ComposeVisuals.borderWidthDp(100f, 1f, null)!!, 0f)
        // A node with an empty side draws its content only, no border.
        assertNull(ComposeVisuals.borderWidthDp(2f, 2f, Pair(0, 80)))
    }

    @Test
    fun border_width_without_a_density_is_the_declared_width() {
        assertEquals(2f, ComposeVisuals.borderWidthDp(2f, Float.NaN, Pair(10, 10))!!, 0f)
        assertNull(ComposeVisuals.borderWidthDp(0f, 0f, null))
    }

    @Test
    fun text_color_falls_back_to_black_only_when_every_source_is_unspecified() {
        val unspecified = ComposeVisuals.UNSPECIFIED_COLOR
        val red = 0xFFFF0000L shl 32
        val blue = 0xFF0000FFL shl 32
        assertEquals(red, ComposeVisuals.textColorPacked(true, red, blue))
        assertEquals(blue, ComposeVisuals.textColorPacked(true, unspecified, blue))
        assertEquals(blue, ComposeVisuals.textColorPacked(false, null, blue))
        assertEquals(ComposeVisuals.BLACK_PACKED, ComposeVisuals.textColorPacked(false, null, unspecified))
        assertEquals(ComposeVisuals.BLACK_PACKED, ComposeVisuals.textColorPacked(true, unspecified, unspecified))
        assertEquals(0xFF000000L shl 32, ComposeVisuals.BLACK_PACKED)
        // A producer or style that could not be read is not evidence of the default.
        assertNull(ComposeVisuals.textColorPacked(true, null, unspecified))
        assertNull(ComposeVisuals.textColorPacked(false, null, null))
    }

    @Test
    fun brush_and_painter_alpha_scale_the_drawn_alpha() {
        assertEquals(0xFFFF0000.toInt(), ComposeVisuals.scaleAlpha(0xFFFF0000.toInt(), 1f))
        assertEquals(0x80FF0000.toInt(), ComposeVisuals.scaleAlpha(0xFFFF0000.toInt(), 0.5f))
        assertEquals(0x40112233, ComposeVisuals.scaleAlpha(0x80112233.toInt(), 0.5f))
        assertNull(ComposeVisuals.argbHex(ComposeVisuals.scaleAlpha(0xFFFF0000.toInt(), 0f), keepTransparent = false))
    }

    @Test
    fun unspecified_font_size_is_composes_default() {
        assertEquals(14f, ComposeVisuals.fontSizeSp(Float.NaN)!!, 0f)
        assertEquals(17f, ComposeVisuals.fontSizeSp(17f)!!, 0f)
        assertNull(ComposeVisuals.fontSizeSp(null))
        assertNull(ComposeVisuals.fontSizeSp(0f))
        assertNull(ComposeVisuals.fontSizeSp(Float.POSITIVE_INFINITY))
    }

    @Test
    fun all_transparent_gradient_is_invisible_but_a_fade_is_not() {
        assertEquals(false, ComposeVisuals.gradientVisible(listOf("#00000000", "#00FF0000")))
        assertEquals(true, ComposeVisuals.gradientVisible(listOf("#FF0000", "#00000000")))
        assertEquals(true, ComposeVisuals.gradientVisible(listOf("#80FF0000", "#00000000")))
    }

    @Test
    fun border_with_all_transparent_gradient_draws_nothing() {
        assertEquals(false, ComposeVisuals.borderDraws("LinearGradient", null, listOf("#00000000", "#00FF0000")))
        assertEquals(true, ComposeVisuals.borderDraws("LinearGradient", null, listOf("#FF0000", "#00000000")))
        assertEquals(false, ComposeVisuals.borderDraws("SolidColor", null, null))
        assertEquals(true, ComposeVisuals.borderDraws("SolidColor", "#FF0000", null))
        // A shader brush, or a gradient whose stops could not be read, still draws.
        assertEquals(true, ComposeVisuals.borderDraws("ShaderBrush", null, null))
        assertEquals(true, ComposeVisuals.borderDraws(null, null, null))
    }

    @Test
    fun sp_packs_as_a_compose_text_unit() {
        // TextUnitType.Sp is 0x1_0000_0000; the value's float bits sit in the low word.
        assertEquals(0x1_0000_0000L or java.lang.Float.floatToRawIntBits(14f).toLong(), ComposeVisuals.packSp(14f))
        assertEquals(17f, java.lang.Float.intBitsToFloat(ComposeVisuals.packSp(17f).toInt()), 0f)
    }

    @Test
    fun size_packs_like_an_offset() {
        assertEquals(Pair(1080f, 2400f), ComposeVisuals.unpackOffset(ComposeVisuals.packSize(1080f, 2400f)))
        assertEquals(pack(12.5f, 3f), ComposeVisuals.packSize(12.5f, 3f))
    }

    @Test
    fun int_size_unpacks_width_high_and_height_low() {
        // A graphicsLayer block sees the coordinator's measured IntSize, not window bounds.
        assertEquals(Pair(100, 48), ComposeVisuals.unpackIntSize((100L shl 32) or 48L))
        assertEquals(Pair(1080, 2400), ComposeVisuals.unpackIntSize((1080L shl 32) or 2400L))
    }

    @Test
    fun selectable_text_is_read_like_plain_text() {
        // Text in a SelectionContainer has its own element class with the same fields.
        assertTrue("SelectableTextAnnotatedStringElement" in ComposeVisuals.TEXT_ELEMENTS)
        assertTrue("TextStringSimpleElement" in ComposeVisuals.TEXT_ELEMENTS)
        assertTrue("TextAnnotatedStringElement" in ComposeVisuals.TEXT_ELEMENTS)
    }

    @Test
    fun clipped_text_past_max_lines_is_truncated() {
        // TextOverflow.Clip with maxLines: no ellipsis, but the line limit was hit.
        assertEquals(true, ViewTreeWalker.textTruncated(ellipsized = false, exceededMaxLines = true))
        assertEquals(true, ViewTreeWalker.textTruncated(ellipsized = true, exceededMaxLines = true))
        assertEquals(true, ViewTreeWalker.textTruncated(ellipsized = true, exceededMaxLines = null))
        assertEquals(false, ViewTreeWalker.textTruncated(ellipsized = false, exceededMaxLines = false))
        assertEquals(false, ViewTreeWalker.textTruncated(ellipsized = null, exceededMaxLines = false))
        assertNull(ViewTreeWalker.textTruncated(ellipsized = null, exceededMaxLines = null))
    }

    @Test
    fun absolute_padding_swaps_sides_in_rtl() {
        // absolutePadding(left = 8, right = 16): start = 8, end = 16, rtlAware = false.
        assertEquals(Pair(16f, 8f), ComposeVisuals.logicalSides(8f, 16f, rtlAware = false, rtl = true))
        assertEquals(Pair(8f, 16f), ComposeVisuals.logicalSides(8f, 16f, rtlAware = false, rtl = false))
        // padding(start, end) is already logical in either direction.
        assertEquals(Pair(8f, 16f), ComposeVisuals.logicalSides(8f, 16f, rtlAware = true, rtl = true))
        assertEquals(Pair(8f, 16f), ComposeVisuals.logicalSides(8f, 16f, rtlAware = true, rtl = false))
    }

    @Test
    fun default_font_family_is_the_platform_family() {
        // FontFamily.Default renders like no family at all, which reports sans-serif.
        assertEquals("sans-serif", ComposeVisuals.classFamilyName("DefaultFontFamily"))
        assertEquals("customfamily", ComposeVisuals.classFamilyName("CustomFamily"))
    }

    @Test
    fun shadow_with_both_colors_transparent_is_invisible() {
        val black = 0xFF000000.toInt()
        val transparent = 0x00000000
        assertEquals(false, ComposeVisuals.shadowColorsVisible(transparent, transparent))
        assertEquals(false, ComposeVisuals.shadowColorsVisible(0x00FF0000, 0x0000FF00))
        assertTrue(ComposeVisuals.shadowColorsVisible(black, transparent))
        assertTrue(ComposeVisuals.shadowColorsVisible(transparent, 0x01000000))
        // A color that could not be read is assumed to be Compose's default black.
        assertTrue(ComposeVisuals.shadowColorsVisible(null, transparent))
        assertTrue(ComposeVisuals.shadowColorsVisible(null, null))
    }

    @Test
    fun field_reads_private_backing_fields_through_superclasses() {
        open class Base(private val shape: String)
        class Element(private val brush: String) : Base("rounded")
        val e = Element("solid")
        assertEquals("solid", ComposeVisuals.field(e, "brush"))
        assertEquals("rounded", ComposeVisuals.field(e, "shape"))
        assertNull(ComposeVisuals.field(e, "missing"))
        assertNull(ComposeVisuals.field(null, "brush"))
    }

    @Test
    fun every_arbitrary_draw_modifier_is_a_custom_draw_and_counts_as_a_visual() {
        // A Compose Canvas is a Spacer with Modifier.drawBehind: nothing else marks it as drawn.
        assertTrue(ComposeVisuals.isCustomDraw("DrawBehindElement"))
        // drawWithContent { drawRect(...); drawContent() } and drawWithCache { onDrawBehind { } }
        // run arbitrary draw lambdas too.
        assertTrue(ComposeVisuals.isCustomDraw("DrawWithContentElement"))
        assertTrue(ComposeVisuals.isCustomDraw("DrawWithCacheElement"))
        assertFalse(ComposeVisuals.isCustomDraw("PaddingElement"))
        assertFalse(ComposeVisuals.isCustomDraw("GraphicsLayerElement"))
        assertFalse(ComposeDrawInfo().draws)
        assertTrue(ComposeDrawInfo(customDraw = true).draws)
    }

    @Test
    fun percent_corners_resolve_against_the_local_size() {
        // RoundedCornerShape(50) on a 100x40 px box: half the smaller side.
        assertEquals(20f, ComposeVisuals.cornerSizePx("PercentCornerSize", 50f, 2f, Pair(100, 40))!!, 0f)
        // With no size there is nothing to take a percent of.
        assertNull(ComposeVisuals.cornerSizePx("PercentCornerSize", 50f, 2f, null))
        assertEquals(16f, ComposeVisuals.cornerSizePx("DpCornerSize", 8f, 2f, null)!!, 0f)
        assertEquals(5f, ComposeVisuals.cornerSizePx("PxCornerSize", 5f, 2f, Pair(100, 40))!!, 0f)
        assertNull(ComposeVisuals.cornerSizePx("SomeCustomCornerSize", 5f, 2f, Pair(100, 40)))
        assertNull(ComposeVisuals.cornerSizePx("DpCornerSize", Float.NaN, 2f, null))
        assertNull(ComposeVisuals.cornerSizePx("DpCornerSize", null, 2f, null))
    }

    @Test
    fun corners_too_large_for_the_shape_are_scaled_down_as_compose_draws_them() {
        // RoundedCornerShape(100.dp) at density 2 on a 96x48 px button: 200 + 200 px > 48 px,
        // so both start corners scale down to 24 px.
        assertEquals(24f, ComposeVisuals.drawnCornerPx(200f, 200f, Pair(96, 48)), 1e-4f)
        // Corners that fit, and a 50% pill whose corners add up to exactly the smaller side.
        assertEquals(16f, ComposeVisuals.drawnCornerPx(16f, 16f, Pair(96, 48)), 0f)
        assertEquals(24f, ComposeVisuals.drawnCornerPx(24f, 24f, Pair(96, 48)), 0f)
        // No size: nothing to scale against. An unknown bottom corner counts as 0.
        assertEquals(200f, ComposeVisuals.drawnCornerPx(200f, 200f, null), 0f)
        assertEquals(48f, ComposeVisuals.drawnCornerPx(100f, null, Pair(96, 48)), 1e-4f)
    }

    @Test
    fun only_rounded_shapes_have_a_corner_radius() {
        assertTrue("RoundedCornerShape" in ComposeVisuals.ROUNDED_SHAPES)
        assertTrue("AbsoluteRoundedCornerShape" in ComposeVisuals.ROUNDED_SHAPES)
        // Cut corners are bevels of the same size, not arcs.
        assertFalse("CutCornerShape" in ComposeVisuals.ROUNDED_SHAPES)
        assertFalse("AbsoluteCutCornerShape" in ComposeVisuals.ROUNDED_SHAPES)
        assertFalse("RectangleShape" in ComposeVisuals.ROUNDED_SHAPES)
    }

    private fun em(value: Float): Long =
        ComposeVisuals.TEXT_UNIT_EM or (java.lang.Float.floatToRawIntBits(value).toLong() and 0xFFFFFFFFL)

    private val unspecifiedTextUnit = java.lang.Float.floatToRawIntBits(Float.NaN).toLong() and 0xFFFFFFFFL

    @Test
    fun style_font_size_reads_the_text_unit_type() {
        assertEquals(20f, ComposeVisuals.styleFontSizeSp(ComposeVisuals.packSp(20f))!!, 0f)
        assertEquals(14f, ComposeVisuals.styleFontSizeSp(unspecifiedTextUnit)!!, 0f)
        // An em size at the style level scales the platform paint's size: no size to name.
        assertNull(ComposeVisuals.styleFontSizeSp(em(1.5f)))
        assertNull(ComposeVisuals.styleFontSizeSp(null))
        assertEquals(1.5f, ComposeVisuals.textUnitValue(em(1.5f)), 0f)
    }

    private fun <T> span(start: Int, end: Int, value: T) = ComposeVisuals.SpanEffect<T>(start, end) { value }

    @Test
    fun a_full_range_span_sets_an_attribute_and_a_partial_one_mixes_it() {
        val uniform = { v: String -> ComposeVisuals.OverText.Uniform(v) }
        val mixed = ComposeVisuals.OverText.Mixed
        assertEquals(uniform("black"), ComposeVisuals.overWholeText("black", 5, emptyList()))
        assertEquals(uniform("red"), ComposeVisuals.overWholeText("black", 5, listOf(span(0, 5, "red"))))
        assertEquals(mixed, ComposeVisuals.overWholeText("black", 5, listOf(span(0, 2, "red"))))
        // A partial span that sets the value already there changes nothing.
        assertEquals(uniform("black"), ComposeVisuals.overWholeText("black", 5, listOf(span(0, 2, "black"))))
        // A later span wins where two overlap.
        assertEquals(uniform("blue"), ComposeVisuals.overWholeText("black", 5, listOf(span(0, 2, "red"), span(0, 5, "blue"))))
        assertEquals(mixed, ComposeVisuals.overWholeText("black", 5, listOf(span(0, 5, "blue"), span(3, 5, "red"))))
        // Two spans that together cover the text with one value.
        assertEquals(uniform("red"), ComposeVisuals.overWholeText("black", 5, listOf(span(0, 2, "red"), span(2, 5, "red"))))
        // Spans Compose skips: empty, starting before the text, or ending past it.
        assertEquals(
            uniform("black"),
            ComposeVisuals.overWholeText("black", 5, listOf(span(2, 2, "red"), span(-1, 5, "red"), span(0, 6, "red"))),
        )
        // A span that leaves the attribute alone, and an empty text.
        assertEquals(uniform("black"), ComposeVisuals.overWholeText("black", 5, listOf(ComposeVisuals.SpanEffect<String>(0, 2, null))))
        assertEquals(uniform("black"), ComposeVisuals.overWholeText("black", 0, listOf(span(0, 0, "red"))))
        assertEquals("red", ComposeVisuals.OverText.Uniform("red").valueOrNull())
        assertNull(mixed.valueOrNull())
    }

    @Test
    fun span_font_sizes_replace_in_sp_and_scale_in_em() {
        // The sp-to-dp conversion is the node's (nonlinear at large font scales): applied to sp only.
        val toDp: (Float) -> Float = { it * 2f }
        val sp20 = ComposeVisuals.fontSizeEffect(ComposeVisuals.packSp(20f), toDp)!!
        val em15 = ComposeVisuals.fontSizeEffect(em(1.5f), toDp)!!
        assertEquals(40f, sp20(28f)!!, 0f)
        assertEquals(42f, em15(28f)!!, 0f)
        assertNull(em15(null))
        assertNull(ComposeVisuals.fontSizeEffect(unspecifiedTextUnit, toDp))
        assertNull(ComposeVisuals.fontSizeEffect(null, toDp))
        assertNull(ComposeVisuals.fontSizeEffect(ComposeVisuals.packSp(Float.NaN), toDp))
        fun over(vararg spans: ComposeVisuals.SpanEffect<Float?>) = ComposeVisuals.overWholeText<Float?>(14f, 4, spans.toList())
        assertEquals(ComposeVisuals.OverText.Uniform(21f), over(ComposeVisuals.SpanEffect(0, 4, em15)))
        // Two em spans over the same text compound; an em span scales an sp span under it.
        assertEquals(ComposeVisuals.OverText.Uniform(31.5f), over(ComposeVisuals.SpanEffect(0, 4, em15), ComposeVisuals.SpanEffect(0, 4, em15)))
        assertEquals(ComposeVisuals.OverText.Uniform(60f), over(ComposeVisuals.SpanEffect(0, 4, sp20), ComposeVisuals.SpanEffect(0, 4, em15)))
        assertEquals(ComposeVisuals.OverText.Mixed, over(ComposeVisuals.SpanEffect(0, 2, sp20)))
    }

    @Test
    fun span_colors_draw_over_a_solid_foreground_and_span_brushes_over_anything() {
        val red = 0xFFFF0000L shl 32
        val black = ComposeVisuals.TextForeground.Solid(ComposeVisuals.BLACK_PACKED)
        val brush = Any()
        val redSpan = ComposeVisuals.foregroundEffect(red, null, 1f)!!
        assertEquals(ComposeVisuals.TextForeground.Solid(red), redSpan(black))
        assertEquals(ComposeVisuals.TextForeground.Solid(red), redSpan(null))
        // Text drawn with a brush keeps it under a color span: the shader draws over the paint color.
        val shader = ComposeVisuals.TextForeground.Shader(brush, 1f)
        assertEquals(shader, redSpan(shader))
        // A brush span replaces the color under it.
        assertEquals(ComposeVisuals.TextForeground.Shader(brush, 0.5f), ComposeVisuals.foregroundEffect(null, brush, 0.5f)!!(black))
        assertNull(ComposeVisuals.foregroundEffect(ComposeVisuals.UNSPECIFIED_COLOR, null, 1f))
        assertNull(ComposeVisuals.foregroundEffect(null, null, 1f))
        // Over a whole text: SpanStyle(color = Red) on all of it is red text; on part of it, no one color.
        fun over(vararg spans: ComposeVisuals.SpanEffect<ComposeVisuals.TextForeground?>) =
            ComposeVisuals.overWholeText<ComposeVisuals.TextForeground?>(black, 3, spans.toList())
        assertEquals(ComposeVisuals.OverText.Uniform(ComposeVisuals.TextForeground.Solid(red)), over(ComposeVisuals.SpanEffect(0, 3, redSpan)))
        assertEquals(ComposeVisuals.OverText.Mixed, over(ComposeVisuals.SpanEffect(0, 1, redSpan)))
    }

    // Brushes with Compose's class names and backing field.
    class SolidColor(private val value: Long)
    class ShaderBrush
    class LinearGradient

    @Test
    fun a_solid_text_brush_is_a_color_and_only_a_shader_brush_is_a_shader() {
        val red = 0xFFFF0000L shl 32
        // AndroidTextPaint.setBrush draws a SolidColor as the paint color, at the brush alpha.
        assertEquals(ComposeVisuals.TextForeground.Solid(red, 0.5f), ComposeVisuals.brushForeground(SolidColor(red), 0.5f))
        for (shader in listOf(ShaderBrush(), LinearGradient())) {
            assertEquals(ComposeVisuals.TextForeground.Shader(shader, 0.5f), ComposeVisuals.brushForeground(shader, 0.5f))
        }
        // A solid brush with no color to read is a color, but an unknown one.
        assertNull(ComposeVisuals.brushForeground(SolidColor(ComposeVisuals.UNSPECIFIED_COLOR), 1f))
    }

    @Test
    fun a_solid_span_brush_is_a_color_span() {
        val red = 0xFFFF0000L shl 32
        val blue = 0xFF0000FFL shl 32
        val shader = ComposeVisuals.TextForeground.Shader(ShaderBrush(), 1f)
        // A text drawn in a solid red brush (at half alpha) takes a solid blue span brush's own color.
        val blueBrushSpan = ComposeVisuals.foregroundEffect(null, SolidColor(blue), 0.5f)!!
        assertEquals(ComposeVisuals.TextForeground.Solid(blue), blueBrushSpan(ComposeVisuals.TextForeground.Solid(red, 0.5f)))
        assertEquals(ComposeVisuals.TextForeground.Solid(blue), blueBrushSpan(null))
        // Like a color span, it leaves a shader under it drawing; a shader span replaces anything.
        assertEquals(shader, blueBrushSpan(shader))
        val gradient = LinearGradient()
        val gradientSpan = ComposeVisuals.foregroundEffect(null, gradient, 0.5f)!!
        assertEquals(ComposeVisuals.TextForeground.Shader(gradient, 0.5f), gradientSpan(ComposeVisuals.TextForeground.Solid(red)))
        // A solid span brush whose color can't be read leaves the color unknown, a shader under it drawing.
        val unread = ComposeVisuals.foregroundEffect(null, SolidColor(ComposeVisuals.UNSPECIFIED_COLOR), 1f)!!
        assertNull(unread(ComposeVisuals.TextForeground.Solid(red)))
        assertEquals(shader, unread(shader))
    }

    @Test
    fun image_role_does_not_set_an_image_format() {
        // Role.Image with no recognised painter (a Canvas, custom drawing): an image, format unknown.
        assertEquals(ComposeVisuals.ImageFacts(isImage = true, imageType = null), ComposeVisuals.imageFacts(null, "Image"))
        // Only the painter tells the format.
        assertEquals(ComposeVisuals.ImageFacts(true, "raster"), ComposeVisuals.imageFacts("raster", "Image"))
        assertEquals(ComposeVisuals.ImageFacts(true, "vector"), ComposeVisuals.imageFacts("vector", null))
        assertEquals(ComposeVisuals.ImageFacts(false, null), ComposeVisuals.imageFacts(null, "Button"))
        assertEquals(ComposeVisuals.ImageFacts(false, null), ComposeVisuals.imageFacts(null, null))
    }
}
