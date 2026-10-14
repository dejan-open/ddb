package dev.substrate.semantic

import dev.substrate.semantic.ViewTreeWalker.ComposeBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What counts as a drawn element: the ghost touch-target filter and the image block. */
class DrawnElementTest {

    private fun element(
        id: String,
        bounds: Bounds,
        type: String = "view",
        content: String? = null,
        clickable: Boolean = false,
        background: String? = null,
        elevation: Float? = null,
        imageResource: String? = null,
        imageType: String? = null,
        imagePath: String? = null,
        border: BorderInfo? = null,
        gradient: GradientInfo? = null,
        customDraw: Boolean = false,
    ) = SemanticElement(
        id = id, platformId = null, type = type, content = content, font = null, color = null,
        bounds = bounds, zIndex = 0, clickable = clickable, enabled = true, accessible = true,
        a11yLabel = null, a11yId = null, background = background, cornerRadius = null,
        padding = null, margin = null, elevation = elevation, render = null,
        imageResource = imageResource, imageType = imageType, imagePath = imagePath,
        border = border, gradient = gradient, customDraw = customDraw,
    )

    private val parent = Bounds(0, 0, 200, 60)
    private val label = Bounds(20, 20, 100, 20)

    /** The ids left after the ghost filter, for a clickable unlabelled parent around a text child. */
    private fun survivors(parentElement: SemanticElement): List<String> {
        val list = mutableListOf(parentElement, element("label", label, type = "text", content = "Continue"))
        ViewTreeWalker.removeGhostTouchTargets(list, StringBuilder())
        return list.map { it.id }
    }

    @Test
    fun an_unlabelled_clickable_parent_that_draws_nothing_is_a_ghost() {
        assertEquals(listOf("label"), survivors(element("p", parent, type = "button", clickable = true)))
        // A shadow at zero elevation draws nothing either.
        assertEquals(listOf("label"), survivors(element("p", parent, type = "button", clickable = true, elevation = 0f)))
    }

    @Test
    fun an_unlabelled_clickable_parent_that_draws_anything_is_kept() {
        val drawn = listOf(
            element("custom", parent, type = "button", clickable = true, customDraw = true),
            element("bg", parent, type = "button", clickable = true, background = "#FF0000FF"),
            element("border", parent, type = "button", clickable = true, border = BorderInfo(1f, "#FF000000")),
            element("gradient", parent, type = "button", clickable = true, gradient = GradientInfo("linear", listOf("#FF000000", "#FFFFFFFF"), null)),
            element("shadow", parent, type = "button", clickable = true, elevation = 2f),
            // Role.Image with no recognised painter: an image whose format is unknown.
            element("role_image", parent, type = "view", clickable = true, imageResource = "role_image"),
            element("vector", parent, type = "button", clickable = true, imageResource = "v", imageType = "vector"),
            element("path_only", parent, type = "view", clickable = true, imagePath = "images/x.png"),
        )
        for (e in drawn) {
            assertTrue(e.id, e.drawsSomething)
            assertEquals(e.id, listOf(e.id, "label"), survivors(e))
        }
    }

    @Test
    fun drawsSomething_is_false_for_a_bare_element() {
        assertFalse(element("bare", parent).drawsSomething)
    }

    @Test
    fun a_view_gradient_whose_every_stop_is_transparent_is_no_gradient() {
        // A GradientDrawable fading between two transparent colors draws nothing: gradientInfo
        // reports no gradient, so none is serialized and it doesn't count toward drawsSomething.
        assertEquals(null, ViewTreeWalker.gradientStops(intArrayOf(0x00FF0000, 0x000000FF)))
        // A transparent stop among visible ones still shapes the fade and is kept.
        assertEquals(listOf("#00FF0000", "#0000FF"), ViewTreeWalker.gradientStops(intArrayOf(0x00FF0000, 0xFF0000FF.toInt())))
        // Fewer than two stops, or none read, is no gradient.
        assertEquals(null, ViewTreeWalker.gradientStops(intArrayOf(0xFF0000FF.toInt())))
        assertEquals(null, ViewTreeWalker.gradientStops(null))
    }

    @Test
    fun a_view_stroke_with_zero_alpha_is_no_border() {
        // GradientDrawable strokes only when the stroke paint's alpha is above 0, so a transparent
        // stroke color is no border at any width: none is serialized, and it doesn't count toward
        // drawsSomething.
        assertEquals(null, ViewTreeWalker.strokeBorder(6f, 0x00FF0000, density = 2f))
        assertEquals(null, ViewTreeWalker.strokeBorder(6f, 0, density = 2f))
        // setStroke with no color list strokes Color.TRANSPARENT.
        assertEquals(null, ViewTreeWalker.strokeBorder(6f, null, density = 2f))
        // No width, no border.
        assertEquals(null, ViewTreeWalker.strokeBorder(0f, 0xFF000000.toInt(), density = 2f))
        // A visible stroke, however faint, is a border, its width in dp.
        assertEquals(BorderInfo(3f, "#FF0000"), ViewTreeWalker.strokeBorder(6f, 0xFFFF0000.toInt(), density = 2f))
        assertEquals(BorderInfo(1f, "#01000000"), ViewTreeWalker.strokeBorder(2f, 0x01000000, density = 2f))
        // A clickable wrapper whose only draw is a transparent stroke is still a ghost.
        val transparentStroke = ViewTreeWalker.strokeBorder(6f, 0x00FF0000, density = 2f)
        assertEquals(listOf("label"), survivors(element("p", parent, type = "button", clickable = true, border = transparentStroke)))
    }

    @Test
    fun a_compose_margin_goes_with_the_bounds_inside_it() {
        val node = Bounds(0, 0, 100, 60)
        val drawn = Bounds(16, 16, 68, 28)
        val margin = MarginInfo(8, 8, 8, 8)
        // padding(8).clickable { }.background(…): the background's box, with the 8 dp outside it
        // as the margin, not the whole node and the margin on top of it. The padding may sit
        // outside the click, so it sets no tap target.
        assertEquals(ComposeBox(drawn, margin), ViewTreeWalker.composeBox(touchable = true, bounds = node, drawBounds = drawn, margin = margin))
        // The same for a node that only draws.
        assertEquals(ComposeBox(drawn, margin), ViewTreeWalker.composeBox(touchable = false, bounds = node, drawBounds = drawn, margin = margin))
        // Draw bounds that can't be read: the whole node, and no margin counted on top of it.
        assertEquals(ComposeBox(node, null), ViewTreeWalker.composeBox(touchable = true, bounds = node, drawBounds = null, margin = margin))
        // No margin, as for a layout modifier that grows the box around the draw: the element is
        // what is drawn, and a node that takes touches itself keeps its whole box as the tap target.
        assertEquals(ComposeBox(drawn, null, tapTarget = node), ViewTreeWalker.composeBox(touchable = true, bounds = node, drawBounds = drawn, margin = null))
        assertEquals(ComposeBox(drawn, null), ViewTreeWalker.composeBox(touchable = false, bounds = node, drawBounds = drawn, margin = null))
        assertEquals(ComposeBox(node, null), ViewTreeWalker.composeBox(touchable = false, bounds = node, drawBounds = null, margin = null))
    }

    @Test
    fun image_block_writes_type_only_when_the_format_is_known() {
        assertEquals(emptyList<String>(), SemanticServer.imageYaml(null, null))
        assertEquals(emptyList<String>(), SemanticServer.imageYaml(null, "raster"))
        // Role.Image alone: an image, and no format is made up for it.
        assertEquals(listOf("  image:", "    resource: \"canvas_1\""), SemanticServer.imageYaml("canvas_1", null))
        assertEquals(
            listOf("  image:", "    resource: \"logo\"", "    type: vector"),
            SemanticServer.imageYaml("logo", "vector"),
        )
        assertEquals(
            listOf("  image:", "    resource: \"photo\"", "    type: raster"),
            SemanticServer.imageYaml("photo", "raster"),
        )
    }

    @Test
    fun a_layout_walk_that_emits_nothing_falls_back() {
        fun walk(layout: (MutableList<SemanticElement>) -> Unit, semantics: Boolean): Pair<Boolean, List<String>> {
            val out = mutableListOf<SemanticElement>()
            val ok = ViewTreeWalker.composeWalk(
                layoutWalk = { layout(out) },
                semanticsWalk = if (semantics) ({ out.add(element("semantics", parent)) }) else null,
                out = out,
                log = StringBuilder(),
            )
            return ok to out.map { it.id }
        }
        val emits: (MutableList<SemanticElement>) -> Unit = { it.add(element("layout", parent)) }
        assertEquals(true to listOf("layout"), walk(emits, semantics = true))
        // A layout walk that returns without emitting (a child accessor failed softly) runs the
        // semantics walk, and with no semantics root the caller walks the View tree.
        assertEquals(true to listOf("semantics"), walk({}, semantics = true))
        assertEquals(false to emptyList<String>(), walk({}, semantics = false))
    }
}
