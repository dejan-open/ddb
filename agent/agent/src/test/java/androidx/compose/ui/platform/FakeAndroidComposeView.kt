package androidx.compose.ui.platform

import android.content.Context
import android.widget.FrameLayout

/**
 * Test double for Compose's AndroidComposeView, which ViewTreeWalker finds by its class name and
 * walks through its LayoutNode root (getRoot, by name and return type). It has no semantics
 * owner, so the walk reads only the LayoutNode tree.
 */
class AndroidComposeView(context: Context, private val root: LayoutNode) : FrameLayout(context) {
    fun getRoot(): LayoutNode = root
}

/** A LayoutNode: where it sits in the window and its modifier infos (each with getModifier). */
class LayoutNode(private val coordinates: Any, private val modifierInfo: List<Any>) {
    fun getOuterCoordinator(): Any = coordinates
    fun getModifierInfo(): List<Any> = modifierInfo
}
