package dev.substrate.semantic

import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The View path over real framework drawables (Robolectric runs AOSP's GradientDrawable and
 * ImageView): what a GradientDrawable draws lives on its GradientState, and a color list is
 * drawn at the drawable's current state, not as its default color.
 */
@RunWith(RobolectricTestRunner::class)
class ViewDrawableTest {

    private val pressed = intArrayOf(android.R.attr.state_pressed)

    /** Red when pressed, blue otherwise: its default color is blue. */
    private fun pressedRed() = ColorStateList(arrayOf(pressed, intArrayOf()), intArrayOf(Color.RED, Color.BLUE))

    private val context get() = RuntimeEnvironment.getApplication()

    /** A View [w] × [h] px with [background], laid out but not yet drawn (its background has no bounds). */
    private fun sized(background: Drawable, w: Int = 200, h: Int = 48) = View(context).apply {
        this.background = background
        layout(0, 0, w, h)
    }

    private fun background(view: View, density: Float = 1f) = ViewTreeWalker.viewBackground(view, density)

    @Test
    fun a_gradient_stroke_is_read_at_the_drawable_state() {
        val drawable = GradientDrawable().apply { setStroke(6, pressedRed()) }
        // Unpressed: the default entry, 6 px at density 2.
        assertEquals(BorderInfo(3f, "#0000FF"), ViewTreeWalker.gradientBorder(drawable, density = 2f))
        assertEquals(BorderInfo(3f, "#0000FF"), ViewTreeWalker.stateStroke(drawable.constantState, drawable.state, density = 2f))
        // Pressed: the pressed entry, which the list's default color never is.
        drawable.state = pressed
        assertEquals(BorderInfo(3f, "#FF0000"), ViewTreeWalker.gradientBorder(drawable, density = 2f))
        assertEquals(BorderInfo(3f, "#FF0000"), ViewTreeWalker.stateStroke(drawable.constantState, drawable.state, density = 2f))
    }

    @Test
    fun a_gradient_with_no_stroke_has_no_border() {
        val drawable = GradientDrawable().apply { setColor(Color.RED) }
        assertNull(ViewTreeWalker.gradientBorder(drawable, density = 2f))
        assertNull(ViewTreeWalker.stateStroke(drawable.constantState, drawable.state, density = 2f))
        // A transparent stroke draws nothing either.
        assertNull(ViewTreeWalker.gradientBorder(GradientDrawable().apply { setStroke(6, Color.TRANSPARENT) }, density = 2f))
    }

    @Test
    fun a_gradient_fill_is_read_at_the_drawable_state() {
        val drawable = GradientDrawable().apply { color = pressedRed() }
        assertEquals("#0000FF", ViewTreeWalker.gradientFill(drawable))
        drawable.state = pressed
        assertEquals("#FF0000", ViewTreeWalker.gradientFill(drawable))
        // A plain color, and a gradient, which has no one fill color.
        assertEquals("#00FF00", ViewTreeWalker.gradientFill(GradientDrawable().apply { setColor(Color.GREEN) }))
        val gradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.RED, Color.BLUE))
        assertNull(ViewTreeWalker.gradientFill(gradient))
    }

    @Test
    fun a_fully_transparent_fill_is_no_fill() {
        // A shape filled with Color.TRANSPARENT draws nothing: no background, and nothing for
        // drawsSomething to count.
        assertNull(ViewTreeWalker.gradientFill(GradientDrawable().apply { setColor(Color.TRANSPARENT) }))
        assertNull(background(sized(GradientDrawable().apply { setColor(Color.TRANSPARENT) }))!!.fill)
        // A list whose entry for the current state is transparent too.
        val clearUnlessPressed = GradientDrawable().apply {
            color = ColorStateList(arrayOf(pressed, intArrayOf()), intArrayOf(Color.RED, Color.TRANSPARENT))
        }
        assertNull(ViewTreeWalker.gradientFill(clearUnlessPressed))
        // Partly transparent is still drawn.
        assertEquals("#80FF0000", ViewTreeWalker.gradientFill(GradientDrawable().apply { setColor(0x80FF0000.toInt()) }))
    }

    @Test
    fun a_transparent_color_background_draws_nothing() {
        // android:background="@android:color/transparent": ColorDrawable.draw skips a zero-alpha color.
        assertNull(background(sized(ColorDrawable(Color.TRANSPARENT)))!!.fill)
        assertEquals("#80FF0000", background(sized(ColorDrawable(0x80FF0000.toInt())))!!.fill)
        assertEquals("#FF0000", background(sized(ColorDrawable(Color.RED)))!!.fill)
    }

    @Test
    fun a_gradient_drawable_alpha_scales_what_it_draws() {
        // toolbar.background.alpha = 0: GradientDrawable modulates its fill and stroke by its own
        // alpha, so nothing is drawn, though getColor() and the stroke paint keep full opacity.
        val shape = GradientDrawable().apply {
            setColor(Color.RED)
            setStroke(4, Color.BLUE)
            alpha = 0
        }
        val hidden = background(sized(shape), density = 2f)!!
        assertNull(hidden.fill)
        assertNull(hidden.border)
        val gradient = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.RED, Color.BLUE)).apply { alpha = 0 }
        assertNull(background(sized(gradient))!!.gradient)
        // At half alpha, each is drawn at half its own (modulateAlpha: 255 × 129 >> 8 = 128).
        shape.alpha = 128
        val half = background(sized(shape), density = 2f)!!
        assertEquals("#80FF0000", half.fill)
        assertEquals(BorderInfo(2f, "#800000FF"), half.border)
        gradient.alpha = 128
        assertEquals(listOf("#80FF0000", "#800000FF"), background(sized(gradient))!!.gradient!!.colors)
    }

    @Test
    fun a_ripple_mask_is_not_drawn() {
        val highlight = ColorStateList.valueOf(Color.GRAY)
        // A mask-only ripple draws nothing at rest: RippleDrawable draws every layer but its mask.
        val colorMask = RippleDrawable(highlight, null, ColorDrawable(Color.WHITE))
        assertNull(ViewTreeWalker.drawnLayer(colorMask))
        assertNull(background(sized(colorMask)))
        // The rounded-ripple mask: a white rounded shape, not a white rounded fill.
        val shapeMask = RippleDrawable(highlight, null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 8f })
        assertNull(background(sized(shapeMask)))
        // ?attr/selectableItemBackground: the framework's item_background_material, a ripple
        // whose only layer is a white mask.
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val selectable = attrs.getDrawable(0)
        attrs.recycle()
        assertTrue(selectable is RippleDrawable)
        assertNull(background(sized(selectable!!)))
        // A ripple over a content shape draws the shape: its fill, its radius and its stroke,
        // all from the same layer.
        val content = GradientDrawable().apply {
            setColor(Color.RED)
            cornerRadius = 8f
            setStroke(2, Color.BLUE)
        }
        val drawn = background(sized(RippleDrawable(highlight, content, ColorDrawable(Color.WHITE))), density = 2f)!!
        assertEquals("#FF0000", drawn.fill)
        assertEquals(8f, drawn.cornerRadiusPx!!, 0f)
        assertEquals(BorderInfo(1f, "#0000FF"), drawn.border)
        // The content layer first or second, the mask is never the one read.
        val maskFirst = RippleDrawable(highlight, null, ColorDrawable(Color.WHITE)).apply {
            addLayer(GradientDrawable().apply { setColor(Color.GREEN) })
        }
        assertEquals("#00FF00", background(sized(maskFirst))!!.fill)
    }

    @Test
    fun a_background_tint_recolors_what_the_background_draws() {
        val blue = ColorStateList.valueOf(Color.BLUE)
        // android:backgroundTint="@color/blue" over a red rounded shape with a green stroke: the
        // default SRC_IN tint draws the fill and the stroke in blue.
        val shape = GradientDrawable().apply {
            setColor(Color.RED)
            setStroke(4, Color.GREEN)
            cornerRadius = 8f
        }
        val tinted = background(sized(shape).apply { backgroundTintList = blue }, density = 2f)!!
        assertEquals("#0000FF", tinted.fill)
        assertEquals(BorderInfo(2f, "#0000FF"), tinted.border)
        // SRC_IN keeps the shape's own alpha.
        val halfRed = GradientDrawable().apply { setColor(0x80FF0000.toInt()) }
        assertEquals("#800000FF", background(sized(halfRed).apply { backgroundTintList = blue })!!.fill)
        // A stroke-only shape is filled with the tint (GradientDrawable fills with its alpha
        // under the tint's filter when it has no fill color), and has no fill untinted.
        assertNull(background(sized(GradientDrawable().apply { setStroke(4, Color.GREEN) }))!!.fill)
        val outline = GradientDrawable().apply { setStroke(4, Color.GREEN) }
        assertEquals("#0000FF", background(sized(outline).apply { backgroundTintList = blue })!!.fill)
        // A transparent tint hides the shape.
        val clear = background(
            sized(GradientDrawable().apply { setColor(Color.RED); setStroke(4, Color.GREEN) }).apply {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            },
        )!!
        assertNull(clear.fill)
        assertNull(clear.border)
        // A ColorDrawable is tinted the same way.
        assertEquals("#0000FF", background(sized(ColorDrawable(Color.RED)).apply { backgroundTintList = blue })!!.fill)
        // The tint is resolved at the drawable's state.
        val pressedTint = sized(GradientDrawable().apply { setColor(Color.GREEN) }).apply {
            backgroundTintList = pressedRed()
        }
        assertEquals("#0000FF", background(pressedTint)!!.fill)
        pressedTint.isPressed = true
        assertEquals("#FF0000", background(pressedTint)!!.fill)
        // SRC_ATOP keeps the shape's alpha and blends the tint over its color.
        val atop = sized(GradientDrawable().apply { setColor(0x80FF0000.toInt()) }).apply {
            backgroundTintList = blue
            backgroundTintBlendMode = BlendMode.SRC_ATOP
        }
        assertEquals("#800000FF", background(atop)!!.fill)
        // A color filter set on the drawable is what it draws with, not the tint: red over red
        // stays red.
        val filtered = GradientDrawable().apply {
            setColor(Color.RED)
            colorFilter = PorterDuffColorFilter(Color.RED, PorterDuff.Mode.SRC_IN)
        }
        assertEquals("#FF0000", background(sized(filtered).apply { backgroundTintList = blue })!!.fill)
    }

    @Test
    fun a_gradient_corner_radius_is_read_from_its_state() {
        assertNull(ViewTreeWalker.gradientCornerRadius(GradientDrawable()))
        assertEquals(12f, ViewTreeWalker.gradientCornerRadius(GradientDrawable().apply { cornerRadius = 12f })!!, 0f)
        // Per-corner radii: the top-left one.
        val radii = floatArrayOf(6f, 6f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(6f, ViewTreeWalker.gradientCornerRadius(GradientDrawable().apply { cornerRadii = radii })!!, 0f)
        // A square top-left corner is no radius, whatever the others are.
        val bottomOnly = floatArrayOf(0f, 0f, 0f, 0f, 8f, 8f, 8f, 8f)
        assertNull(ViewTreeWalker.gradientCornerRadius(GradientDrawable().apply { cornerRadii = bottomOnly }))
    }

    @Test
    fun a_gradient_corner_radius_is_clamped_to_the_rect_it_draws() {
        // A 48 px tall pill with <corners android:radius="100dp"/>: GradientDrawable draws half the
        // height, 24, not 100.
        val pill = GradientDrawable().apply { cornerRadius = 100f }
        assertEquals(24f, background(sized(pill, 200, 48))!!.cornerRadiusPx!!, 0f)
        // Its own bounds, once set, are what it draws in, inset by half the stroke on each side.
        pill.setBounds(0, 0, 100, 40)
        assertEquals(20f, ViewTreeWalker.gradientCornerRadius(pill)!!, 0f)
        pill.setStroke(4, Color.BLACK)
        assertEquals(18f, ViewTreeWalker.gradientCornerRadius(pill)!!, 0f)
        // A radius that fits is kept.
        assertEquals(8f, background(sized(GradientDrawable().apply { cornerRadius = 8f }, 200, 48))!!.cornerRadiusPx!!, 0f)
        // Per-corner radii that overrun a side are all scaled by one factor (SkRRect): here the left
        // side's 30 + 30 in 40 px, so every radius is two thirds of its own.
        val overrun = GradientDrawable().apply {
            cornerRadii = floatArrayOf(30f, 30f, 30f, 30f, 0f, 0f, 30f, 30f)
            setBounds(0, 0, 100, 40)
        }
        assertEquals(20f, ViewTreeWalker.gradientCornerRadius(overrun)!!, 1e-4f)
        // Radii that fit every side are kept as declared.
        assertEquals(30f, ViewTreeWalker.radiiTopLeft(floatArrayOf(30f, 30f, 10f, 10f, 0f, 0f, 0f, 0f), Pair(100f, 40f))!!, 0f)
        // A corner with one radius at 0 is square, and its other radius takes no part in the scale.
        assertNull(ViewTreeWalker.radiiTopLeft(floatArrayOf(30f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), Pair(100f, 40f)))
        assertEquals(10f, ViewTreeWalker.radiiTopLeft(floatArrayOf(10f, 10f, 0f, 0f, 0f, 0f, 0f, 90f), Pair(100f, 40f))!!, 0f)
    }

    @Test
    fun a_gradient_shape_decides_what_it_draws() {
        // <shape android:shape="oval"> on a square View: a circle, whose radius is half its side.
        val dot = background(sized(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }, 48, 48))!!
        assertEquals("#FF0000", dot.fill)
        assertEquals(24f, dot.cornerRadiusPx!!, 0f)
        // An oval ring: its stroke, with the same radius.
        val ring = background(sized(GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(2, Color.BLUE) }, 48, 48))!!
        assertEquals(BorderInfo(2f, "#0000FF"), ring.border)
        assertEquals(23f, ring.cornerRadiusPx!!, 0f)
        // <shape android:shape="line">: one stroked line across the middle, no fill and no box border.
        val divider = background(sized(GradientDrawable().apply { shape = GradientDrawable.LINE; setStroke(2, Color.GRAY) }, 200, 10))!!
        assertNull(divider.fill)
        assertNull(divider.border)
        assertNull(divider.cornerRadiusPx)
        assertTrue(divider.customDraw)
        // A transparent line draws nothing at all.
        val clearLine = background(sized(GradientDrawable().apply { shape = GradientDrawable.LINE; setStroke(2, Color.TRANSPARENT) }))!!
        assertFalse(clearLine.customDraw)
        // <shape android:shape="ring">: its ring path, not a fill of the box.
        val donut = background(sized(GradientDrawable().apply { shape = GradientDrawable.RING; setColor(Color.RED) }, 48, 48))!!
        assertNull(donut.fill)
        assertNull(donut.border)
        assertTrue(donut.customDraw)
    }

    @Test
    fun an_image_tint_is_read_at_the_view_state() {
        val view = ImageView(context).apply {
            setImageDrawable(ColorDrawable(Color.GRAY))
            imageTintList = pressedRed()
        }
        assertEquals("#0000FF", ViewTreeWalker.imageTint(view))
        assertFalse(ViewTreeWalker.imageTintHides(view))
        view.isPressed = true
        assertEquals("#FF0000", ViewTreeWalker.imageTint(view))
        // A list with no entry for the current state tints transparent, as Drawable does, not
        // with its default color (here its only one, red). SRC_IN with it leaves nothing of the
        // image on screen, so there is no tint to report and the image is hidden.
        val pressedOnly = ImageView(context).apply {
            setImageDrawable(ColorDrawable(Color.GRAY))
            imageTintList = ColorStateList(arrayOf(pressed), intArrayOf(Color.RED))
        }
        assertNull(ViewTreeWalker.imageTint(pressedOnly))
        assertTrue(ViewTreeWalker.imageTintHides(pressedOnly))
        // SRC_ATOP draws a transparent tint over the image, which leaves it as it is.
        pressedOnly.imageTintBlendMode = BlendMode.SRC_ATOP
        assertFalse(ViewTreeWalker.imageTintHides(pressedOnly))
        // No tint.
        assertNull(ViewTreeWalker.imageTint(ImageView(context)))
        assertFalse(ViewTreeWalker.imageTintHides(ImageView(context)))
    }
}
