package androidx.compose.ui.graphics

/**
 * Test double for Compose's BlendMode, which ComposeVisuals reads by reflection to name a
 * BlendModeColorFilter's mode: its companion has one Int getter per mode (getSrcIn, …; Compose's
 * are mangled, getSrcIn-0nO6VwU). The values are Compose's.
 */
class BlendMode {
    companion object {
        val Clear = 0
        val Src = 1
        val SrcOver = 3
        val SrcIn = 5
        val SrcAtop = 9
    }
}
