@file:JvmName("LayoutCoordinatesKt")

package androidx.compose.ui.layout

/**
 * Test double for Compose's `LayoutCoordinatesKt.boundsInWindow`, which ComposeVisuals finds by
 * reflection to read where a modifier draws. Only [PlacedCoordinates] are placed in the window;
 * any other coordinates read as unplaced (null), as they did before this double existed.
 */
fun boundsInWindow(coordinates: Any): WindowRect? = (coordinates as? PlacedCoordinates)?.rect

/** A Compose Rect, with its getters. */
class WindowRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Attached coordinates at [rect] in the window, with their measured size packed as Compose packs an IntSize. */
class PlacedCoordinates(val rect: WindowRect) {
    fun isAttached(): Boolean = true
    fun getSize(): Long = ((rect.right - rect.left).toLong() shl 32) or (rect.bottom - rect.top).toLong()
}
