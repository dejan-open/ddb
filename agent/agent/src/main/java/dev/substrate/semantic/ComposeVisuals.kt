package dev.substrate.semantic

import android.content.res.Resources

/**
 * What a Compose LayoutNode draws, read from its modifier chain by reflection.
 *
 * The agent has no compile-time Compose dependency, so every read goes
 * through the element classes' backing fields (stable across releases,
 * unlike the name-mangled value-class getters). Each read is independent
 * and fails soft: an unknown element or a renamed field yields null for
 * that attribute only, never a failed walk.
 */
internal data class ComposeDrawInfo(
    val background: String? = null,
    val gradient: GradientInfo? = null,
    val cornerRadius: Float? = null,
    val border: BorderInfo? = null,
    val padding: PaddingInfo? = null,
    val margin: MarginInfo? = null,
    val elevation: Float? = null,
    val alpha: Float? = null,
    val imageType: String? = null,
    val imageTint: String? = null,
    val font: FontInfo? = null,
    val textColor: String? = null,
    val maxLines: Int? = null,
    /**
     * The node draws through a custom draw modifier (drawBehind, drawWithContent or
     * drawWithCache; a Compose Canvas is a Spacer with drawBehind). What it draws can't be
     * read, but it is on screen like any other visual.
     */
    val customDraw: Boolean = false,
    /** Bounds of the first drawing modifier, when it differs from the node's (an outer padding). */
    val drawBounds: Bounds? = null,
    /**
     * A drawWithContent block (or a drawWithCache onDrawWithContent block) was seen to return
     * without calling drawContent(): the modifiers after it and the node's children are not drawn.
     */
    val contentSkipped: Boolean = false,
    /**
     * The node's click (clickable, combinedClickable, toggleable, selectable) comes before any
     * outer padding in the chain (clickable { }.padding(8.dp)), so it covers the whole box,
     * margin included.
     */
    val clickOutsideMargin: Boolean = false,
) {
    val draws: Boolean
        // A shadow is drawn on its own, with no background or content under it.
        get() = background != null || gradient != null || border != null || imageType != null || elevation != null ||
            customDraw

    /**
     * Whether the node's content and subtree are off screen when drawn at [subtreeAlpha] (every
     * ancestor's and the node's own layer alpha): under a layer at alpha 0, or after a skipped
     * drawContent(). What the node draws before that point is still on screen.
     */
    fun hidesContent(subtreeAlpha: Float): Boolean = subtreeAlpha <= 0f || contentSkipped
}

internal object ComposeVisuals {
    const val UNSPECIFIED_COLOR = 16L

    /** Elements that put pixels on screen (a layer at alpha 0 hides those after it). */
    private val DRAWING_ELEMENTS = setOf(
        "BackgroundElement", "BorderModifierNodeElement", "PainterElement", "ShadowGraphicsLayerElement",
        "DrawBehindElement", "DrawWithContentElement", "DrawWithCacheElement",
        "ContentPainterElement", "SubcomposeContentPainterElement",
    )

    /**
     * Modifiers that draw a painter: Modifier.paint (Image) and Coil 3's AsyncImage, whose
     * element is a ContentPainterElement that keeps its AsyncImagePainter on the live node
     * (ContentPainterNode.painter), and SubcomposeAsyncImage's SubcomposeContentPainterElement,
     * which keeps it on the element.
     */
    private val PAINTER_ELEMENTS = setOf("PainterElement", "ContentPainterElement", "SubcomposeContentPainterElement")

    private const val ASYNC_IMAGE_PAINTER = "coil3.compose.AsyncImagePainter"

    /**
     * Modifiers that run an arbitrary draw lambda and have no fields to decode:
     * Modifier.drawBehind (which a Compose Canvas is built on), Modifier.drawWithContent and
     * Modifier.drawWithCache. Whether the lambda puts pixels on screen can't be read through
     * reflection, so each one counts as a draw: it marks the node as drawn, sets the
     * padding/margin boundary and is hidden by an alpha-0 layer before it, like a background.
     * A lambda that only calls drawContent() costs at most a plain container in the capture;
     * dropping a node that does draw would lose it entirely.
     *
     * drawWithContent and drawWithCache's onDrawWithContent also decide whether the content
     * under them is drawn at all, which [probeContentDraw] observes by running the block once in
     * a read-only snapshot: a block seen to return without calling drawContent() hides the rest
     * of the chain and the children, and one that also drew nothing of its own is no draw.
     * drawBehind always draws the content after its lambda (DrawBackgroundModifier.draw:
     * onDraw(), then drawContent()).
     */
    private val CUSTOM_DRAW_ELEMENTS = setOf("DrawBehindElement", "DrawWithContentElement", "DrawWithCacheElement")

    /** Whether a modifier, known by its element class name, is a custom draw. */
    fun isCustomDraw(elementName: String): Boolean = elementName in CUSTOM_DRAW_ELEMENTS

    /** What a content block (a `ContentDrawScope.() -> Unit`) does with the content under it. */
    enum class ContentDraw {
        /** It called drawContent(): the rest of the chain and the children are drawn. */
        CONTENT,

        /** It returned without calling drawContent(), after a draw call of its own. */
        OWN_ONLY,

        /** It returned without calling drawContent() or drawing anything: nothing is on screen. */
        NOTHING,

        /** It could not be run, or it threw before calling drawContent(). */
        UNKNOWN,
    }

    private const val CONTENT_DRAW_SCOPE = "androidx.compose.ui.graphics.drawscope.ContentDrawScope"
    private const val SNAPSHOT = "androidx.compose.runtime.snapshots.Snapshot"

    /**
     * Runs [body] inside a read-only Compose snapshot: `Snapshot.takeSnapshot()`, entered with
     * `Snapshot.enter { }` and disposed afterwards (public runtime API, reached by reflection
     * like everything else here). Inside it a state write throws (ReadonlySnapshot.recordModified
     * reports "Cannot modify a state object in a read-only snapshot"), so the write never lands
     * and never reaches the global snapshot, whose write observers are what schedule
     * recomposition and draw invalidation. Reads register nothing: the snapshot is taken with no
     * read observer, and Compose only tracks draw reads inside its own observer
     * (NodeCoordinator's drawBlock runs the draw modifiers in snapshotObserver.observeReads).
     *
     * Returns false, without running [body], when no read-only snapshot can be taken (the
     * runtime is not on [loader]'s path). An exception from [body] is rethrown as is.
     */
    fun runInReadOnlySnapshot(loader: ClassLoader?, body: () -> Unit): Boolean {
        val snapshotClass: Class<*>
        val companion: Any
        val take: java.lang.reflect.Method
        val enter: java.lang.reflect.Method
        val dispose: java.lang.reflect.Method
        try {
            snapshotClass = Class.forName(SNAPSHOT, false, loader)
            companion = snapshotClass.getField("Companion").get(null) ?: return false
            take = companion.javaClass.getMethod("takeSnapshot", Function1::class.java)
            enter = snapshotClass.getMethod("enter", Function0::class.java)
            dispose = snapshotClass.getMethod("dispose")
        } catch (_: Throwable) {
            return false
        }
        // Every member is resolved before the snapshot exists, so a taken snapshot is always disposed.
        val snapshot = try {
            take.invoke(companion, null)
        } catch (_: Throwable) {
            null
        } ?: return false
        try {
            enter.invoke(snapshot, body)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        } finally {
            try {
                dispose.invoke(snapshot)
            } catch (_: Throwable) {
            }
        }
        return true
    }

    /** Ends a probed block at its drawContent() call: the answer is known, and nothing after it runs. */
    private class ContentDrawn : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /**
     * Runs a content block once on a recording ContentDrawScope and reports whether it draws
     * the content under it. The block decides that itself: Compose's DrawWithContentModifier
     * (DrawModifier.kt) draws by running `onDraw()` and nothing else, drawWithCache's node runs
     * its cached DrawResult block the same way, and only ContentDrawScope.drawContent() draws
     * the rest of the chain and the children (LayoutNodeDrawScope.drawContent: the next draw
     * node, else the wrapped coordinator). ContentDrawScope's own contract: "If drawContent is
     * not called, the contents of the layout will not be drawn."
     *
     * The scope records instead of drawing. Draw calls are dropped and noted. Size (the
     * modifier's measured size, [sizePx]), density, font scale and layout direction are the
     * node's. DrawScope's GraphicsLayer.record runs its block on this scope and never touches
     * the layer. The run ends at the first drawContent(), so nothing after it executes. A block
     * that throws first, for example by reaching for the platform canvas, is UNKNOWN, and the
     * caller keeps the content, as before: the capture never drops content it did not see skipped.
     *
     * The block runs inside a read-only Compose snapshot ([runInReadOnlySnapshot]), so it can
     * read app state but not change it: a state write throws, which makes the block UNKNOWN,
     * and nothing reaches the app's global snapshot. With no read-only snapshot available the
     * block is not run at all, and is UNKNOWN. Compose itself runs these blocks whenever it
     * redraws, as many times as it likes (every layer re-record, and every View.draw onto a
     * software canvas, which re-runs the layer's draw block), so a run here is one more of those.
     */
    fun probeContentDraw(
        block: Any?,
        anchor: Any,
        sizePx: Pair<Int, Int>?,
        density: Float,
        fontScale: Float,
        layoutDirection: Any?,
    ): ContentDraw {
        @Suppress("UNCHECKED_CAST")
        val fn = block as? Function1<Any?, Any?> ?: return ContentDraw.UNKNOWN
        val loader = anchor.javaClass.classLoader
        val scopeClass = try {
            Class.forName(CONTENT_DRAW_SCOPE, false, loader)
        } catch (_: Throwable) {
            return ContentDraw.UNKNOWN
        }
        val size = sizePx?.let { packSize(it.first.toFloat(), it.second.toFloat()) } ?: packSize(0f, 0f)
        val recorder = DrawRecorder(size, density, fontScale, layoutDirection)
        val scope = recorder.proxy(scopeClass) ?: return ContentDraw.UNKNOWN
        var returned = false
        val ran = try {
            runInReadOnlySnapshot(loader) {
                fn.invoke(scope)
                returned = true
            }
        } catch (_: Throwable) {
            // The block ran and threw: at its drawContent() (the probe's stop), on a state
            // write, or on anything else it reached for.
            true
        }
        if (!ran) return ContentDraw.UNKNOWN
        return when {
            recorder.contentDrawn -> ContentDraw.CONTENT
            !returned -> ContentDraw.UNKNOWN
            recorder.drewOwn -> ContentDraw.OWN_ONLY
            else -> ContentDraw.NOTHING
        }
    }

    /**
     * The scope a probed content block runs on, and every Compose interface it reaches from there
     * (DrawContext, Canvas, DrawTransform, Density), as dynamic proxies that answer with the node's
     * size, density and layout direction, drop draws, and note whether drawContent() or a draw ran.
     */
    private class DrawRecorder(
        private val size: Long,
        private val density: Float,
        private val fontScale: Float,
        private val layoutDirection: Any?,
    ) {
        var contentDrawn = false
            private set
        var drewOwn = false
            private set

        fun proxy(iface: Class<*>): Any? = try {
            java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { proxy, method, args ->
                answer(proxy, method, args ?: emptyArray())
            }
        } catch (_: Throwable) {
            null
        }

        private fun answer(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>): Any? {
            val name = method.name
            val returns = method.returnType
            when {
                name == "equals" && args.size == 1 -> return proxy === args[0]
                name == "hashCode" && args.isEmpty() -> return System.identityHashCode(proxy)
                name == "toString" && args.isEmpty() -> return "DrawRecorder(${method.declaringClass.simpleName})"
                name == "drawContent" && args.isEmpty() -> {
                    contentDrawn = true
                    throw ContentDrawn()
                }
                // DrawScope's GraphicsLayer.record(size, block): run the block here, not into the
                // app's live layer. Its drawContent() is this scope's.
                name.startsWith("record") && args.any { it is Function1<*, *> } -> {
                    @Suppress("UNCHECKED_CAST")
                    (args.last { it is Function1<*, *> } as Function1<Any?, Any?>).invoke(proxy)
                    return zero(returns)
                }
                name.startsWith("draw") -> {
                    drewOwn = true
                    return zero(returns)
                }
                args.isEmpty() && returns == java.lang.Long.TYPE && (name == "getSize" || name.startsWith("getSize-")) ->
                    return size
                args.isEmpty() && returns == java.lang.Long.TYPE && (name == "getCenter" || name.startsWith("getCenter-")) -> {
                    val (w, h) = unpackOffset(size)
                    return packSize(w / 2f, h / 2f)
                }
                args.isEmpty() && returns == java.lang.Float.TYPE && name == "getDensity" -> return density
                args.isEmpty() && returns == java.lang.Float.TYPE && name == "getFontScale" -> return fontScale
                args.isEmpty() && name == "getLayoutDirection" && layoutDirection != null -> return layoutDirection
                args.isEmpty() && name == "getGraphicsLayer" -> return null
                // DrawContext.canvas and .density have default bodies (EmptyCanvas, which throws on
                // every call, and a density of 1): a recording proxy answers for them instead, as
                // for drawContext and transform.
                args.isEmpty() && name.startsWith("get") && returns.isInterface -> return proxy(returns)
            }
            // A member with a Kotlin default body (Density.toPx, DrawScope helpers): run that body.
            val body = defaultBody(proxy, method, args)
            if (body != null) return body.value
            return zero(returns)
        }

        private class Answer(val value: Any?)

        /** Runs a member's Kotlin default body, kept in the interface's DefaultImpls; null when there is none. */
        private fun defaultBody(proxy: Any, method: java.lang.reflect.Method, args: Array<Any?>): Answer? {
            val iface = method.declaringClass
            val impl = try {
                Class.forName(iface.name + "\$DefaultImpls", false, iface.classLoader).methods.firstOrNull {
                    it.name == method.name && it.parameterTypes.contentEquals(arrayOf(iface, *method.parameterTypes))
                }
            } catch (_: Throwable) {
                null
            } ?: return null
            return try {
                Answer(impl.invoke(null, proxy, *args))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        /** What a dropped call returns: zero for a primitive, a recording proxy for an interface. */
        private fun zero(type: Class<*>): Any? = when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Character.TYPE -> 0.toChar()
            else -> if (type.isInterface) proxy(type) else null
        }
    }

    /**
     * The live modifier nodes of a LayoutNode whose class is [simpleName], in chain order: its
     * NodeChain from the head (the outermost modifier) through each node's child. Null when the
     * chain can't be read. drawWithCache keeps its content block on its node
     * (CacheDrawModifierNodeImpl), and Coil's AsyncImage its painter (ContentPainterNode).
     */
    private fun chainNodes(layoutNode: Any, simpleName: String): List<Any>? {
        var node = field(field(layoutNode, "nodes"), "head") ?: return null
        val out = ArrayList<Any>()
        repeat(10_000) {
            if (node.javaClass.simpleName == simpleName) out.add(node)
            node = field(node, "child") ?: return out
        }
        return null
    }

    /** What a painter puts on screen ([painterDraw]). */
    sealed class PainterDraw {
        /** It draws [painter]. */
        class Draws(val painter: Any) : PainterDraw()

        /** It draws nothing. */
        object None : PainterDraw()

        /** What it draws can't be read. */
        object Unread : PainterDraw()
    }

    /**
     * What [painter] draws: itself, or, for Coil's AsyncImagePainter, the painter it currently
     * draws. AsyncImagePainter.onDraw draws its `painter` property (the current state's painter,
     * after the request's transform) and nothing while that is null: State.Empty before a
     * request starts, and Loading or Error with no placeholder or error painter. The property is
     * a MutableState (`painter$delegate`); without it the current state's painter is read
     * (`state.value.painter`), which it is set from.
     */
    fun painterDraw(painter: Any?): PainterDraw {
        if (painter == null) return PainterDraw.Unread
        if (painter.javaClass.name != ASYNC_IMAGE_PAINTER) return PainterDraw.Draws(painter)
        val current = field(painter, "painter\$delegate")?.let { call0(it, "getValue") }
            ?: call0(painter, "getState")?.value?.let { call0(it, "getValue") }?.value?.let { call0(it, "getPainter") }
            ?: return PainterDraw.Unread
        return current.value?.let { PainterDraw.Draws(it) } ?: PainterDraw.None
    }

    /** A value read by reflection, which may itself be null. */
    class Read(val value: Any?)

    /**
     * Calls a no-argument method through a public class or interface that declares it, so a
     * state or flow whose own class is not public (StateFlowImpl) can still be read. Null when
     * there is no such method or the call throws.
     */
    fun call0(obj: Any, name: String): Read? {
        val queue = ArrayDeque<Class<*>>().apply { add(obj.javaClass) }
        val seen = HashSet<Class<*>>()
        while (queue.isNotEmpty()) {
            val type = queue.removeFirst()
            if (!seen.add(type)) continue
            val method = if (java.lang.reflect.Modifier.isPublic(type.modifiers)) {
                try {
                    type.getMethod(name).takeIf { java.lang.reflect.Modifier.isPublic(it.declaringClass.modifiers) }
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            if (method != null) {
                return try {
                    Read(method.invoke(obj))
                } catch (_: Throwable) {
                    null
                }
            }
            type.superclass?.let { queue.add(it) }
            queue.addAll(type.interfaces)
        }
        return null
    }

    /**
     * The content block a drawWithCache node last built: its CacheDrawScope's DrawResult, the
     * `onDrawWithContent` (or onDrawBehind) block the node runs on every draw. Null before the
     * node's first draw, or when the node can't be read.
     */
    private fun cachedDrawBlock(node: Any?): Any? = field(field(field(node, "cacheDrawScope"), "drawResult"), "block")

    /**
     * How a Compose node reports an image. It is one when a painter draws an image or its
     * semantics role is Image, but only the painter tells the format: Role.Image on a Canvas,
     * custom drawing or a bitmap-backed component is accessibility semantics, so the format
     * stays unknown (null) rather than a guessed "vector".
     */
    data class ImageFacts(val isImage: Boolean, val imageType: String?)

    fun imageFacts(painterImageType: String?, role: String?): ImageFacts =
        ImageFacts(isImage = painterImageType != null || role == "Image", imageType = painterImageType)

    /**
     * Text modifiers whose style and color are read. Text in a SelectionContainer is a
     * SelectableTextAnnotatedStringElement, with the same style and color fields.
     */
    val TEXT_ELEMENTS = setOf(
        "TextStringSimpleElement", "TextAnnotatedStringElement", "SelectableTextAnnotatedStringElement",
    )

    /** Modifiers that give a node its click: it covers the box they are applied to. */
    private val CLICK_ELEMENTS = setOf(
        "ClickableElement", "CombinedClickableElement", "ToggleableElement", "TriStateToggleableElement",
        "SelectableElement",
    )

    fun read(
        layoutNode: Any,
        density: Float,
        fontScale: Float,
        resources: Resources?,
        nodeBounds: Bounds?,
        inheritedAlpha: Float = 1f,
    ): ComposeDrawInfo {
        val infos = invoke0(layoutNode, "getModifierInfo") as? List<*> ?: return ComposeDrawInfo()
        // The node's own Compose Density: it carries the window's FontScaleConverter, so
        // sp converts as Compose renders it (nonlinear at large font scales on Android 14+).
        val composeDensity = invoke0(layoutNode, "getDensity")
        var v = ComposeDrawInfo()
        // Graphics-layer alpha fades everything after the layer in the chain (and the
        // subtree), and stacked layers multiply. ownAlpha is this node's layers only;
        // layerAlpha is what a modifier at the current chain position is drawn with.
        var ownAlpha = 1f
        fun layerAlpha(): Float = inheritedAlpha * ownAlpha
        var outerPad: FloatArray? = null
        var innerPad: FloatArray? = null
        var seenDraw = false
        // A click seen before any outer padding covers the whole box, margin included.
        var clickOutsideMargin = false
        // The first drawing modifier owns the draw bounds, even when they equal the node's
        // (recorded as null): a later modifier inside padding must not replace them.
        var drawBoundsTaken = false
        fun firstDrawBounds(info: Any): Bounds? {
            if (drawBoundsTaken) return v.drawBounds
            drawBoundsTaken = true
            return modifierBounds(info, nodeBounds)
        }
        // A content block that skipped drawContent() hides every draw after it in the chain.
        var contentSkipped = false
        fun drawsHere(): Boolean = layerAlpha() > 0f && !contentSkipped
        // drawWithCache keeps its content block on the live node, and Coil's AsyncImage its
        // painter, in chain order.
        val elementNames = infos.map { invoke0(it, "getModifier")?.javaClass?.simpleName }
        val cacheElements = elementNames.count { it == "DrawWithCacheElement" }
        val cacheNodes by lazy { chainNodes(layoutNode, "CacheDrawModifierNodeImpl") }
        var cacheIndex = 0
        val painterElements = elementNames.count { it == "ContentPainterElement" }
        val painterNodes by lazy { chainNodes(layoutNode, "ContentPainterNode") }
        var painterIndex = 0
        for (info in infos) {
            if (info == null) continue
            val element = invoke0(info, "getModifier") ?: continue
            val name = element.javaClass.simpleName
            // The k-th such element is the k-th such node, when the counts agree.
            val cacheNode = if (name == "DrawWithCacheElement") {
                val i = cacheIndex++
                cacheNodes?.takeIf { it.size == cacheElements }?.get(i)
            } else {
                null
            }
            val painterNode = if (name == "ContentPainterElement") {
                val i = painterIndex++
                painterNodes?.takeIf { it.size == painterElements }?.get(i)
            } else {
                null
            }
            // Inside a layer at alpha 0, or after a skipped drawContent(), nothing is drawn;
            // modifiers before it still are.
            if (!drawsHere() && name in DRAWING_ELEMENTS) continue
            if (isCustomDraw(name)) {
                val block = when (name) {
                    "DrawWithContentElement" -> field(element, "onDraw")
                    "DrawWithCacheElement" -> cachedDrawBlock(cacheNode)
                    else -> null
                }
                val drawn = if (block == null) {
                    ContentDraw.UNKNOWN
                } else {
                    probeContentDraw(
                        block, element, measuredSize(info, nodeBounds), density, fontScale,
                        invoke0(layoutNode, "getLayoutDirection"),
                    )
                }
                if (drawn == ContentDraw.OWN_ONLY || drawn == ContentDraw.NOTHING) {
                    contentSkipped = true
                    v = v.copy(contentSkipped = true)
                }
                // A block that put nothing on screen is no draw and sets no padding boundary.
                if (drawn == ContentDraw.NOTHING) continue
                // A draw like a background: it sets the inside/outside padding boundary and the
                // draw bounds, and where nothing is drawn it was skipped above.
                seenDraw = true
                v = v.copy(customDraw = true, drawBounds = firstDrawBounds(info))
                continue
            }
            when (name) {
                in CLICK_ELEMENTS -> if (outerPad == null) clickOutsideMargin = true
                "BackgroundElement" -> {
                    // background(brush, alpha) scales the brush only; the color overload has none.
                    val brushAlpha = (field(element, "alpha") as? Float)?.takeIf { it.isFinite() } ?: 1f
                    val brush = field(element, "brush")
                    val color = colorHex(element, (field(element, "color") as? Long) ?: UNSPECIFIED_COLOR, alpha = layerAlpha())
                    val decoded = if (brushAlpha > 0f) gradient(brush, element, brushAlpha * layerAlpha()) else null
                    // A gradient keeps transparent stops for its fade, but one whose every stop
                    // is transparent draws nothing.
                    val grad = decoded?.takeIf { gradientVisible(it.colors) }
                    val solid = color ?: if (brushAlpha > 0f) solidBrushHex(brush, element, brushAlpha * layerAlpha()) else null
                    // Nothing drawn: a transparent color with no brush, a brush at alpha 0, a
                    // solid brush with no color left, or an all-transparent gradient. Any other
                    // brush (a ShaderBrush) still draws, even when its colors could not be read.
                    val brushDrawsNothing = brush == null || brushAlpha <= 0f ||
                        (brush.javaClass.simpleName == "SolidColor" && solid == null) ||
                        (decoded != null && grad == null)
                    if (color == null && brushDrawsNothing) continue
                    seenDraw = true
                    // A brush that draws but whose colors can't be decoded (a ShaderBrush, or a
                    // gradient whose stops failed to read) is still a visual: its type, no colors.
                    val opaqueBrush = if (grad == null && solid == null && !brushDrawsNothing) {
                        GradientInfo(type = brushType(brush) ?: "shader", colors = emptyList(), orientation = null)
                    } else {
                        null
                    }
                    v = v.copy(
                        background = v.background ?: solid,
                        gradient = v.gradient ?: grad ?: opaqueBrush,
                        cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, measuredSize(info, nodeBounds)),
                        drawBounds = firstDrawBounds(info),
                    )
                }
                "BorderModifierNodeElement" -> {
                    // The stroke Compose draws: rounded up to whole pixels and clamped to half the
                    // smaller side of the size the modifier draws at.
                    val width = borderWidthDp(field(element, "width") as? Float, density, measuredSize(info, nodeBounds))
                    val brush = field(element, "brush")
                    val color = solidBrushHex(brush, element, layerAlpha())
                    // A solid border with no color left is a fully transparent one: not drawn.
                    // Compose strokes a gradient border with the brush itself, so one whose every
                    // stop is transparent draws nothing either, as for backgrounds.
                    val stops = gradient(brush, element, layerAlpha())?.colors
                    val invisible = !borderDraws(brush?.javaClass?.simpleName, color, stops)
                    // Only a border that draws sets the inside/outside padding boundary.
                    if (width == null || invisible) continue
                    seenDraw = true
                    if (v.border == null) {
                        v = v.copy(
                            border = BorderInfo(width = width, color = color),
                            cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, measuredSize(info, nodeBounds)),
                            drawBounds = firstDrawBounds(info),
                        )
                    }
                }
                in PAINTER_ELEMENTS -> {
                    // Image(alpha = 0f) / paint(alpha = 0f) / AsyncImage(alpha = 0f) draws nothing.
                    val painterAlpha = (field(element, "alpha") as? Float)?.takeIf { it.isFinite() } ?: 1f
                    if (painterAlpha <= 0f) continue
                    // paint(painter, colorFilter = ColorFilter.tint(Color.Transparent)) replaces every
                    // pixel the painter draws with a transparent one: nothing is on screen, as for a
                    // painter that draws nothing (no image, no tint, no padding boundary).
                    if (filterHides(field(element, "colorFilter"), element)) continue
                    val source = if (name == "ContentPainterElement") field(painterNode, "painter") else field(element, "painter")
                    // Coil's painter is an image loaded at run time, like a View bitmap with no
                    // resource, whatever painter it draws it with.
                    val coil = name != "PainterElement" || source?.javaClass?.name == ASYNC_IMAGE_PAINTER
                    val drawn = painterDraw(source)
                    // An AsyncImagePainter with no current painter draws nothing: no image, and
                    // no padding boundary.
                    if (drawn is PainterDraw.None) continue
                    val painter = (drawn as? PainterDraw.Draws)?.painter
                    if (painter?.javaClass?.simpleName == "ColorPainter") {
                        // ColorPainter fills its bounds with one color: that is a background, not
                        // an image, and a transparent one draws nothing (no padding boundary).
                        val fill = (field(painter, "color") as? Long)?.let { colorHex(element, it, alpha = painterAlpha * layerAlpha()) }
                            ?: continue
                        seenDraw = true
                        v = v.copy(
                            background = v.background ?: fill,
                            drawBounds = firstDrawBounds(info),
                        )
                        continue
                    }
                    seenDraw = true
                    val type = if (coil) "loaded" else painterType(painter)
                    if (type != null && v.imageType == null) {
                        v = v.copy(
                            imageType = type,
                            imageTint = colorFilterHex(field(element, "colorFilter"), element, painterAlpha * layerAlpha()),
                            drawBounds = firstDrawBounds(info),
                        )
                    }
                }
                "ShadowGraphicsLayerElement" -> {
                    // A shadow whose ambient and spot colors are both transparent draws nothing.
                    val elev = (field(element, "elevation") as? Float)?.takeIf {
                        it.isFinite() && it > 0f && shadowColorsVisible(
                            argb(element, field(element, "ambientColor") as? Long),
                            argb(element, field(element, "spotColor") as? Long),
                        )
                    }
                    // The layer's shape rounds the first draw only when the layer encloses it (no
                    // draw came before it) and clips or shadows; a later layer shapes only what
                    // follows it, and one that does neither shapes nothing.
                    val shapes = !seenDraw && (elev != null || field(element, "clip") == true)
                    // A shadow is a draw: it sets the inside/outside boundary like a background.
                    if (elev != null) seenDraw = true
                    v = v.copy(
                        elevation = v.elevation ?: elev,
                        cornerRadius = v.cornerRadius
                            ?: if (shapes) cornerRadius(field(element, "shape"), density, measuredSize(info, nodeBounds)) else null,
                        drawBounds = if (elev != null) firstDrawBounds(info) else v.drawBounds,
                    )
                }
                "GraphicsLayerElement", "BlockGraphicsLayerElement" -> {
                    // graphicsLayer(alpha = …) keeps the values as fields; graphicsLayer { … }
                    // keeps a block, which is run on a fresh layer scope to read them.
                    val layer = if (element.javaClass.simpleName == "GraphicsLayerElement") {
                        LayerProps(
                            alpha = field(element, "alpha") as? Float,
                            shadowPx = field(element, "shadowElevation") as? Float,
                            clip = field(element, "clip") as? Boolean ?: false,
                            shape = field(element, "shape"),
                            shadowColorsVisible = shadowColorsVisible(
                                argb(element, field(element, "ambientShadowColor") as? Long),
                                argb(element, field(element, "spotShadowColor") as? Long),
                            ),
                            scaleX = field(element, "scaleX") as? Float,
                            scaleY = field(element, "scaleY") as? Float,
                        )
                    } else {
                        blockLayer(element, composeDensity, density, fontScale, measuredSize(info, nodeBounds))
                    } ?: continue
                    val alpha = layer.alpha?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    if (alpha != null && alpha < 1f) ownAlpha *= alpha
                    // scale(0f) collapses the layer to nothing: like alpha 0, it hides what follows
                    // it and the subtree, and draws no shadow.
                    if (layer.collapsed) ownAlpha = 0f
                    // A layer's shadow is drawn with the layer's own alpha: none at 0, and none
                    // after a skipped drawContent().
                    val shadow = layer.shadowPx
                        ?.takeIf { it.isFinite() && it > 0f && drawsHere() && layer.shadowColorsVisible }
                        ?.let { it / density }
                    // As for a shadow element: only a layer that encloses the first draw shapes it.
                    val shapes = !seenDraw && (layer.clip || shadow != null)
                    if (shadow != null) seenDraw = true
                    v = v.copy(
                        alpha = if (ownAlpha < 1f) ownAlpha else v.alpha,
                        elevation = v.elevation ?: shadow,
                        cornerRadius = v.cornerRadius
                            ?: if (shapes) cornerRadius(layer.shape, density, measuredSize(info, nodeBounds)) else null,
                        drawBounds = if (shadow != null) firstDrawBounds(info) else v.drawBounds,
                    )
                }
                "PaddingElement" -> {
                    // absolutePadding(left, right) keeps left/right in start/end with
                    // rtlAware = false: in an RTL layout its logical start is the right side.
                    val (start, end) = logicalSides(
                        dp(field(element, "start")),
                        dp(field(element, "end")),
                        rtlAware = field(element, "rtlAware") as? Boolean ?: true,
                        rtl = (invoke0(layoutNode, "getLayoutDirection") as? Enum<*>)?.name == "Rtl",
                    )
                    val p = floatArrayOf(dp(field(element, "top")), dp(field(element, "bottom")), start, end)
                    if (seenDraw) innerPad = add(innerPad, p) else outerPad = add(outerPad, p)
                }
                "PaddingValuesElement" -> {
                    // Modifier.padding(PaddingValues), e.g. a Scaffold's content padding.
                    val p = paddingValues(field(element, "paddingValues"), layoutNode)
                    if (p != null) {
                        if (seenDraw) innerPad = add(innerPad, p) else outerPad = add(outerPad, p)
                    }
                }
                in TEXT_ELEMENTS -> {
                    val style = field(element, "style")
                    val producer = field(element, "color")
                    // An AnnotatedString's span styles draw over the base style (a String has none).
                    val spans = textSpans(field(element, "text"))
                    val color = textColorHex(producer, style, spans, element, layerAlpha())
                    val maxLines = (field(element, "maxLines") as? Int)?.takeIf { it in 1 until Int.MAX_VALUE }
                    v = v.copy(
                        font = v.font ?: font(style, spans, composeDensity, fontScale, resources),
                        textColor = v.textColor ?: color,
                        maxLines = v.maxLines ?: maxLines,
                    )
                }
            }
        }
        // With no drawing modifier there is no inside/outside to tell apart:
        // every padding is the node's own.
        if (!seenDraw && outerPad != null) {
            innerPad = outerPad
            outerPad = null
        }
        return v.copy(
            padding = innerPad?.let { PaddingInfo(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) }
                ?.takeIf { it.top > 0 || it.bottom > 0 || it.start > 0 || it.end > 0 },
            margin = outerPad?.let { MarginInfo(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) }
                ?.takeIf { it.top > 0 || it.bottom > 0 || it.start > 0 || it.end > 0 },
            clickOutsideMargin = clickOutsideMargin,
        )
    }

    // ---- pure helpers (unit-tested) ----

    /**
     * The family name for a FontFamily known only by its class. FontFamily.Default is a
     * DefaultFontFamily, which Compose renders through the same platform default as no
     * family, so it reports the same "sans-serif".
     */
    fun classFamilyName(simpleName: String): String =
        if (simpleName == "DefaultFontFamily") "sans-serif" else simpleName.lowercase()

    /**
     * Logical (start, end) for a padding stored as [start]/[end]. An `absolutePadding` stores
     * left/right there with [rtlAware] false, so in an [rtl] layout the two sides swap.
     */
    fun logicalSides(start: Float, end: Float, rtlAware: Boolean, rtl: Boolean): Pair<Float, Float> =
        if (!rtlAware && rtl) Pair(end, start) else Pair(start, end)

    /**
     * Whether a shadow with these ambient and spot colors (ARGB, null when unread) shows.
     * Their alpha is the shadow's opacity, so two fully transparent colors draw nothing; an
     * unread color is assumed visible (Compose's default is black).
     */
    fun shadowColorsVisible(ambientArgb: Int?, spotArgb: Int?): Boolean {
        fun visible(argb: Int?) = argb == null || (argb ushr 24) != 0
        return visible(ambientArgb) || visible(spotArgb)
    }

    fun weightName(weight: Int): String =
        when {
            weight < 200 -> "thin"
            weight < 300 -> "extralight"
            weight < 400 -> "light"
            weight < 500 -> "regular"
            weight < 600 -> "medium"
            weight < 700 -> "semibold"
            weight < 800 -> "bold"
            weight < 900 -> "extrabold"
            else -> "black"
        }

    /** Compose packs an Offset as two floats: x in the high 32 bits, y in the low. */
    fun unpackOffset(packed: Long): Pair<Float, Float> =
        Pair(
            java.lang.Float.intBitsToFloat((packed ushr 32).toInt()),
            java.lang.Float.intBitsToFloat((packed and 0xFFFFFFFFL).toInt()),
        )

    /** False when every stop is fully transparent (#00RRGGBB): such a gradient draws nothing. */
    fun gradientVisible(colors: List<String>): Boolean =
        colors.any { !(it.length == 9 && it.startsWith("#00")) }

    /**
     * Whether a border with brush class [brushClass] puts pixels on screen. A solid brush draws
     * only with a color left ([solidColor]); a decoded gradient only when a stop is visible
     * ([gradientStops], null when not a readable gradient). Any other brush (a ShaderBrush, a
     * gradient whose stops failed to read) is assumed to draw.
     */
    fun borderDraws(brushClass: String?, solidColor: String?, gradientStops: List<String>?): Boolean = when {
        brushClass == "SolidColor" -> solidColor != null
        gradientStops != null -> gradientVisible(gradientStops)
        else -> true
    }

    /** Compose packs a Size like an Offset: width in the high 32 bits, height in the low. */
    fun packSize(width: Float, height: Float): Long =
        (java.lang.Float.floatToRawIntBits(width).toLong() shl 32) or
            (java.lang.Float.floatToRawIntBits(height).toLong() and 0xFFFFFFFFL)

    /** Names match GradientDrawable.Orientation, lowercased, as the View path reports them. */
    fun gradientOrientation(start: Pair<Float, Float>, end: Pair<Float, Float>): String? {
        val dx = finiteDelta(start.first, end.first)
        val dy = finiteDelta(start.second, end.second)
        if (dx == 0f && dy == 0f) return null
        val ax = kotlin.math.abs(dx)
        val ay = kotlin.math.abs(dy)
        return when {
            ax > ay * 2 -> if (dx > 0) "left_right" else "right_left"
            ay > ax * 2 -> if (dy > 0) "top_bottom" else "bottom_top"
            dx > 0 && dy > 0 -> "tl_br"
            dx > 0 -> "bl_tr"
            dy > 0 -> "tr_bl"
            else -> "br_tl"
        }
    }

    /** Offset.Infinite stands for "the far edge": treat it as a unit step in its direction. */
    private fun finiteDelta(a: Float, b: Float): Float =
        when {
            a.isNaN() || b.isNaN() -> 0f
            a.isInfinite() && b.isInfinite() -> 0f
            b.isInfinite() -> if (b > 0) 1f else -1f
            a.isInfinite() -> if (a > 0) -1f else 1f
            else -> b - a
        }

    /**
     * A drawn color's hex, or null when it is fully transparent and so draws nothing.
     * Gradient stops pass [keepTransparent]: a transparent stop still shapes the fade.
     */
    fun argbHex(argb: Int, keepTransparent: Boolean): String? =
        if (!keepTransparent && (argb ushr 24) == 0) null else argbToHex(argb)

    /**
     * Border width in dp as Compose draws it. BorderModifierNode strokes
     * `min(ceil(width.toPx()), ceil(size.minDimension / 2))` px, where `Dp.Hairline` (stored as
     * 0) is one physical pixel: a fractional width rounds up to whole pixels, and one wider than
     * half the smaller side is clamped to it. [size] is the size the modifier draws at in px (the
     * measured size, null when unknown: no clamp), and with an empty side no border is drawn.
     * Null when the width can't be read or nothing is drawn.
     */
    fun borderWidthDp(width: Float?, density: Float, size: Pair<Int, Int>?): Float? {
        if (width == null || !width.isFinite() || width < 0f) return null
        // With no density there are no pixels to round to: the declared width, or none for a hairline.
        if (!density.isFinite() || density <= 0f) return width.takeIf { it > 0f }
        val minSide = size?.let { minOf(it.first, it.second) }
        if (minSide != null && minSide <= 0) return null
        val requestedPx = if (width == 0f) 1f else kotlin.math.ceil(width * density)
        val px = if (minSide == null) requestedPx else minOf(requestedPx, kotlin.math.ceil(minSide / 2f))
        return px / density
    }

    fun argbToHex(color: Int): String {
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return if (a == 255) String.format("#%02X%02X%02X", r, g, b) else String.format("#%02X%02X%02X%02X", a, r, g, b)
    }

    /**
     * Alpha [alpha] (0..1) applied on top of [argb]'s own alpha, as Compose multiplies a
     * brush's or painter's alpha into what it draws.
     */
    fun scaleAlpha(argb: Int, alpha: Float): Int {
        if (alpha >= 1f || alpha.isNaN()) return argb
        val a = ((argb ushr 24) * alpha.coerceAtLeast(0f) + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0x00FFFFFF)
    }

    /** Compose's default font size: TextStyle.Default leaves it unspecified and text draws at 14sp. */
    const val DEFAULT_FONT_SIZE_SP = 14f

    /**
     * Font size in sp from the reflected TextUnit value: null when it could not be read,
     * Compose's default when it is unspecified (NaN), the value when it is a real size.
     */
    fun fontSizeSp(raw: Float?): Float? = when {
        raw == null -> null
        raw.isNaN() -> DEFAULT_FONT_SIZE_SP
        raw.isFinite() && raw > 0f -> raw
        else -> null
    }

    // A TextUnit packs its unit type in bits 32..39 and the value's float bits in the low word.
    private const val TEXT_UNIT_MASK = 0xFFL shl 32
    const val TEXT_UNIT_SP = 0x1L shl 32
    const val TEXT_UNIT_EM = 0x2L shl 32

    /** The value of a packed TextUnit (NaN for TextUnit.Unspecified). */
    fun textUnitValue(packed: Long): Float = java.lang.Float.intBitsToFloat(packed.toInt())

    /**
     * A TextStyle's font size in sp: Compose's default when unspecified, the value when it is in
     * sp, and null when it can't be read or is in em. An em size at the style level scales the
     * platform paint's own text size, not a size this reader can name.
     */
    fun styleFontSizeSp(packed: Long?): Float? = when {
        packed == null -> null
        (packed and TEXT_UNIT_MASK) == TEXT_UNIT_SP -> fontSizeSp(textUnitValue(packed))
        (packed and TEXT_UNIT_MASK) == 0L -> DEFAULT_FONT_SIZE_SP
        else -> null
    }

    /**
     * What a span's font size does to the size under it, both in dp: an sp size replaces it
     * (Compose sets an AbsoluteSizeSpan) and an em size multiplies it (a RelativeSizeSpan, which
     * compounds with another em span over the same text). Null when the span leaves it alone.
     */
    fun fontSizeEffect(packed: Long?, spToDp: (Float) -> Float): ((Float?) -> Float?)? {
        if (packed == null) return null
        val value = textUnitValue(packed)
        if (!value.isFinite() || value <= 0f) return null
        return when (packed and TEXT_UNIT_MASK) {
            TEXT_UNIT_SP -> { _ -> spToDp(value) }
            TEXT_UNIT_EM -> { size -> size?.times(value) }
            else -> null
        }
    }

    /** What a span sets for a text attribute it replaces outright; null when it leaves it alone. */
    fun <T : Any> replaceEffect(value: T?): ((T?) -> T?)? = value?.let { v -> { _ -> v } }

    /** One span style applied over the characters [start, end) of a text. */
    class SpanEffect<T>(val start: Int, val end: Int, val effect: ((T) -> T)?)

    /** An attribute's value over a whole text: one value throughout, or mixed between spans. */
    sealed class OverText<out T> {
        data class Uniform<out T>(val value: T) : OverText<T>()
        object Mixed : OverText<Nothing>()

        /** The value when it is the same over the whole text; null when it is mixed. */
        fun valueOrNull(): T? = (this as? Uniform)?.value
    }

    /**
     * An attribute's value over the whole of a [length]-char text, with [spans] applied in order
     * over [base] as Compose's paragraph applies span styles: a later span wins where two overlap,
     * and a span Compose skips (empty, or reaching outside the text) is skipped. A span over the
     * whole text sets the value everywhere; when spans leave two parts of the text with different
     * values, the attribute is [OverText.Mixed] and has no one value to report.
     */
    fun <T> overWholeText(base: T, length: Int, spans: List<SpanEffect<T>>): OverText<T> {
        val applied = spans.filter { it.effect != null && it.start >= 0 && it.start < length && it.end > it.start && it.end <= length }
        if (applied.isEmpty()) return OverText.Uniform(base)
        // Between two consecutive span boundaries every character has the same spans over it.
        val cuts = (applied.flatMap { listOf(it.start, it.end) } + 0 + length).distinct().sorted()
        var result: OverText.Uniform<T>? = null
        for (i in 0 until cuts.size - 1) {
            val from = cuts[i]
            val to = cuts[i + 1]
            var value = base
            for (span in applied) {
                if (span.start <= from && span.end >= to) value = span.effect!!(value)
            }
            val seen = result
            if (seen == null) {
                result = OverText.Uniform(value)
            } else if (seen.value != value) {
                return OverText.Mixed
            }
        }
        return result ?: OverText.Uniform(base)
    }

    /** What text is drawn with: one color at an alpha, or a shader brush with its alpha. */
    sealed class TextForeground {
        data class Solid(val packed: Long, val alpha: Float = 1f) : TextForeground()
        data class Shader(val brush: Any, val alpha: Float) : TextForeground()
    }

    /**
     * A text brush as Compose draws it. Only a ShaderBrush (a gradient, or any other shader) sets
     * the paint's shader. A SolidColor is a color: AndroidTextPaint.setBrush sets the paint's
     * color from it at [alpha]. Null for a SolidColor whose color can't be read: a color, but an
     * unknown one.
     */
    fun brushForeground(brush: Any, alpha: Float): TextForeground? =
        if (brush.javaClass.simpleName == "SolidColor") {
            (field(brush, "value") as? Long)?.takeIf { it != UNSPECIFIED_COLOR }?.let { TextForeground.Solid(it, alpha) }
        } else {
            TextForeground.Shader(brush, alpha)
        }

    /**
     * What a span's color or brush does to the text's foreground. A shader brush span sets the
     * paint's shader, which replaces whatever was under it. A color span sets the paint's color,
     * which a shader under it still draws over, so it changes only a solid (or unread)
     * foreground. Compose applies a span's SolidColor brush as such a color span, with the
     * brush's own color (the span alpha is not applied to it). Null when the span sets neither.
     */
    fun foregroundEffect(color: Long?, brush: Any?, alpha: Float): ((TextForeground?) -> TextForeground?)? {
        if (brush != null) {
            val fg = brushForeground(brush, alpha)
            if (fg is TextForeground.Shader) return { _ -> fg }
            return colorSpan((fg as? TextForeground.Solid)?.let { TextForeground.Solid(it.packed) })
        }
        if (color == null || color == UNSPECIFIED_COLOR) return null
        return colorSpan(TextForeground.Solid(color))
    }

    /** A color span over a foreground: a shader under it keeps drawing, anything else takes [solid] (null: unread). */
    private fun colorSpan(solid: TextForeground.Solid?): (TextForeground?) -> TextForeground? =
        { under -> under as? TextForeground.Shader ?: solid }

    /**
     * A text's span styles: its length, and each span's range and SpanStyle. A plain String, or
     * an AnnotatedString whose spans can't be read, has none.
     */
    class TextSpans(val length: Int, val spans: List<Triple<Int, Int, Any>>) {
        /** [base] with each span's effect (from [effectOf] on its SpanStyle) applied over the whole text. */
        fun <T> resolve(base: T, effectOf: (Any) -> ((T) -> T)?): OverText<T> =
            overWholeText(base, length, spans.map { (start, end, style) -> SpanEffect(start, end, effectOf(style)) })
    }

    private fun textSpans(text: Any?): TextSpans {
        val string = invoke0(text, "getText") as? String ?: return TextSpans(0, emptyList())
        val ranges = invoke0(text, "getSpanStyles") as? List<*> ?: return TextSpans(string.length, emptyList())
        val spans = ranges.mapNotNull { range ->
            val start = invoke0(range, "getStart") as? Int ?: return@mapNotNull null
            val end = invoke0(range, "getEnd") as? Int ?: return@mapNotNull null
            val style = invoke0(range, "getItem") ?: return@mapNotNull null
            Triple(start, end, style)
        }
        return TextSpans(string.length, spans)
    }

    // ---- reflection ----

    /** [top, bottom, start, end] in dp from a PaddingValues, or null when it can't be read. */
    private fun paddingValues(pv: Any?, layoutNode: Any): FloatArray? {
        if (pv == null) return null
        // PaddingValuesImpl keeps the four Dp values as fields.
        val top = field(pv, "top") as? Float
        val bottom = field(pv, "bottom") as? Float
        val start = field(pv, "start") as? Float
        val end = field(pv, "end") as? Float
        if (top != null && bottom != null && start != null && end != null) {
            return floatArrayOf(dp(top), dp(bottom), dp(start), dp(end))
        }
        // Any other PaddingValues (window insets, a custom one) computes them on demand.
        return try {
            val t = invokePrefix(pv, "calculateTopPadding") as? Float ?: return null
            val b = invokePrefix(pv, "calculateBottomPadding") as? Float ?: return null
            val direction = invoke0(layoutNode, "getLayoutDirection") ?: return null
            val rtl = (direction as? Enum<*>)?.name == "Rtl"
            fun side(name: String): Float? = pv.javaClass.methods
                .firstOrNull { (it.name == name || it.name.startsWith("$name-")) && it.parameterCount == 1 }
                ?.invoke(pv, direction) as? Float
            val left = side("calculateLeftPadding") ?: return null
            val right = side("calculateRightPadding") ?: return null
            floatArrayOf(dp(t), dp(b), dp(if (rtl) right else left), dp(if (rtl) left else right))
        } catch (_: Throwable) {
            null
        }
    }

    /** A TextUnit in sp, packed as Compose packs it: the unit type in the high 32 bits, the value's bits low. */
    fun packSp(value: Float): Long =
        0x1_0000_0000L or (java.lang.Float.floatToRawIntBits(value).toLong() and 0xFFFFFFFFL)

    /**
     * The size in dp Compose renders [sizeSp] at, through the node's Density (its
     * `TextUnit.toDp()` applies the FontScaleConverter). Null when that isn't reachable.
     */
    private fun spToDp(composeDensity: Any?, sizeSp: Float): Float? {
        if (composeDensity == null) return null
        return try {
            composeDensity.javaClass.methods
                .firstOrNull {
                    it.name.startsWith("toDp") && it.parameterCount == 1 &&
                        it.parameterTypes[0] == java.lang.Long.TYPE && it.returnType == java.lang.Float.TYPE
                }
                ?.invoke(composeDensity, packSp(sizeSp)) as? Float
        } catch (_: Throwable) {
            null
        }?.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * The font the text draws with: the style's, with its AnnotatedString's span styles applied.
     * Null when the size can't be read, or when spans give parts of the text a different family,
     * weight or size (a mixed font has no one value to report).
     */
    private fun font(style: Any?, spans: TextSpans, composeDensity: Any?, fontScale: Float, resources: Resources?): FontInfo? {
        if (style == null) return null
        fun sizeDp(sp: Float): Float = spToDp(composeDensity, sp) ?: sp * fontScale
        val baseSize = styleFontSizeSp(invokePrefix(style, "getFontSize") as? Long)?.let { sizeDp(it) }
        val size = spans.resolve(baseSize) { fontSizeEffect(invokePrefix(it, "getFontSize") as? Long, ::sizeDp) }
            .valueOrNull() ?: return null
        val weightOver = spans.resolve(fontWeight(style)) { replaceEffect(fontWeight(it)) }
        val familyOver = spans.resolve(invoke0(style, "getFontFamily")) { replaceEffect(invoke0(it, "getFontFamily")) }
        if (weightOver is OverText.Mixed || familyOver is OverText.Mixed) return null
        val family = familyOver.valueOrNull()
        var weight = weightOver.valueOrNull()
        var familyName = "sans-serif"
        when (family?.javaClass?.simpleName) {
            null -> {}
            "GenericFontFamily" -> familyName = (invoke0(family, "getName") as? String) ?: familyName
            "FontListFontFamily" -> {
                val fonts = invoke0(family, "getFonts") as? List<*>
                val target = weight ?: 400
                val font = fonts?.minByOrNull {
                    kotlin.math.abs(((invoke0(invoke0(it, "getWeight"), "getWeight") as? Int) ?: 400) - target)
                }
                val resId = invoke0(font, "getResId") as? Int
                if (resId != null && resources != null) {
                    try {
                        familyName = cleanFamily(resources.getResourceEntryName(resId))
                    } catch (_: Throwable) {}
                }
                if (weight == null) weight = invoke0(invoke0(font, "getWeight"), "getWeight") as? Int
            }
            else -> familyName = classFamilyName(family.javaClass.simpleName)
        }
        return FontInfo(family = familyName, weight = weightName(weight ?: 400), size = size)
    }

    /** A TextStyle's or SpanStyle's font weight; null when it sets none. */
    private fun fontWeight(style: Any?): Int? = invoke0(invoke0(style, "getFontWeight"), "getWeight") as? Int

    private fun cleanFamily(raw: String): String {
        var name = raw.lowercase()
        if (name.endsWith("_italic")) name = name.removeSuffix("_italic")
        for (suffix in listOf(
            "_extra_bold", "_extrabold", "_semi_bold", "_semibold", "_bold", "_medium",
            "_regular", "_extra_light", "_extralight", "_light", "_thin", "_black",
        )) {
            if (name.endsWith(suffix)) return name.removeSuffix(suffix)
        }
        return name
    }

    /** A style's brush alpha: what Compose multiplies into a brush it draws text with. */
    private fun brushAlpha(style: Any): Float = (invokePrefix(style, "getAlpha") as? Float)?.takeIf { it.isFinite() } ?: 1f

    /**
     * The one color the text draws with, or null when there is none to report: it can't be
     * read, it is a gradient or shader (no single color, and not black), or spans give parts of
     * the text different colors.
     */
    private fun textColorHex(producer: Any?, style: Any?, spans: TextSpans, anchor: Any, alpha: Float): String? {
        // A TextStyle brush wins over the color producer and the style color, as in
        // Compose's text draw; without one the paint takes the producer, the style's color,
        // then black. Span colors and brushes then draw over that. A solid brush is drawn as
        // its color, so a color span over it replaces it.
        val styleBrush = style?.let { invokePrefix(it, "getBrush") }
        val base = if (styleBrush != null) {
            brushForeground(styleBrush, brushAlpha(style))
        } else {
            val stylePacked = style?.let { invokePrefix(it, "getColor") as? Long }
            textColorPacked(producer != null, producerPacked(producer), stylePacked)?.let { TextForeground.Solid(it) }
        }
        val foreground = spans.resolve<TextForeground?>(base) {
            foregroundEffect(invokePrefix(it, "getColor") as? Long, invokePrefix(it, "getBrush"), brushAlpha(it))
        }.valueOrNull()
        return when (foreground) {
            is TextForeground.Solid -> colorHex(anchor, foreground.packed, alpha = alpha * foreground.alpha)
            // A shader has no one color (a solid brush is a Solid, above).
            is TextForeground.Shader, null -> null
        }
    }

    /**
     * [v] with the font and text color a laid-out text draws with filled in where its modifier
     * chain gave none. A text field (BasicTextField, and the Material fields built on it) draws
     * its text through its own text-field modifiers, with no text element in the chain to read,
     * but its semantics expose the layout (the GetTextLayoutResult action's TextLayoutResult),
     * whose input carries the style the text was laid out and is drawn with (the field's
     * textStyle, its color included) and the text itself, spans and all. [layoutResult] is that
     * TextLayoutResult and [alpha] the alpha the node draws at. A font or color the chain gave
     * is kept.
     */
    fun withLayoutText(
        v: ComposeDrawInfo,
        layoutResult: Any?,
        composeDensity: Any?,
        fontScale: Float,
        resources: Resources?,
        alpha: Float,
    ): ComposeDrawInfo {
        if (layoutResult == null || (v.font != null && v.textColor != null)) return v
        val input = invoke0(layoutResult, "getLayoutInput") ?: return v
        val style = invoke0(input, "getStyle") ?: return v
        val spans = textSpans(invoke0(input, "getText"))
        return v.copy(
            font = v.font ?: font(style, spans, composeDensity, fontScale, resources),
            textColor = v.textColor ?: textColorHex(null, style, spans, layoutResult, alpha),
        )
    }

    private class LayerProps(
        val alpha: Float?,
        val shadowPx: Float?,
        val clip: Boolean,
        val shape: Any?,
        val shadowColorsVisible: Boolean,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
    ) {
        /** Scaled to 0 on either axis ([layerCollapsed]). */
        val collapsed: Boolean get() = layerCollapsed(scaleX, scaleY)
    }

    /**
     * Whether a graphics layer at [scaleX] × [scaleY] (null when unread) draws nothing: scaled to
     * 0 on either axis, where its content has no area and the RenderNode is skipped (HWUI's
     * RenderNode::nothingToDraw is true at scaleX or scaleY 0, as at alpha 0).
     */
    fun layerCollapsed(scaleX: Float?, scaleY: Float?): Boolean =
        (scaleX != null && scaleX == 0f) || (scaleY != null && scaleY == 0f)

    /**
     * The layer properties a `graphicsLayer { … }` block sets, read by running the block on a
     * fresh ReusableGraphicsLayerScope with the screen density (so `8.dp.toPx()` resolves as
     * it does on screen). Null when any of that is unavailable in this Compose release.
     *
     * The block runs inside a read-only Compose snapshot, as content blocks do
     * ([probeContentDraw], [runInReadOnlySnapshot]): it can read app state but not change it.
     * A block that writes state throws there, and like one that throws for any other reason, or
     * one that can't be run in a read-only snapshot at all, it is an unreadable layer: null,
     * so the caller keeps the content under it and reports no alpha, shadow or shape for it.
     * The scope it sets values on is the fresh one made here, never the node's own layer.
     */
    private fun blockLayer(element: Any, composeDensity: Any?, density: Float, fontScale: Float, layerSize: Pair<Int, Int>?): LayerProps? {
        val block = (field(element, "block") ?: field(element, "layerBlock")) as? Function1<*, *> ?: return null
        return try {
            val loader = element.javaClass.classLoader
            val scopeClass = Class.forName("androidx.compose.ui.graphics.ReusableGraphicsLayerScope", false, loader)
            val scope = scopeClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
            // The node's own Density (with its font-scale converter) when reachable, else
            // one built from the screen density and font scale.
            val densityObj = composeDensity ?: Class.forName("androidx.compose.ui.unit.DensityKt", false, loader).methods
                .firstOrNull { it.name == "Density" && it.parameterCount == 2 }
                ?.invoke(null, density, fontScale)
            if (densityObj != null) {
                scopeClass.methods
                    .firstOrNull { it.name.startsWith("setGraphicsDensity") && it.parameterCount == 1 }
                    ?.invoke(scope, densityObj)
            }
            // Compose sets the layer's measured size before running the block; a block that
            // reads `size` must see the same value here: untransformed and unclipped.
            if (layerSize != null) {
                scopeClass.methods
                    .firstOrNull { it.name.startsWith("setSize") && it.parameterCount == 1 }
                    ?.invoke(scope, packSize(layerSize.first.toFloat(), layerSize.second.toFloat()))
            }
            @Suppress("UNCHECKED_CAST")
            val fn = block as Function1<Any?, Any?>
            if (!runInReadOnlySnapshot(loader) { fn.invoke(scope) }) return null
            LayerProps(
                alpha = invokePrefix(scope, "getAlpha") as? Float,
                shadowPx = invokePrefix(scope, "getShadowElevation") as? Float,
                clip = invokePrefix(scope, "getClip") as? Boolean ?: false,
                shape = invokePrefix(scope, "getShape"),
                shadowColorsVisible = shadowColorsVisible(
                    argb(scope, field(scope, "ambientShadowColor") as? Long),
                    argb(scope, field(scope, "spotShadowColor") as? Long),
                ),
                scaleX = invokePrefix(scope, "getScaleX") as? Float,
                scaleY = invokePrefix(scope, "getScaleY") as? Float,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun producerPacked(producer: Any?): Long? {
        if (producer == null) return null
        return try {
            val m = producer.javaClass.methods.firstOrNull {
                it.name.startsWith("invoke") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE
            }
            m?.invoke(producer) as? Long
        } catch (_: Throwable) {
            null
        }
    }

    /** `Color.Black` packed: an sRGB color keeps its ARGB in the high 32 bits. */
    const val BLACK_PACKED: Long = -0x100000000000000L

    /**
     * The color a BasicText draws with: the color producer, then the style's color, then
     * black, which is what Compose falls back to when both are unspecified. Black is only
     * reported when every source was actually read; an unreadable source yields null.
     */
    fun textColorPacked(hasProducer: Boolean, producer: Long?, style: Long?): Long? {
        if (producer != null && producer != UNSPECIFIED_COLOR) return producer
        if (style != null && style != UNSPECIFIED_COLOR) return style
        val producerUnspecified = !hasProducer || producer == UNSPECIFIED_COLOR
        return if (producerUnspecified && style == UNSPECIFIED_COLOR) BLACK_PACKED else null
    }

    private fun colorFilterHex(filter: Any?, anchor: Any, alpha: Float): String? {
        if (filter == null) return null
        return (field(filter, "color") as? Long)?.let { colorHex(anchor, it, alpha = alpha) }
    }

    /**
     * Whether a painter's color filter leaves nothing of it on screen: a BlendModeColorFilter (what
     * ColorFilter.tint makes, SrcIn by default) whose color is fully transparent, in a mode that
     * replaces the painter's pixels with it ([tintHides]). Any other filter, or one whose color or
     * mode can't be read, keeps the painter.
     */
    private fun filterHides(filter: Any?, anchor: Any): Boolean {
        if (filter?.javaClass?.simpleName != "BlendModeColorFilter") return false
        val color = argb(anchor, field(filter, "color") as? Long) ?: return false
        if ((color ushr 24) != 0) return false
        val mode = (field(filter, "blendMode") as? Int)?.let { blendModeName(anchor, it) } ?: return false
        return tintHides(color, mode)
    }

    /**
     * A Compose BlendMode's name (SrcIn, Src, …): the BlendMode companion constant whose value is
     * [value], its getter's name without "get" and the value-class mangling (getSrcIn-0nO6VwU).
     * Null when the class or no constant can be read.
     */
    private fun blendModeName(anchor: Any, value: Int): String? = try {
        val companion = Class.forName("androidx.compose.ui.graphics.BlendMode", false, anchor.javaClass.classLoader)
            .getField("Companion").get(null)
        companion?.javaClass?.methods?.firstOrNull { m ->
            m.parameterCount == 0 && m.returnType == Integer.TYPE && m.name.startsWith("get") && m.invoke(companion) == value
        }?.name?.removePrefix("get")?.substringBefore('-')
    } catch (_: Throwable) {
        null
    }

    /** A blend mode in one spelling for both paths: BlendMode and PorterDuff's SRC_IN and Compose's SrcIn are "srcin". */
    private fun modeKey(mode: String?): String = (mode ?: "SrcIn").replace("_", "").lowercase()

    private val REPLACING_MODES = setOf("srcin", "src")

    /**
     * What a tint of color [tint] (ARGB) in blend [mode] turns a pixel of color [src] into
     * (Porter-Duff, the tint as the source over the pixel): SrcIn (the default, null, for a
     * Drawable tint and for ColorFilter.tint alike) keeps the tint's color at the product of the
     * two alphas, Src is the tint itself, and SrcAtop keeps the pixel's alpha and blends the
     * tint over its color by the tint's alpha (a transparent tint leaves it as it is). Null for
     * any other mode, which isn't modelled.
     */
    fun tinted(src: Int, tint: Int, mode: String?): Int? = when (modeKey(mode)) {
        "srcin" -> ((((tint ushr 24) * (src ushr 24) + 127) / 255) shl 24) or (tint and 0xFFFFFF)
        "src" -> tint
        "srcatop" -> {
            val ta = tint ushr 24
            fun channel(shift: Int) = (((tint shr shift) and 0xFF) * ta + ((src shr shift) and 0xFF) * (255 - ta) + 127) / 255
            (src and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
        else -> null
    }

    /**
     * Whether a tint leaves nothing on screen: a fully transparent [tint] in a mode that replaces
     * every pixel with it ([tinted]'s modes; null is the default, SrcIn). The one rule for image
     * tints on both paths: an ImageView's tint and a Compose painter's color filter.
     */
    fun tintHides(tint: Int, mode: String?): Boolean = (tint ushr 24) == 0 && modeKey(mode) in REPLACING_MODES

    private fun solidBrushHex(brush: Any?, anchor: Any, alpha: Float = 1f): String? {
        if (brush?.javaClass?.simpleName != "SolidColor") return null
        return (field(brush, "value") as? Long)?.let { colorHex(anchor, it, alpha = alpha) }
    }

    private fun brushType(brush: Any?): String? = when (brush?.javaClass?.simpleName) {
        "LinearGradient" -> "linear"
        "RadialGradient" -> "radial"
        "SweepGradient" -> "sweep"
        else -> null
    }

    private fun gradient(brush: Any?, anchor: Any, alpha: Float = 1f): GradientInfo? {
        val type = brushType(brush) ?: return null
        val colors = (field(brush, "colors") as? List<*>)?.mapNotNull { c ->
            val packed = c?.javaClass?.methods?.firstOrNull { it.name == "unbox-impl" }?.invoke(c) as? Long
            // A transparent stop is still a stop: Compose hands every color to the shader,
            // so a fade to Color.Transparent keeps its #00000000 end.
            packed?.let { colorHex(anchor, it, keepTransparent = true, alpha = alpha) }
        } ?: return null
        if (colors.size < 2) return null
        val orientation = if (type == "linear") {
            val s = field(brush, "start") as? Long
            val e = field(brush, "end") as? Long
            if (s != null && e != null) gradientOrientation(unpackOffset(s), unpackOffset(e)) else null
        } else {
            null
        }
        return GradientInfo(type = type, colors = colors, orientation = orientation)
    }

    /**
     * Shapes whose corners are arcs, so a corner size is a radius. A CutCornerShape (or
     * AbsoluteCutCornerShape) keeps the same corner sizes as bevel lengths, and any other shape's
     * corners are unknown: neither has a corner radius.
     */
    val ROUNDED_SHAPES = setOf("RoundedCornerShape", "AbsoluteRoundedCornerShape")

    /**
     * A corner size in px, as Compose's CornerSize.toPx resolves it against the shape's local
     * [size] (px): a Dp through the density, px as is, a percent of the smaller side. Null when
     * the kind is unknown or a percent has no size to resolve against.
     */
    fun cornerSizePx(kind: String?, value: Float?, density: Float, size: Pair<Int, Int>?): Float? {
        if (value == null || !value.isFinite()) return null
        val px = when (kind) {
            "DpCornerSize" -> value * density
            "PxCornerSize" -> value
            "PercentCornerSize" -> size?.let { value / 100f * minOf(it.first, it.second) } ?: return null
            else -> return null
        }
        return px.takeIf { it.isFinite() && it >= 0f }
    }

    /**
     * The four corners of a rounded shape as Compose draws them at [size] (px, width to height),
     * in CornerBasedShape's order: top-start, top-end, bottom-end, bottom-start. An unknown
     * corner (null) counts as 0. Two steps, both on the path every rounded shape draws through:
     *
     * 1. CornerBasedShape.createOutline (compose foundation, CornerBasedShape.kt) scales the start
     *    pair (topStart + bottomStart) and the end pair (topEnd + bottomEnd), each on its own,
     *    down to the shape's smaller side, so a RoundedCornerShape(100.dp) on a 48dp-high button
     *    draws 24dp corners.
     * 2. RoundedCornerShape.createOutline puts those corners in a RoundRect, which draws through
     *    AndroidPath.addRoundRect into android.graphics.Path.addRoundRect. There Skia's
     *    SkRRect::scaleRadii scales every radius by ONE factor: the smallest side / (the two
     *    radii on that side) over all four sides, the CSS rule for overlapping corners (CSS
     *    Backgrounds 3, 5.5 "Overlapping Curves"). Compose's own RoundRect.scaledRadiiRect
     *    (compose ui-geometry, RoundRect.kt) takes the same factor, in the same side order, for
     *    hit testing. After step 1 the start and end sides fit, so here the top and bottom sides
     *    set it: RoundedCornerShape(topStart = 80px, topEnd = 80px) on a 100px square draws both
     *    corners at 50px, and a bottom side too short for its corners shrinks the top ones too.
     *
     * With no size there is nothing to scale against, and the corners come back as declared.
     */
    fun drawnCornersPx(
        topStart: Float?,
        topEnd: Float?,
        bottomEnd: Float?,
        bottomStart: Float?,
        size: Pair<Int, Int>?,
    ): FloatArray {
        var ts = topStart ?: 0f
        var te = topEnd ?: 0f
        var be = bottomEnd ?: 0f
        var bs = bottomStart ?: 0f
        if (size == null) return floatArrayOf(ts, te, be, bs)
        val width = size.first.toFloat()
        val height = size.second.toFloat()
        // Step 1, CornerBasedShape.createOutline: each pair on a vertical side, against the
        // smaller side of the shape.
        val minDimension = minOf(width, height)
        if (ts + bs > minDimension) {
            val scale = minDimension / (ts + bs)
            ts *= scale
            bs *= scale
        }
        if (te + be > minDimension) {
            val scale = minDimension / (te + be)
            te *= scale
            be *= scale
        }
        // Step 2, RoundRect.scaledRadiiRect: one factor over the four sides. Which corner sits
        // left or right depends on the layout direction, but the four sums do not.
        var scale = 1f
        scale = minRadius(scale, bs, ts, height)
        scale = minRadius(scale, ts, te, width)
        scale = minRadius(scale, te, be, height)
        scale = minRadius(scale, be, bs, width)
        return floatArrayOf(ts * scale, te * scale, be * scale, bs * scale)
    }

    /**
     * RoundRect.minRadius: the smaller of [min] and what fits [radius1] + [radius2] in [limit].
     * SkRRect::scaleRadii takes the same factor, so the View path scales a GradientDrawable's
     * radii with it too.
     */
    fun minRadius(min: Float, radius1: Float, radius2: Float, limit: Float): Float {
        val sum = radius1 + radius2
        return if (sum > limit && sum != 0f) minOf(min, limit / sum) else min
    }

    /**
     * Radius in dp of the top-start corner, the same corner the View path reports, for a rounded
     * shape drawn at [size]: the measured size of the modifier's coordinator, the local size
     * Compose resolves the shape against (before any layer transform or ancestor clip, which
     * window bounds include). Null for any shape that is not rounded.
     */
    private fun cornerRadius(shape: Any?, density: Float, size: Pair<Int, Int>?): Float? {
        if (shape == null || shape.javaClass.simpleName !in ROUNDED_SHAPES) return null
        fun cornerPx(name: String): Float? {
            val corner = field(shape, name) ?: return null
            val kind = corner.javaClass.simpleName
            val value = field(corner, if (kind == "PercentCornerSize") "percent" else "size") as? Float
            return cornerSizePx(kind, value, density, size)
        }
        val topStart = cornerPx("topStart") ?: return null
        val drawn = drawnCornersPx(topStart, cornerPx("topEnd"), cornerPx("bottomEnd"), cornerPx("bottomStart"), size)
        return (drawn[0] / density).takeIf { it.isFinite() && it > 0f }
    }

    private fun painterType(painter: Any?): String? {
        val name = painter?.javaClass?.simpleName ?: return null
        return when {
            name == "ColorPainter" -> null
            name == "VectorPainter" -> "vector"
            name == "BitmapPainter" -> "raster"
            else -> "loaded"
        }
    }

    /**
     * The measured size of the coordinator a modifier sits on (LayoutCoordinates.size): before
     * a layer's scale or rotation and any ancestor clip, which window bounds include. It is the
     * size Compose gives a `graphicsLayer { }` block and resolves a modifier's shape against.
     * Falls back to the node's bounds when the coordinator can't be read.
     */
    private fun measuredSize(info: Any, nodeBounds: Bounds?): Pair<Int, Int>? {
        val packed = invokePrefix(invoke0(info, "getCoordinates"), "getSize") as? Long
        return packed?.let { unpackIntSize(it) }?.takeIf { it.first > 0 && it.second > 0 }
            ?: nodeBounds?.let { Pair(it.w, it.h) }
    }

    /** Compose packs an IntSize as two ints: width in the high 32 bits, height in the low. */
    fun unpackIntSize(packed: Long): Pair<Int, Int> =
        Pair((packed ushr 32).toInt(), (packed and 0xFFFFFFFFL).toInt())

    /** The box a modifier draws in: the coordinator of the next layout modifier inward. */
    private fun ownBounds(info: Any): Bounds? = invoke0(info, "getCoordinates")?.let { boundsInWindow(it) }

    private fun modifierBounds(info: Any, nodeBounds: Bounds?): Bounds? {
        val b = ownBounds(info) ?: return null
        return if (b == nodeBounds) null else b
    }

    fun boundsInWindow(coordinates: Any): Bounds? =
        try {
            if (invoke0(coordinates, "isAttached") == false) {
                null
            } else {
                val kt = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt", false, coordinates.javaClass.classLoader)
                val m = kt.methods.firstOrNull { it.name == "boundsInWindow" && it.parameterCount == 1 }
                rectToBounds(m?.invoke(null, coordinates))
            }
        } catch (_: Throwable) {
            null
        }

    fun rectToBounds(rect: Any?): Bounds? {
        if (rect == null) return null
        return try {
            val left = invoke0(rect, "getLeft") as Float
            val top = invoke0(rect, "getTop") as Float
            val right = invoke0(rect, "getRight") as Float
            val bottom = invoke0(rect, "getBottom") as Float
            val w = (right - left).toInt()
            val h = (bottom - top).toInt()
            if (w <= 0 || h <= 0) null else Bounds(left.toInt(), top.toInt(), w, h)
        } catch (_: Throwable) {
            null
        }
    }

    /** A packed Compose Color as ARGB, or null when it can't be converted. */
    private fun argb(anchor: Any, packed: Long?): Int? {
        if (packed == null) return null
        return try {
            val kt = Class.forName("androidx.compose.ui.graphics.ColorKt", false, anchor.javaClass.classLoader)
            kt.methods.firstOrNull {
                it.name.startsWith("toArgb") && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
            }?.invoke(null, packed) as? Int
        } catch (_: Throwable) {
            null
        }
    }

    private fun colorHex(anchor: Any, packed: Long, keepTransparent: Boolean = false, alpha: Float = 1f): String? {
        if (packed == UNSPECIFIED_COLOR) return null
        return try {
            val kt = Class.forName("androidx.compose.ui.graphics.ColorKt", false, anchor.javaClass.classLoader)
            val m = kt.methods.firstOrNull {
                it.name.startsWith("toArgb") && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
            }
            (m?.invoke(null, packed) as? Int)?.let { argbHex(scaleAlpha(it, alpha), keepTransparent) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun dp(value: Any?): Float = (value as? Float)?.takeIf { it.isFinite() && it > 0f } ?: 0f

    private fun add(acc: FloatArray?, p: FloatArray): FloatArray =
        if (acc == null) p else FloatArray(4) { acc[it] + p[it] }

    private val fieldCache = HashMap<Pair<Class<*>, String>, java.lang.reflect.Field?>()

    fun field(obj: Any?, name: String): Any? {
        if (obj == null) return null
        val key = Pair(obj.javaClass, name)
        val f = synchronized(fieldCache) {
            fieldCache.getOrPut(key) {
                var c: Class<*>? = obj.javaClass
                var found: java.lang.reflect.Field? = null
                while (c != null && found == null) {
                    found = try {
                        c.getDeclaredField(name).apply { isAccessible = true }
                    } catch (_: Throwable) {
                        null
                    }
                    c = c.superclass
                }
                found
            }
        } ?: return null
        return try {
            f.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    fun invoke0(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            obj.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(obj)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * A getter that may be public (`getX`) or Kotlin-internal (`getX$ui`,
     * mangled with the module name) depending on the Compose release.
     */
    fun invokeInternal(obj: Any?, name: String): Any? {
        if (obj == null) return null
        return try {
            val methods = obj.javaClass.methods.filter { it.parameterCount == 0 }
            (methods.firstOrNull { it.name == name } ?: methods.firstOrNull { it.name.startsWith("$name$") })?.invoke(obj)
        } catch (_: Throwable) {
            null
        }
    }

    /** For Compose's name-mangled value-class getters (getFontSize-XSAIIZE, getColor-0d7_KjU). */
    fun invokePrefix(obj: Any?, prefix: String): Any? {
        if (obj == null) return null
        return try {
            obj.javaClass.methods
                .firstOrNull { (it.name == prefix || it.name.startsWith("$prefix-")) && it.parameterCount == 0 }
                ?.invoke(obj)
        } catch (_: Throwable) {
            null
        }
    }
}
