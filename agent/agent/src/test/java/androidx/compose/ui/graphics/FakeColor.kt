@file:JvmName("ColorKt")

package androidx.compose.ui.graphics

/**
 * Test double for Compose's `ColorKt.toArgb`, which ComposeVisuals finds by reflection to turn a
 * packed Color into ARGB. Unit tests have no Compose on the classpath, so without it every color
 * read through ComposeVisuals.read is null. An sRGB Color keeps its ARGB in the high 32 bits,
 * which is all the tests pack.
 */
fun toArgb(color: Long): Int = (color ushr 32).toInt()
