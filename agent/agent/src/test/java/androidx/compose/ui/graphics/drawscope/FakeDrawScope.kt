package androidx.compose.ui.graphics.drawscope

/**
 * Test doubles for Compose's draw scopes. ComposeVisuals.probeContentDraw finds
 * ContentDrawScope by name and answers it (and every interface reached from it) with a
 * recording proxy; unit tests have no Compose on the classpath, so these stand in with the
 * members the probe answers. A real DrawScope's size getter is name-mangled (getSize-NH-jbRc);
 * the probe accepts both spellings.
 */
interface DrawScope {
    val size: Long
    val density: Float
    val drawContext: DrawContext

    fun drawRect(color: Long)

    /** DrawScope's member extension GraphicsLayer.record(size, block). */
    fun Any.record(size: Long, block: DrawScope.() -> Unit)
}

interface DrawContext {
    val canvas: Canvas
}

/** Compose's own Canvas interface (androidx.compose.ui.graphics.Canvas). */
interface Canvas {
    fun save()

    fun restore()

    fun drawCircle(radius: Float)
}

interface ContentDrawScope : DrawScope {
    fun drawContent()
}
