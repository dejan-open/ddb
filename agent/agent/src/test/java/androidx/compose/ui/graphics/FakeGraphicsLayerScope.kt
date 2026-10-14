package androidx.compose.ui.graphics

/**
 * Test double for Compose's ReusableGraphicsLayerScope, the scope ComposeVisuals runs a
 * `graphicsLayer { }` block on (made through its no-argument constructor, by reflection). It has
 * the getters ComposeVisuals reads back; the block sets them as it would on Compose's scope.
 */
class ReusableGraphicsLayerScope {
    var alpha: Float = 1f
    var shadowElevation: Float = 0f
    var clip: Boolean = false
    var shape: Any? = null
    var scaleX: Float = 1f
    var scaleY: Float = 1f
}
