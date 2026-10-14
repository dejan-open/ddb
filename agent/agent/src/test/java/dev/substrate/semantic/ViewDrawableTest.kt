package dev.substrate.semantic

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.ImageView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun an_image_tint_is_read_at_the_view_state() {
        val view = ImageView(RuntimeEnvironment.getApplication()).apply {
            setImageDrawable(ColorDrawable(Color.GRAY))
            imageTintList = pressedRed()
        }
        assertEquals("#0000FF", ViewTreeWalker.imageTint(view))
        view.isPressed = true
        assertEquals("#FF0000", ViewTreeWalker.imageTint(view))
        // A list with no entry for the current state tints transparent, as Drawable does, not
        // with its default color (here its only one, red).
        val pressedOnly = ImageView(RuntimeEnvironment.getApplication()).apply {
            setImageDrawable(ColorDrawable(Color.GRAY))
            imageTintList = ColorStateList(arrayOf(pressed), intArrayOf(Color.RED))
        }
        assertEquals("#00000000", ViewTreeWalker.imageTint(pressedOnly))
        // No tint.
        assertNull(ViewTreeWalker.imageTint(ImageView(RuntimeEnvironment.getApplication())))
    }
}
