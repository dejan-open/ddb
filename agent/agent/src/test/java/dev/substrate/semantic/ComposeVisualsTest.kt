package dev.substrate.semantic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(1f / 2.625f, ComposeVisuals.borderWidthDp(0f, 2.625f)!!, 1e-6f)
        assertEquals(2f, ComposeVisuals.borderWidthDp(2f, 3f)!!, 0f)
        assertNull(ComposeVisuals.borderWidthDp(-1f, 3f))
        assertNull(ComposeVisuals.borderWidthDp(Float.NaN, 3f))
        assertNull(ComposeVisuals.borderWidthDp(null, 3f))
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
    fun field_reads_private_backing_fields_through_superclasses() {
        open class Base(private val shape: String)
        class Element(private val brush: String) : Base("rounded")
        val e = Element("solid")
        assertEquals("solid", ComposeVisuals.field(e, "brush"))
        assertEquals("rounded", ComposeVisuals.field(e, "shape"))
        assertNull(ComposeVisuals.field(e, "missing"))
        assertNull(ComposeVisuals.field(null, "brush"))
    }
}
