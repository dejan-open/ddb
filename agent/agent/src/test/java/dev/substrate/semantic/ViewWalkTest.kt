package dev.substrate.semantic

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The View path end to end: real Views laid out in an Activity under Robolectric, walked as a
 * capture walks them, read back by their (framework) resource ids.
 */
@RunWith(RobolectricTestRunner::class)
class ViewWalkTest {

    /** Lays out [views] (100 × 50 px each, stacked) in an Activity and walks it. */
    private fun walk(views: (Context) -> List<View>): Map<String, SemanticElement> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        views(activity).forEach { root.addView(it, LinearLayout.LayoutParams(100, 50)) }
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        return ViewTreeWalker.walk(activity).elements.filter { it.platformId != null }.associateBy { it.platformId!! }
    }

    private fun shape() = GradientDrawable().apply {
        setColor(Color.RED)
        setStroke(4, Color.BLUE)
        cornerRadius = 8f
    }

    /**
     * A box at [elevation] whose shadow is in [ambient] and [spot], by default the black a device
     * gives every View. Robolectric's RenderNode keeps no shadow colors (both always read 0), so
     * the box reports its own.
     */
    private class Shadowed(
        context: Context,
        elevation: Float,
        private val ambient: Int = Color.BLACK,
        private val spot: Int = Color.BLACK,
    ) : FrameLayout(context) {
        init {
            this.elevation = elevation
        }

        override fun getOutlineAmbientShadowColor() = ambient

        override fun getOutlineSpotShadowColor() = spot
    }

    /** A box with a [shape] background and a shadow, holding a colored box with text in it. */
    private fun box(context: Context, id: Int, innerId: Int, setUp: FrameLayout.() -> Unit = {}) = Shadowed(context, 6f).apply {
        this.id = id
        background = shape()
        addView(
            FrameLayout(context).apply {
                this.id = innerId
                background = ColorDrawable(Color.GREEN)
                addView(TextView(context).apply { text = "Inside" })
            },
            FrameLayout.LayoutParams(80, 40),
        )
        setUp()
    }

    @Test
    fun a_hidden_container_and_what_it_holds_draw_nothing() {
        val elements = walk { context ->
            listOf(
                box(context, android.R.id.widget_frame, android.R.id.icon1) { visibility = View.INVISIBLE },
                box(context, android.R.id.button1, android.R.id.icon2) {
                    alpha = 0f
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.RED, Color.BLUE))
                },
                box(context, android.R.id.button2, android.R.id.summary),
            )
        }
        // INVISIBLE, and at alpha 0: each is still in the tree as structure, with nothing it would draw.
        for (id in listOf("widget_frame", "icon1", "button1", "icon2")) {
            val element = elements[id]
            assertNotNull("$id is emitted", element)
            assertNull("$id background", element!!.background)
            assertNull("$id corner radius", element.cornerRadius)
            assertNull("$id border", element.border)
            assertNull("$id gradient", element.gradient)
            assertNull("$id elevation", element.elevation)
        }
        // The same box, shown, draws all of it.
        val shown = elements.getValue("button2")
        assertEquals("#FF0000", shown.background)
        assertEquals(8f, shown.cornerRadius!!, 0f)
        assertEquals(BorderInfo(4f, "#0000FF"), shown.border)
        assertEquals(6f, shown.elevation!!, 0f)
        assertEquals("#00FF00", elements.getValue("summary").background)
    }

    @Test
    fun a_transparent_image_tint_that_replaces_the_image_hides_it() {
        fun icon(context: Context, id: Int) = ImageView(context).apply {
            this.id = id
            setImageDrawable(ColorDrawable(Color.GRAY))
            imageTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        }
        val elements = walk { context ->
            listOf(
                icon(context, android.R.id.icon1),
                icon(context, android.R.id.icon2).apply { imageTintBlendMode = BlendMode.SRC_ATOP },
            )
        }
        // SRC_IN (the default) with a transparent tint: no image left on screen.
        val hidden = elements.getValue("icon1")
        assertEquals("image", hidden.type)
        assertNull(hidden.imageType)
        assertNull(hidden.imagePath)
        assertNull(hidden.imageResource)
        assertNull(hidden.color)
        // SRC_ATOP keeps the image as it is.
        val kept = elements.getValue("icon2")
        assertNotNull(kept.imageType)
        assertNotNull(kept.imagePath)
        assertNull(kept.color)
    }

    @Test
    fun a_transparent_text_color_is_no_color() {
        val elements = walk { context ->
            listOf(
                TextView(context).apply {
                    id = android.R.id.text1
                    text = "Clear"
                    setTextColor(Color.TRANSPARENT)
                },
                TextView(context).apply {
                    id = android.R.id.text2
                    text = "Red"
                    setTextColor(Color.RED)
                },
            )
        }
        assertNull(elements.getValue("text1").color)
        assertEquals("#FF0000", elements.getValue("text2").color)
    }

    @Test
    fun a_shadow_in_transparent_colors_is_no_shadow() {
        val elements = walk { context ->
            listOf(
                Shadowed(context, 6f, ambient = Color.TRANSPARENT, spot = Color.TRANSPARENT).apply { id = android.R.id.button1 },
                // One color still visible casts a shadow.
                Shadowed(context, 6f, ambient = Color.TRANSPARENT).apply { id = android.R.id.button2 },
                Shadowed(context, 6f, spot = Color.TRANSPARENT).apply { id = android.R.id.button3 },
            )
        }
        assertNull(elements.getValue("button1").elevation)
        assertEquals(6f, elements.getValue("button2").elevation!!, 0f)
        assertEquals(6f, elements.getValue("button3").elevation!!, 0f)
    }
}
