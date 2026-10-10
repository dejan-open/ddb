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
