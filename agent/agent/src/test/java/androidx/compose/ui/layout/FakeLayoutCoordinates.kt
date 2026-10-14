@file:JvmName("LayoutCoordinatesKt")

package androidx.compose.ui.layout

/**
 * Test double for Compose's `LayoutCoordinatesKt.boundsInWindow`, which ComposeVisuals finds by
 * reflection to read where a modifier draws. Only [PlacedCoordinates] are placed in the window;
 * any other coordinates read as unplaced (null), as they did before this double existed.
 */
fun boundsInWindow(coordinates: Any): WindowRect? = (coordinates as? PlacedCoordinates)?.rect

/** Compose's `positionInWindow`: the unclipped top-left, an Offset packed as two floats. */
fun positionInWindow(coordinates: Any): Long {
    val box = (coordinates as PlacedCoordinates).unclipped
    return (java.lang.Float.floatToRawIntBits(box.left).toLong() shl 32) or
        (java.lang.Float.floatToRawIntBits(box.top).toLong() and 0xFFFFFFFFL)
}

/** A Compose Rect, with its getters. */
class WindowRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Attached coordinates at [rect] in the window (clipped to the ancestors), laid out at
 * [unclipped], with their measured size packed as Compose packs an IntSize, and the parent
 * layout's coordinates.
 */
class PlacedCoordinates(val rect: WindowRect, val unclipped: WindowRect = rect, private val parent: Any? = null) {
    fun isAttached(): Boolean = true
    fun getSize(): Long = ((unclipped.right - unclipped.left).toLong() shl 32) or (unclipped.bottom - unclipped.top).toLong()
    fun getParentLayoutCoordinates(): Any? = parent
}
