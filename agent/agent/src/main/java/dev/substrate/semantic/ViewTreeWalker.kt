package dev.substrate.semantic

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.VectorDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ViewTreeWalker {
    @Volatile
    var lastDebugLog: String = ""
        private set

    private val fontFingerprints = mutableMapOf<Long, String>()
    private val glyphHashCache = mutableMapOf<Int, Long>()

    fun initFontMap(context: android.content.Context) {
        fontFingerprints.clear()
        glyphHashCache.clear()
        try {
            val fontClass = Class.forName("${context.packageName}.R\$font")
            val fields = fontClass.declaredFields
            android.util.Log.i("SemanticAgent", "R.font class found with ${fields.size} fields")
            for (field in fields) {
                if (field.type != Int::class.javaPrimitiveType) continue
                try {
                    val resId = field.getInt(null)
                    val typeface =
                        if (Build.VERSION.SDK_INT >= 26) {
                            context.resources.getFont(resId)
                        } else {
                            null
                        }
                    if (typeface != null) {
                        val hash = computeGlyphHash(typeface)
                        fontFingerprints[hash] = field.name
                        android.util.Log.d("SemanticAgent", "font indexed: ${field.name} hash=$hash")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("SemanticAgent", "font ${field.name} load failed: ${e.message}")
                }
            }
            android.util.Log.i("SemanticAgent", "font fingerprints: ${fontFingerprints.size} fonts indexed")
        } catch (_: ClassNotFoundException) {
            android.util.Log.i("SemanticAgent", "no R.font class found — font fingerprinting disabled")
        } catch (e: Exception) {
            android.util.Log.e("SemanticAgent", "font init failed: ${e.message}", e)
        }
    }

    private fun computeGlyphHash(typeface: Typeface): Long {
        val paint =
            android.graphics.Paint().apply {
                this.typeface = typeface
                textSize = 48f
                isAntiAlias = false
            }
        val text = "HgQW"
        val w = paint.measureText(text).toInt().coerceAtLeast(1)
        val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        canvas.drawText(text, 0f, 48f, paint)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        bmp.recycle()
        var hash = -3750763034362895579L // 0xcbf29ce484222325
        for (p in pixels) {
            hash = hash xor p.toLong()
            hash *= 1099511628211L // 0x100000001b3
        }
        return hash
    }

    private fun getGlyphHash(typeface: Typeface): Long {
        val id = System.identityHashCode(typeface)
        return glyphHashCache.getOrPut(id) { computeGlyphHash(typeface) }
    }

    fun walk(activity: Activity, includeOffscreen: Boolean = false): SemanticSchema {
        val density = activity.resources.displayMetrics.density
        val decorView = activity.window.decorView
        val elements = mutableListOf<SemanticElement>()
        val log = StringBuilder()
        imageOutputDir = java.io.File(activity.cacheDir, "vdb-images").apply { mkdirs() }
        var z = 0

        fun nextZ(): Int = z++

        fun walkView(
            view: View,
            parentExternal: Boolean,
            parentHidden: Boolean,
            // The product of every ancestor's alpha: RenderNode fades the whole subtree by it.
            parentAlpha: Float = 1f,
        ) {
            val resId = extractResourceId(view)
            val tag = resId ?: view.javaClass.simpleName

            if (view.visibility == View.GONE) {
                log.appendLine("SKIP $tag reason=gone")
                return
            }

            val isGroup = view is ViewGroup
            val childCount = if (isGroup) (view as ViewGroup).childCount else 0
            val effectivelyHidden =
                parentHidden ||
                    (view.visibility == View.INVISIBLE) ||
                    (view.alpha == 0f)

            if (effectivelyHidden && childCount == 0) {
                log.appendLine("SKIP $tag reason=hidden_leaf hidden=$parentHidden vis=${view.visibility} alpha=${view.alpha}")
                return
            }

            val rect = Rect()
            val onScreen = view.getGlobalVisibleRect(rect)
            // TD-130: when includeOffscreen=true, recover bounds via
            // getLocationOnScreen + measured dimensions so the walker emits
            // views that exist in the tree but lie outside the current
            // viewport (or are clipped to zero visible area).
            val bounds =
                if (onScreen) {
                    Bounds(
                        x = rect.left,
                        y = rect.top,
                        w = rect.right - rect.left,
                        h = rect.bottom - rect.top,
                    )
                } else if (includeOffscreen) {
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    Bounds(x = loc[0], y = loc[1], w = view.width, h = view.height)
                } else {
                    log.appendLine("SKIP $tag reason=no_visible_rect")
                    return
                }
            if (bounds.w <= 0 || bounds.h <= 0) {
                if (!includeOffscreen || (view.width <= 0 || view.height <= 0)) {
                    log.appendLine("SKIP $tag reason=zero_size bounds=$bounds")
                    return
                }
            }

            val isExternal = isExternalSurface(view)
            val effectiveExternal = isExternal || parentExternal

            if (effectivelyHidden) {
                // INVISIBLE or at alpha 0 (or under such a parent), the container draws nothing:
                // its element is structure only, with nothing of its background or image.
                val element = buildElement(view, bounds, density, nextZ(), effectiveExternal, hidden = true)
                elements.add(element)
                log.appendLine(
                    "EMIT $tag z=${element.zIndex} type=${element.type} bounds=$bounds hidden_container=true vis=${view.visibility} alpha=${view.alpha} children=$childCount",
                )
                if (!isExternal && isGroup) {
                    val group = view as ViewGroup
                    for (i in 0 until group.childCount) {
                        walkView(group.getChildAt(i), effectiveExternal, true)
                    }
                }
                return
            }

            // The alpha this View draws at: its own times every ancestor's (View.setAlpha sets it
            // on the View's RenderNode, which fades everything it and its children draw).
            val alpha = parentAlpha * view.alpha
            val element = buildElement(view, bounds, density, nextZ(), effectiveExternal, alpha = alpha)
            elements.add(element)
            val c = element.content?.take(30) ?: "null"
            log.appendLine(
                "EMIT $tag z=${element.zIndex} type=${element.type}" +
                    " bounds=$bounds vis=V alpha=${view.alpha}" +
                    " accessible=${view.isImportantForAccessibility}" +
                    " children=$childCount clickable=${view.isClickable}" +
                    " content=$c",
            )

            if (isExternal) return

            if (isComposeView(view)) {
                try {
                    val before = elements.size
                    val ok = walkComposeSemanticsOwner(view, density, ::nextZ, elements, log, alpha)
                    if (!ok && view is ViewGroup) {
                        val group = view as ViewGroup
                        for (i in 0 until group.childCount) {
                            walkView(group.getChildAt(i), effectiveExternal, false, alpha)
                        }
                    }
                    log.appendLine("COMPOSE_DESCENT root=$tag ok=$ok emitted=${elements.size - before}")
                } catch (e: Throwable) {
                    log.appendLine("COMPOSE_DESCENT root=$tag failed=${e.javaClass.simpleName}:${e.message}")
                }
                return
            }

            if (isGroup) {
                val group = view as ViewGroup
                for (i in 0 until group.childCount) {
                    walkView(group.getChildAt(i), effectiveExternal, false, alpha)
                }
            }
        }

        walkView(decorView, parentExternal = false, parentHidden = false)
        removeGhostTouchTargets(elements, log)
        disambiguateIds(elements)
        lastDebugLog = log.toString()

        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")

        val metrics = activity.resources.displayMetrics
        val viewport =
            ViewportInfo(
                width = metrics.widthPixels,
                height = metrics.heightPixels,
                density = density,
            )

        return SemanticSchema(
            screen = activity.javaClass.simpleName,
            device = Build.MODEL,
            platform = "android",
            timestamp = sdf.format(Date()),
            viewport = viewport,
            elements = elements,
        )
    }

    internal fun removeGhostTouchTargets(
        elements: MutableList<SemanticElement>,
        log: StringBuilder,
    ) {
        val toRemove = mutableSetOf<Int>()
        for (i in elements.indices) {
            val a = elements[i]
            if (!a.clickable) continue
            if (a.type != "button" && a.type != "view") continue
            if (!a.content.isNullOrEmpty()) continue
            // A touch target that draws something (an outlined button, a gradient pill, an
            // icon of any format, a shadow, a custom draw) is a real element, not a ghost.
            if (a.drawsSomething) continue

            for (j in elements.indices) {
                if (i == j) continue
                val b = elements[j]
                if (!b.content.isNullOrEmpty() && boundsContains(a.bounds, b.bounds)) {
                    toRemove.add(i)
                    log.appendLine("GHOST_FILTER remove id=${a.id} z=${a.zIndex} — contains ${b.id} with content")
                    break
                }
            }
        }
        for (idx in toRemove.sortedDescending()) {
            elements.removeAt(idx)
        }
    }

    private var imageOutputDir: java.io.File? = null

    /**
     * One View as an element. A [hidden] view (INVISIBLE, at alpha 0, or under such a parent)
     * draws nothing, so it is reported as structure only: its type, bounds, text and semantics,
     * with no color, background, corner radius, border, gradient, shadow or image. Every color it
     * draws is faded by [alpha], the View's own alpha times every ancestor's ([ComposeVisuals.scaleAlpha]).
     */
    private fun buildElement(
        view: View,
        bounds: Bounds,
        density: Float,
        z: Int,
        isExternal: Boolean,
        hidden: Boolean = false,
        alpha: Float = 1f,
    ): SemanticElement {
        val resourceId = extractResourceId(view)
        val content: String?
        var font: FontInfo? = null
        var textColor: String? = null
        var lineCount: Int? = null
        var truncated: Boolean? = null
        var imageResource: String? = null
        var imageType: String? = null
        var imagePath: String? = null
        val type: String

        when (view) {
            is EditText -> {
                content = view.text?.toString()?.ifEmpty { view.hint?.toString() }
                font = extractFont(view, density)
                textColor = visibleColor(ComposeVisuals.scaleAlpha(view.currentTextColor, alpha))
                type = "input"
            }

            is TextView -> {
                content = view.text?.toString()
                font = extractFont(view, density)
                textColor = visibleColor(ComposeVisuals.scaleAlpha(view.currentTextColor, alpha))
                lineCount = view.lineCount.takeIf { it > 0 }
                truncated =
                    view.layout?.let { layout ->
                        val lastLine = layout.lineCount - 1
                        lastLine >= 0 && layout.getEllipsisCount(lastLine) > 0
                    }
                type = if (view.isClickable) "button" else "text"
            }

            is ImageView -> {
                content = view.contentDescription?.toString()
                // A transparent tint that replaces the image's pixels leaves no image on screen:
                // no image block, no file written for it, no tint.
                if (!hidden && !imageTintHides(view)) {
                    textColor = imageTint(view, alpha)
                    val imgInfo = extractImageInfo(view, resourceId ?: "image_${bounds.x}_${bounds.y}")
                    imageResource = imgInfo?.first
                    imageType = imgInfo?.second
                    imagePath = imgInfo?.third
                }
                type = "image"
            }

            else -> {
                content = view.contentDescription?.toString()
                type =
                    when {
                        view.isClickable -> "button"
                        view is ViewGroup -> "container"
                        else -> "view"
                    }
            }
        }

        val id =
            when {
                !content.isNullOrEmpty() -> slugify(content)
                resourceId != null -> resourceId.lowercase()
                else -> "${view.javaClass.simpleName.lowercase()}_${bounds.x}_${bounds.y}"
            }

        val padding =
            if (view.paddingTop > 0 || view.paddingBottom > 0 ||
                view.paddingStart > 0 || view.paddingEnd > 0
            ) {
                PaddingInfo(
                    top = (view.paddingTop / density).toInt(),
                    bottom = (view.paddingBottom / density).toInt(),
                    start = (view.paddingStart / density).toInt(),
                    end = (view.paddingEnd / density).toInt(),
                )
            } else {
                null
            }

        val margin =
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                if (lp.topMargin > 0 || lp.bottomMargin > 0 ||
                    lp.marginStart > 0 || lp.marginEnd > 0
                ) {
                    MarginInfo(
                        top = (lp.topMargin / density).toInt(),
                        bottom = (lp.bottomMargin / density).toInt(),
                        start = (lp.marginStart / density).toInt(),
                        end = (lp.marginEnd / density).toInt(),
                    )
                } else {
                    null
                }
            }

        val elev = view.elevation + view.translationZ
        val elevDp = if (elev > 0f && !hidden && shadowColorsVisible(view, alpha)) elev / density else null
        val background = if (hidden) null else viewBackground(view, density, alpha)

        return SemanticElement(
            id = id,
            platformId = resourceId,
            type = type,
            content = content,
            font = font,
            color = if (hidden) null else textColor,
            bounds = bounds,
            zIndex = z,
            clickable = view.isClickable || hasClickableAncestor(view),
            enabled = view.isEnabled,
            accessible = view.isImportantForAccessibility,
            a11yLabel = view.contentDescription?.toString(),
            a11yId = resourceId,
            background = background?.fill,
            cornerRadius = background?.cornerRadiusPx?.let { it / density },
            padding = padding,
            margin = margin,
            elevation = elevDp,
            render = if (isExternal) "external" else null,
            lineCount = lineCount,
            truncated = truncated,
            imageResource = imageResource,
            imageType = imageType,
            imagePath = imagePath,
            border = background?.border,
            gradient = background?.gradient,
            tapTarget = if (!view.isClickable && !view.isLongClickable) findClickableAncestorBounds(view) else null,
            customDraw = background?.customDraw ?: false,
        )
    }

    /**
     * Walks Compose semantics via reflection on AndroidComposeView.semanticsOwner.
     * No accessibility flags toggled, no AccessibilityNodeProvider — pure introspection
     * of Compose's internal SemanticsNode tree. Returns true if walk succeeded.
     */
    private fun walkComposeSemanticsOwner(
        view: View,
        density: Float,
        nextZ: () -> Int,
        out: MutableList<SemanticElement>,
        log: StringBuilder,
        // The alpha [view] draws at: its own times every ancestor's.
        viewAlpha: Float = 1f,
    ): Boolean {
        // Find AndroidComposeView (may be `view` itself or a descendant)
        val androidComposeView = findAndroidComposeView(view) ?: return false
        // Its RenderNode fades everything Compose draws in it, with every View between it and
        // [view] fading it too: the alpha the whole Compose tree starts from.
        var hostAlpha = viewAlpha
        var between: View? = androidComposeView
        while (between != null && between !== view) {
            hostAlpha *= between.alpha
            between = between.parent as? View
        }
        val owner = try {
            val m = androidComposeView.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" && it.parameterCount == 0 }
            m?.invoke(androidComposeView)
        } catch (e: Throwable) {
            log.appendLine("COMPOSE_SEM owner fetch failed=${e.javaClass.simpleName}:${e.message}")
            null
        }

        val rootNode = try {
            val m = owner?.javaClass?.methods?.firstOrNull { it.name == "getUnmergedRootSemanticsNode" && it.parameterCount == 0 }
                ?: owner?.javaClass?.methods?.firstOrNull { it.name == "getRootSemanticsNode" && it.parameterCount == 0 }
            m?.invoke(owner)
        } catch (e: Throwable) {
            log.appendLine("COMPOSE_SEM root fetch failed=${e.javaClass.simpleName}:${e.message}")
            null
        }

        // Preferred: the LayoutNode tree, which carries each node's modifier
        // chain (background, border, shape, painter, text style). Its
        // semantics come from the unmerged SemanticsNode tree, keyed by the
        // semantics id both trees share — LayoutNode's own config getter has
        // been renamed across Compose releases, the id has not. Falls back to
        // the semantics-only walk if the LayoutNode surface isn't reachable.
        val root = try {
            androidComposeView.javaClass.methods
                .firstOrNull { it.name == "getRoot" && it.parameterCount == 0 && it.returnType.simpleName == "LayoutNode" }
                ?.invoke(androidComposeView)
        } catch (e: Throwable) {
            log.appendLine("COMPOSE_LAYOUT root fetch failed=${e.javaClass.simpleName}:${e.message}")
            null
        }
        return composeWalk(
            layoutWalk = root?.let {
                {
                    val configs = HashMap<Int, Any>()
                    if (rootNode != null) collectSemanticsConfigs(rootNode, configs)
                    val res = androidComposeView.resources
                    walkLayoutNode(it, configs, density, res.configuration.fontScale, res, nextZ, out, log, false, hostAlpha)
                }
            },
            semanticsWalk = rootNode?.let { { walkSemanticsNode(it, density, nextZ, out, log, 0, false) } },
            out = out,
            log = log,
        )
    }

    /**
     * The LayoutNode walk when it emits something, else the semantics-only walk. A walk that
     * throws or emits nothing (a renamed child accessor fails softly) falls back; with no
     * semantics root either, the result is false and the caller walks the View tree.
     */
    internal fun composeWalk(
        layoutWalk: (() -> Unit)?,
        semanticsWalk: (() -> Unit)?,
        out: MutableList<SemanticElement>,
        log: StringBuilder,
    ): Boolean {
        val before = out.size
        if (layoutWalk != null) {
            try {
                layoutWalk()
                val emitted = out.size - before
                log.appendLine("COMPOSE_LAYOUT ok emitted=$emitted")
                if (emitted > 0) return true
            } catch (e: Throwable) {
                log.appendLine("COMPOSE_LAYOUT failed=${e.javaClass.simpleName}:${e.message}")
                while (out.size > before) out.removeAt(out.size - 1)
            }
        }
        if (semanticsWalk == null) return false
        semanticsWalk()
        return true
    }

    /** LayoutNode.zSortedChildren as a list, or null when this Compose release doesn't expose it. */
    private fun zSortedChildren(node: Any): List<*>? = try {
        val vector = node.javaClass.methods
            .firstOrNull { it.parameterCount == 0 && (it.name == "getZSortedChildren" || it.name.startsWith("getZSortedChildren$")) }
            ?.invoke(node)
        when (vector) {
            null -> null
            is List<*> -> vector
            // MutableVector keeps a backing array larger than its size.
            else -> {
                val size = vector.javaClass.methods.firstOrNull { it.name == "getSize" && it.parameterCount == 0 }?.invoke(vector) as? Int
                val content = vector.javaClass.methods.firstOrNull { it.name == "getContent" && it.parameterCount == 0 }?.invoke(vector) as? Array<*>
                if (size != null && content != null && size <= content.size) content.take(size) else null
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun collectSemanticsConfigs(node: Any, out: MutableMap<Int, Any>) {
        val id = ComposeVisuals.invoke0(node, "getId") as? Int
        val config = ComposeVisuals.invoke0(node, "getConfig")
        if (id != null && config != null) out[id] = config
        val children = try {
            val m = node.javaClass.methods.firstOrNull { it.name == "getChildren" && it.parameterCount == 0 }
                ?: node.javaClass.methods.firstOrNull { it.name == "getReplacedChildren" && it.parameterCount == 0 }
            m?.invoke(node) as? List<*>
        } catch (_: Throwable) { null } ?: return
        for (child in children) if (child != null) collectSemanticsConfigs(child, out)
    }

    /**
     * One LayoutNode: its own (unmerged) semantics plus what its modifier
     * chain draws. A node is emitted when it says something (text, content
     * description, role, click, input) or draws something (background,
     * gradient, border, image) — so undescribed images and plain cards show
     * up too, which the semantics-only walk could never report.
     */
    private fun walkLayoutNode(
        node: Any,
        configs: Map<Int, Any>,
        density: Float,
        fontScale: Float,
        resources: android.content.res.Resources,
        nextZ: () -> Int,
        out: MutableList<SemanticElement>,
        log: StringBuilder,
        inheritedClickable: Boolean,
        // The product of every ancestor's graphics-layer alpha: it fades this subtree too.
        inheritedAlpha: Float = 1f,
    ) {
        if (ComposeVisuals.invoke0(node, "isPlaced") == false) return
        // The outer coordinator is the box the node occupies, before any of
        // its own padding; getCoordinates is the inner (content) box.
        val coords = ComposeVisuals.invokeInternal(node, "getOuterCoordinator") ?: ComposeVisuals.invoke0(node, "getCoordinates")
        val bounds = coords?.let { ComposeVisuals.boundsInWindow(it) }
        val visuals = ComposeVisuals.read(node, density, fontScale, resources, bounds, inheritedAlpha)
        val subtreeAlpha = inheritedAlpha * (visuals.alpha ?: 1f)
        // A layer at alpha 0 hides the node's content and its subtree, and so does a
        // drawWithContent block that skips drawContent(), but whatever the node draws before
        // that point (background(Red).graphicsLayer(alpha = 0f)) stays on screen: emit only
        // that, as a plain container with no semantics, and stop.
        val hidden = visuals.hidesContent(subtreeAlpha)
        if (hidden && !visuals.draws) {
            log.appendLine("COMPOSE_SKIP hidden alpha=$subtreeAlpha contentSkipped=${visuals.contentSkipped} bounds=$bounds")
            return
        }

        val config = if (hidden) null else (ComposeVisuals.invokeInternal(node, "getSemanticsId") as? Int)?.let { configs[it] }
        val text = extractSemanticsText(config)
        val contentDesc = extractSemanticsContentDesc(config)
        val role = extractSemanticsRole(config)
        val isDisabled = hasSemanticsKey(config, "Disabled")
        val ownClick = hasSemanticsAction(config, "OnClick") && !isDisabled
        val isClickable = ownClick || (inheritedClickable && !isDisabled)
        val isInput = hasSemanticsKey(config, "EditableText") || hasSemanticsAction(config, "SetText")
        val content = text ?: contentDesc
        val image = ComposeVisuals.imageFacts(visuals.imageType, role)
        val isImage = image.isImage

        val says = content != null || ownClick || isInput || role != null || (isClickable && text != null)
        val box = composeBox(ownClick || isInput, bounds, visuals.drawBounds, visuals.margin, visuals.clickBounds)
        val elementBounds = box.bounds
        if (elementBounds != null && (says || visuals.draws)) {
            val type = when {
                isInput -> "input"
                isClickable && (ownClick || text != null) -> "button"
                text != null -> "text"
                isImage -> "image"
                visuals.draws -> "container"
                else -> "view"
            }
            val id = if (!content.isNullOrEmpty()) slugify(content)
                else "compose_${type}_${elementBounds.x}_${elementBounds.y}"
            val showsText = text != null || isInput
            val layoutResult = if (showsText) textLayoutResult(config) else null
            val layout = if (text != null) layoutResult?.let { textLayout(it) } else null
            // A text field draws its text through its own modifiers, not a text element, so its
            // font and color come from the layout its semantics expose.
            val textVisuals = if (showsText) {
                ComposeVisuals.withLayoutText(
                    visuals, layoutResult, ComposeVisuals.invoke0(node, "getDensity"), fontScale, resources, subtreeAlpha,
                )
            } else {
                visuals
            }
            val z = nextZ()
            out.add(
                SemanticElement(
                    id = id, platformId = null, type = type, content = content,
                    font = if (showsText) textVisuals.font else null,
                    color = if (showsText) textVisuals.textColor else visuals.imageTint,
                    bounds = elementBounds, zIndex = z,
                    clickable = isClickable, enabled = !isDisabled, accessible = config != null,
                    a11yLabel = contentDesc, a11yId = null,
                    background = visuals.background,
                    cornerRadius = visuals.cornerRadius,
                    padding = visuals.padding, margin = box.margin,
                    elevation = visuals.elevation, render = null,
                    lineCount = layout?.first, truncated = layout?.second,
                    imageResource = if (isImage) id else null,
                    imageType = image.imageType,
                    imagePath = null,
                    border = visuals.border, gradient = visuals.gradient, tapTarget = box.tapTarget,
                    customDraw = visuals.customDraw,
                ),
            )
            log.appendLine("COMPOSE_EMIT z=$z type=$type bounds=$elementBounds bg=${visuals.background} img=${visuals.imageType} content=${content?.take(30) ?: "null"}")
        }
        if (hidden) return

        // Paint order first: Compose draws children by zIndex, then placement order
        // (zSortedChildren, an internal MutableVector), so z_index follows what is on top.
        // LayoutNode.children (name-mangled getChildren$ui) and the public getChildrenInfo
        // are the fallbacks, in declaration order.
        val children = zSortedChildren(node) ?: run {
            val methods = node.javaClass.methods.filter { it.parameterCount == 0 && List::class.java.isAssignableFrom(it.returnType) }
            (
                methods.firstOrNull { it.name == "getChildren" || it.name.startsWith("getChildren$") }
                    ?: methods.firstOrNull { it.name == "getChildrenInfo" }
                )?.let { m -> try { m.invoke(node) as? List<*> } catch (_: Throwable) { null } }
        } ?: return
        for (child in children) {
            if (child != null) walkLayoutNode(child, configs, density, fontScale, resources, nextZ, out, log, isClickable, subtreeAlpha)
        }
    }

    /**
     * Text is truncated when its last line is ellipsized or it ran past maxLines (a clip with
     * no ellipsis). Null when neither signal could be read.
     */
    fun textTruncated(ellipsized: Boolean?, exceededMaxLines: Boolean?): Boolean? =
        if (ellipsized == null && exceededMaxLines == null) null else ellipsized == true || exceededMaxLines == true

    /** The box a Compose element reports: its bounds, its margin, and where a tap on it lands. */
    internal data class ComposeBox(val bounds: Bounds?, val margin: MarginInfo?, val tapTarget: Bounds? = null)

    /**
     * The box a Compose element reports. When its first draw sits inside the node's box
     * ([drawBounds]: an outer padding, or a layout modifier that grows the box around it, as
     * minimumInteractiveComponentSize does around a 40 dp Material button) the element is what
     * is drawn, there, with the padding outside it as its margin. A node that takes touches
     * itself ([touchable]: its own click, or an input) and has no margin still takes them over
     * its whole box (a Material button's 48 dp touch area around its 40 dp shape), which is then
     * its tap target. A click covers the box of the coordinator it is attached to, which every
     * layout modifier before it shapes ([clickBounds]): the whole box for
     * clickable { }.padding(8.dp), the box inside the outer 4 dp for
     * padding(4.dp).clickable { }.padding(8.dp), the inner 40 dp for
     * size(96.dp).wrapContentSize().clickable { }.size(40.dp). That box is the
     * tap target when it differs from the drawn one; for padding(8.dp).clickable { } it is the
     * drawn box, and there is none. With no draw bounds the whole box is reported,
     * with no margin, so no stretch of screen is counted both as the element and as its margin,
     * and a click box around it (Compose's minimum touch target) is its tap target.
     */
    internal fun composeBox(
        touchable: Boolean,
        bounds: Bounds?,
        drawBounds: Bounds?,
        margin: MarginInfo?,
        clickBounds: Bounds? = null,
    ): ComposeBox =
        if (drawBounds != null) {
            // With no click modifier read (an input), the whole box when there is no margin.
            val target = clickBounds ?: bounds.takeIf { margin == null }
            ComposeBox(drawBounds, margin, tapTarget = if (touchable && target != drawBounds) target else null)
        } else {
            // A click Compose hit-tests over more than the whole box (a 24 dp icon's 48 dp
            // minimum touch target) still taps that larger box.
            val target = clickBounds?.takeIf { touchable && bounds != null && it != bounds && it.encloses(bounds) }
            ComposeBox(bounds, null, tapTarget = target)
        }

    private fun Bounds.encloses(b: Bounds): Boolean = x <= b.x && y <= b.y && x + w >= b.x + b.w && y + h >= b.y + b.h

    /** The TextLayoutResult the node's GetTextLayoutResult semantics action returns, or null. */
    private fun textLayoutResult(config: Any?): Any? {
        if (config == null) return null
        try {
            val iter = (config as? Iterable<*>)?.iterator() ?: return null
            while (iter.hasNext()) {
                val entry = iter.next() ?: continue
                val key = entry.javaClass.getMethod("getKey").invoke(entry)
                val keyName = key?.javaClass?.getMethod("getName")?.invoke(key) as? String
                if (keyName != "GetTextLayoutResult") continue
                val action = entry.javaClass.getMethod("getValue").invoke(entry)
                val fn = ComposeVisuals.invoke0(action, "getAction") ?: return null
                val results = ArrayList<Any?>()
                @Suppress("UNCHECKED_CAST")
                (fn as? Function1<Any?, Any?>)?.invoke(results)
                return results.firstOrNull()
            }
        } catch (_: Throwable) {}
        return null
    }

    /** (lineCount, truncated) from a TextLayoutResult. */
    private fun textLayout(result: Any): Pair<Int?, Boolean?>? =
        try {
            val lines = (ComposeVisuals.invoke0(result, "getLineCount") as? Int)?.takeIf { it > 0 }
            // Truncated = the last laid-out line ends in an ellipsis, or the
            // text had more lines than maxLines (TextOverflow.Clip cuts them
            // with no ellipsis). The overflow flags are no signal: sub-pixel
            // rounding sets didOverflowWidth (and a tall line height
            // hasVisualOverflow) on text that is drawn whole.
            val ellipsized = lines?.let { n ->
                result.javaClass.methods
                    .firstOrNull { it.name == "isLineEllipsized" && it.parameterCount == 1 }
                    ?.invoke(result, n - 1) as? Boolean
            }
            val exceededMaxLines = ComposeVisuals.invoke0(
                ComposeVisuals.invoke0(result, "getMultiParagraph"),
                "getDidExceedMaxLines",
            ) as? Boolean
            Pair(lines, textTruncated(ellipsized, exceededMaxLines))
        } catch (_: Throwable) {
            null
        }

    private fun findAndroidComposeView(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val r = findAndroidComposeView(view.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun walkSemanticsNode(
        node: Any,
        density: Float,
        nextZ: () -> Int,
        out: MutableList<SemanticElement>,
        log: StringBuilder,
        depth: Int,
        inheritedClickable: Boolean,
    ) {
        val cls = node.javaClass
        // SemanticsNode.boundsInWindow: Rect (composeui Rect, not graphics Rect)
        val composeRect = try {
            val m = cls.methods.firstOrNull { it.name == "getBoundsInWindow" && it.parameterCount == 0 }
            m?.invoke(node)
        } catch (_: Throwable) { null }
        val bounds = composeRectToBounds(composeRect)

        // SemanticsNode.config: SemanticsConfiguration
        val config = try {
            val m = cls.methods.firstOrNull { it.name == "getConfig" && it.parameterCount == 0 }
            m?.invoke(node)
        } catch (_: Throwable) { null }

        val text = extractSemanticsText(config)
        val contentDesc = extractSemanticsContentDesc(config)
        val role = extractSemanticsRole(config)
        val ownClick = hasSemanticsAction(config, "OnClick")
        // TD-59: Compose Modifier.clickable installs the OnClick semantics even
        // when `enabled=false`; the action body is gated separately. Detect the
        // Disabled semantics key (set by Material/Modifier when enabled=false)
        // and treat as non-clickable regardless of OnClick presence.
        val isDisabled = hasSemanticsKey(config, "Disabled")
        val effectiveOwnClick = ownClick && !isDisabled
        // In Compose's unmerged tree the clickable Box and its inner Text label
        // are separate nodes. Propagate clickable from a clickable ancestor so
        // text leaves inside a Button report clickable=true. Disabled blocks
        // propagation locally and downstream.
        val isClickable = effectiveOwnClick || (inheritedClickable && !isDisabled)
        val isInput = hasSemanticsKey(config, "EditableText") || hasSemanticsAction(config, "SetText")

        val content = text ?: contentDesc
        if (bounds != null && (content != null || isClickable || isInput || role != null)) {
            val type = when {
                isInput -> "input"
                isClickable && !content.isNullOrEmpty() -> "button"
                isClickable -> "button"
                !content.isNullOrEmpty() -> "text"
                else -> "view"
            }
            val id = if (!content.isNullOrEmpty()) slugify(content)
                else "compose_${role?.lowercase() ?: "node"}_${bounds.x}_${bounds.y}"
            val z = nextZ()
            out.add(
                SemanticElement(
                    id = id, platformId = null, type = type, content = content,
                    font = null, color = null, bounds = bounds, zIndex = z,
                    clickable = isClickable, enabled = !isDisabled, accessible = true,
                    a11yLabel = contentDesc, a11yId = null, background = null,
                    cornerRadius = null, padding = null, margin = null, elevation = null,
                    render = null, lineCount = null, truncated = null,
                    imageResource = null, imageType = null, imagePath = null,
                    border = null, gradient = null, tapTarget = null,
                ),
            )
            log.appendLine("COMPOSE_SEM_EMIT z=$z type=$type bounds=$bounds clickable=$isClickable content=${content?.take(30) ?: "null"}")
        }

        // Recurse: SemanticsNode.children: List<SemanticsNode>
        val children = try {
            // try getChildren() first (returns List); fallback to getReplacedChildren()
            val m = cls.methods.firstOrNull { it.name == "getChildren" && it.parameterCount == 0 }
                ?: cls.methods.firstOrNull { it.name == "getReplacedChildren" && it.parameterCount == 0 }
            m?.invoke(node) as? List<*>
        } catch (_: Throwable) { null }
        if (children != null) {
            for (child in children) {
                if (child != null) walkSemanticsNode(child, density, nextZ, out, log, depth + 1, isClickable)
            }
        }
    }

    private fun composeRectToBounds(rect: Any?): Bounds? {
        if (rect == null) return null
        return try {
            val cls = rect.javaClass
            val left = (cls.getMethod("getLeft").invoke(rect) as Float)
            val top = (cls.getMethod("getTop").invoke(rect) as Float)
            val right = (cls.getMethod("getRight").invoke(rect) as Float)
            val bottom = (cls.getMethod("getBottom").invoke(rect) as Float)
            val w = (right - left).toInt()
            val h = (bottom - top).toInt()
            if (w <= 0 || h <= 0) null
            else Bounds(left.toInt(), top.toInt(), w, h)
        } catch (_: Throwable) { null }
    }

    private fun extractSemanticsText(config: Any?): String? {
        if (config == null) return null
        // SemanticsConfiguration is iterable over Map.Entry<SemanticsPropertyKey<*>, Any?>
        try {
            val iter = (config as? Iterable<*>)?.iterator() ?: return null
            while (iter.hasNext()) {
                val entry = iter.next() ?: continue
                val key = entry.javaClass.getMethod("getKey").invoke(entry)
                val keyName = key?.javaClass?.getMethod("getName")?.invoke(key) as? String
                if (keyName == "Text" || keyName == "EditableText") {
                    val value = entry.javaClass.getMethod("getValue").invoke(entry)
                    // Text is List<AnnotatedString>, EditableText is AnnotatedString
                    val str = when (value) {
                        is List<*> -> value.joinToString(" ") { it?.toString() ?: "" }
                        else -> value?.toString()
                    }
                    if (!str.isNullOrEmpty()) return str
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun extractSemanticsContentDesc(config: Any?): String? {
        if (config == null) return null
        try {
            val iter = (config as? Iterable<*>)?.iterator() ?: return null
            while (iter.hasNext()) {
                val entry = iter.next() ?: continue
                val key = entry.javaClass.getMethod("getKey").invoke(entry)
                val keyName = key?.javaClass?.getMethod("getName")?.invoke(key) as? String
                if (keyName == "ContentDescription") {
                    val value = entry.javaClass.getMethod("getValue").invoke(entry)
                    val str = when (value) {
                        is List<*> -> value.joinToString(" ") { it?.toString() ?: "" }
                        else -> value?.toString()
                    }
                    if (!str.isNullOrEmpty()) return str
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun extractSemanticsRole(config: Any?): String? {
        if (config == null) return null
        try {
            val iter = (config as? Iterable<*>)?.iterator() ?: return null
            while (iter.hasNext()) {
                val entry = iter.next() ?: continue
                val key = entry.javaClass.getMethod("getKey").invoke(entry)
                val keyName = key?.javaClass?.getMethod("getName")?.invoke(key) as? String
                if (keyName == "Role") {
                    return entry.javaClass.getMethod("getValue").invoke(entry)?.toString()
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    private fun hasSemanticsAction(config: Any?, actionName: String): Boolean {
        if (config == null) return false
        try {
            val iter = (config as? Iterable<*>)?.iterator() ?: return false
            while (iter.hasNext()) {
                val entry = iter.next() ?: continue
                val key = entry.javaClass.getMethod("getKey").invoke(entry)
                val keyName = key?.javaClass?.getMethod("getName")?.invoke(key) as? String
                if (keyName == actionName) return true
            }
        } catch (_: Throwable) {}
        return false
    }

    private fun hasSemanticsKey(config: Any?, keyName: String): Boolean = hasSemanticsAction(config, keyName)

    private fun isComposeView(view: View): Boolean {
        var c: Class<*>? = view.javaClass
        while (c != null) {
            val n = c.name
            if (n == "androidx.compose.ui.platform.AbstractComposeView" ||
                n == "androidx.compose.ui.platform.AndroidComposeView"
            ) return true
            c = c.superclass
        }
        return false
    }

    private fun composeA11yDelegate(view: View): Any? {
        var c: Class<*>? = view.javaClass
        while (c != null) {
            if (c.name == "androidx.compose.ui.platform.AndroidComposeView") {
                for (fieldName in listOf("composeAccessibilityDelegate", "accessibilityDelegate")) {
                    try {
                        val f = c.getDeclaredField(fieldName)
                        f.isAccessible = true
                        return f.get(view) ?: continue
                    } catch (_: NoSuchFieldException) {}
                }
                return null
            }
            c = c.superclass
        }
        return null
    }

    private fun setComposeA11yFlag(delegate: Any?, enabled: Boolean) {
        if (delegate == null) return
        try {
            val f = delegate.javaClass.getDeclaredField("accessibilityForceEnabledForTesting")
            f.isAccessible = true
            f.setBoolean(delegate, enabled)
        } catch (_: Throwable) {}
    }

    private fun walkComposeProvider(
        node: AccessibilityNodeInfo,
        provider: android.view.accessibility.AccessibilityNodeProvider,
        density: Float,
        nextZ: () -> Int,
        out: MutableList<SemanticElement>,
        log: StringBuilder,
    ) {
        val childCount = node.childCount
        for (i in 0 until childCount) {
            // Compose's createAccessibilityNodeInfo returns unsealed nodes; getChild() throws
            // "Cannot perform this action on a not sealed instance". The hidden API
            // AccessibilityNodeInfo.getChildId(int) returns the packed (sourceId,virtualId)
            // long; we extract the virtual ID and re-query the provider.
            val packed: Long = try {
                var p: Long = 0L
                var found = false
                val cls = AccessibilityNodeInfo::class.java
                try {
                    val f = cls.getDeclaredField("mChildNodeIds")
                    f.isAccessible = true
                    val arr = f.get(node)
                    if (arr is LongArray && i < arr.size) {
                        p = arr[i]; found = true
                    } else if (arr != null) {
                        val getM = arr.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                        p = getM.invoke(arr, i) as Long; found = true
                    }
                } catch (_: Throwable) {}
                if (!found) for (m in cls.declaredMethods) {
                    if (m.name == "getChildId" && m.parameterTypes.size == 1) {
                        m.isAccessible = true; p = m.invoke(node, i) as Long; found = true; break
                    }
                }
                if (!found) throw NoSuchMethodException("no getChildId or mChildNodeIds")
                p
            } catch (e: Throwable) {
                log.appendLine("COMPOSE_CHILDID_ERR i=$i err=${e.javaClass.simpleName}:${e.message}")
                continue
            }
            // Try both halves and the raw value to handle differing pack orders.
            val low = (packed and 0xFFFFFFFFL).toInt()
            val high = (packed ushr 32).toInt()
            val candidates = listOf(low, high, packed.toInt())
            var child: AccessibilityNodeInfo? = null
            var hitVid = -1
            for (vid in candidates.distinct()) {
                val c = try { provider.createAccessibilityNodeInfo(vid) } catch (_: Throwable) { null }
                if (c != null) { child = c; hitVid = vid; break }
            }
            if (child == null) {
                log.appendLine("COMPOSE_CHILD_NULL i=$i packed=$packed low=$low high=$high")
                continue
            }
            val virtualId = hitVid
            try {
                val rect = Rect()
                child.getBoundsInScreen(rect)
                val text = child.text?.toString()
                val desc = child.contentDescription?.toString()
                log.appendLine(
                    "COMPOSE_CHILD i=$i class=${child.className} bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}] " +
                        "text=${text?.take(40) ?: "null"} desc=${desc?.take(40) ?: "null"} children=${child.childCount}",
                )
                val element = buildElementFromA11y(child, density, nextZ())
                if (element != null) {
                    out.add(element)
                    log.appendLine(
                        "COMPOSE_EMIT z=${element.zIndex} type=${element.type} bounds=${element.bounds} " +
                            "clickable=${element.clickable} content=${element.content?.take(30) ?: "null"}",
                    )
                }
                walkComposeProvider(child, provider, density, nextZ, out, log)
            } finally {
                try { child.recycle() } catch (_: Throwable) {}
            }
        }
    }

    private fun buildElementFromA11y(
        info: AccessibilityNodeInfo,
        density: Float,
        z: Int,
    ): SemanticElement? {
        val rect = Rect()
        info.getBoundsInScreen(rect)
        if (rect.width() <= 0 || rect.height() <= 0) return null

        val text = info.text?.toString()
        val contentDesc = info.contentDescription?.toString()
        val hint = if (Build.VERSION.SDK_INT >= 26) info.hintText?.toString() else null
        val content = text ?: contentDesc ?: hint

        val className = info.className?.toString() ?: ""
        val isInput = className.endsWith("EditText") || info.isEditable
        // TD-59: Compose Button doesn't set the legacy info.isClickable bit;
        // it publishes click affordance via actionList ACTION_CLICK and/or a
        // "Button" role. Treat either as clickable so element_state assertions
        // toggle correctly with the enabled= parameter.
        val hasClickAction = try {
            info.actionList.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id }
        } catch (_: Throwable) { false }
        val isComposeButton = className.endsWith("Button") ||
            className == "android.view.View" && hasClickAction
        val clickable = info.isClickable || hasClickAction || isComposeButton
        val type = when {
            isInput -> "input"
            clickable && !content.isNullOrEmpty() -> "button"
            clickable -> "button"
            !content.isNullOrEmpty() -> "text"
            else -> "view"
        }

        val id = when {
            !content.isNullOrEmpty() -> slugify(content)
            else -> "compose_${className.substringAfterLast('.').lowercase()}_${rect.left}_${rect.top}"
        }

        return SemanticElement(
            id = id,
            platformId = null,
            type = type,
            content = content,
            font = null,
            color = null,
            bounds = Bounds(rect.left, rect.top, rect.width(), rect.height()),
            zIndex = z,
            clickable = clickable,
            enabled = info.isEnabled,
            accessible = info.isImportantForAccessibility,
            a11yLabel = contentDesc,
            a11yId = null,
            background = null,
            cornerRadius = null,
            padding = null,
            margin = null,
            elevation = null,
            render = null,
            lineCount = null,
            truncated = null,
            imageResource = null,
            imageType = null,
            imagePath = null,
            border = null,
            gradient = null,
            tapTarget = null,
        )
    }

    internal fun disambiguateIds(elements: MutableList<SemanticElement>) {
        val counts = mutableMapOf<String, Int>()
        for (e in elements) counts[e.id] = (counts[e.id] ?: 0) + 1

        val seen = mutableMapOf<String, Int>()
        for (i in elements.indices) {
            val e = elements[i]
            if ((counts[e.id] ?: 0) > 1) {
                val idx = seen.getOrDefault(e.id, 0)
                seen[e.id] = idx + 1
                elements[i] = e.copy(id = "${e.id}_$idx")
            }
        }
    }

    private fun extractFont(
        tv: TextView,
        density: Float,
    ): FontInfo {
        val typeface = tv.typeface ?: Typeface.DEFAULT
        val weight =
            if (Build.VERSION.SDK_INT >= 28) {
                typeface.weight
            } else {
                if (typeface.isBold) 700 else 400
            }
        val weightName =
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

        val fingerprintMatch = lookupFontByFingerprint(typeface)
        val family = fingerprintMatch?.first ?: extractFontFamily(tv)
        val resolvedWeight = fingerprintMatch?.second ?: weightName

        return FontInfo(
            family = family,
            weight = resolvedWeight,
            size = tv.textSize / density,
        )
    }

    private fun lookupFontByFingerprint(typeface: Typeface): Pair<String, String?>? {
        if (fontFingerprints.isEmpty()) return null
        val hash = getGlyphHash(typeface)
        val resName = fontFingerprints[hash] ?: return null
        val family = cleanFontFamily(resName)
        val weightFromName = extractWeightFromName(resName)
        return Pair(family, weightFromName)
    }

    private val weightSuffixes =
        listOf(
            "_extra_bold" to "extrabold",
            "_semi_bold" to "semibold",
            "_semibold" to "semibold",
            "_bold" to "bold",
            "_medium" to "medium",
            "_regular" to "regular",
            "_light" to "light",
            "_extra_light" to "extralight",
            "_thin" to "thin",
            "_black" to "black",
        )

    private fun extractWeightFromName(name: String): String? {
        var lower = name.lowercase()
        if (lower.endsWith("_italic")) lower = lower.removeSuffix("_italic")
        for ((suffix, weight) in weightSuffixes) {
            if (lower.endsWith(suffix)) return weight
        }
        return null
    }

    private fun extractFontFamily(tv: TextView): String {
        try {
            val attrs = intArrayOf(android.R.attr.fontFamily)
            val ta = tv.context.obtainStyledAttributes(null, attrs, android.R.attr.textViewStyle, 0)
            val family = ta.getString(0)
            ta.recycle()
            if (!family.isNullOrEmpty()) return cleanFontFamily(family)
        } catch (_: Exception) {
        }

        try {
            val field = Typeface::class.java.getDeclaredField("mFamilyName")
            field.isAccessible = true
            val name = field.get(tv.typeface) as? String
            if (name != null && name != "sans-serif") return cleanFontFamily(name)
        } catch (_: Exception) {
        }

        return "sans-serif"
    }

    private fun cleanFontFamily(raw: String): String {
        var name = raw.lowercase()
        if (name.startsWith("res/font/")) name = name.removePrefix("res/font/")
        if (name.startsWith("@font/")) name = name.removePrefix("@font/")
        if (name.endsWith(".ttf") || name.endsWith(".otf")) name = name.substringBeforeLast('.')
        if (name.endsWith("_italic")) name = name.removeSuffix("_italic")
        for (suffix in listOf(
            "_extra_bold",
            "_semi_bold",
            "_semibold",
            "_bold",
            "_medium",
            "_regular",
            "_extra_light",
            "_light",
            "_thin",
            "_black",
        )) {
            if (name.endsWith(suffix)) return name.removeSuffix(suffix)
        }
        return name
    }

    // ---- View backgrounds ----

    /**
     * A color as the capture reports it: its hex, or null when it is fully transparent and so
     * draws nothing. The one visible-color rule, the same on both paths: every View color (fills,
     * strokes, text, tints) goes through it, as every Compose color does
     * ([ComposeVisuals.argbHex], which it is). Gradient stops are the one exception: a transparent
     * stop still shapes the fade ([gradientStops]).
     */
    internal fun visibleColor(argb: Int): String? = ComposeVisuals.argbHex(argb, keepTransparent = false)

    /**
     * Whether a View's shadow shows, by its colors (outlineAmbientShadowColor and
     * outlineSpotShadowColor, API 28; black before): two fully transparent ones draw nothing,
     * the rule Compose shadows go through ([ComposeVisuals.shadowColorsVisible]), each faded by
     * the View's [alpha] first.
     */
    internal fun shadowColorsVisible(view: View, alpha: Float = 1f): Boolean =
        Build.VERSION.SDK_INT < 28 ||
            ComposeVisuals.shadowColorsVisible(
                ComposeVisuals.scaleAlpha(view.outlineAmbientShadowColor, alpha),
                ComposeVisuals.scaleAlpha(view.outlineSpotShadowColor, alpha),
            )

    /**
     * The layer of a background that draws. A RippleDrawable draws every layer but its mask
     * (android.R.id.mask), which only bounds the ripple (RippleDrawable.drawContent skips it): its
     * first other layer, or null when the mask is all it has. Any other drawable is its own layer.
     */
    internal fun drawnLayer(background: Drawable?): Drawable? {
        if (background !is RippleDrawable) return background
        for (i in 0 until background.numberOfLayers) {
            if (background.getId(i) != android.R.id.mask) return background.getDrawable(i)
        }
        return null
    }

    /** What a View's background draws: fill, corner radius (px), border, gradient. */
    internal class DrawnBackground(
        val fill: String? = null,
        val cornerRadiusPx: Float? = null,
        val border: BorderInfo? = null,
        val gradient: GradientInfo? = null,
        /**
         * It draws something the fields above can't describe: a GradientDrawable line or ring.
         * Not serialized; it keeps the element from being taken for an empty ghost touch target.
         */
        val customDraw: Boolean = false,
    )

    /**
     * A View's background as it draws, with the View's background tint ([backgroundTint]). A
     * ColorDrawable is a fill; a GradientDrawable is read by [gradientBackground]. A
     * RippleDrawable draws its non-mask layers in order: a later one over the same bounds that
     * covers the earlier ones (an opaque, square fill with no gradient, by the cover rule the
     * Compose path uses) replaces them; any other later layer adds what it visibly draws over
     * them ([mergeLayer]). Every color is faded by the View's [alpha] (its own times every
     * ancestor's), as its RenderNode draws it. Null for any other drawable, or no background.
     */
    internal fun viewBackground(view: View, density: Float, alpha: Float = 1f): DrawnBackground? {
        val background = view.background ?: return null
        if (background !is RippleDrawable) return layerBackground(view, background, density, alpha)
        var chosen = -1
        var drawn: DrawnBackground? = null
        for (i in 0 until background.numberOfLayers) {
            if (background.getId(i) == android.R.id.mask) continue
            val layer = background.getDrawable(i) ?: continue
            // Judged at the layer's own opacity, before the View's alpha.
            if (chosen < 0 || (covers(layerBackground(view, layer, density)) && sameLayerBounds(background, chosen, i))) {
                chosen = i
                drawn = layerBackground(view, layer, density, alpha)
            } else {
                drawn = mergeLayer(drawn, layerBackground(view, layer, density, alpha))
            }
        }
        return drawn
    }

    /**
     * [over], a later ripple layer that does not cover [under], drawn on top of it: its border
     * replaces the earlier one, and its fill or gradient shows where [under] has neither; a
     * transparent or absent field leaves [under]'s. The one corner radius goes with the fill
     * (or gradient) when there is one, else with the border.
     */
    private fun mergeLayer(under: DrawnBackground?, over: DrawnBackground?): DrawnBackground? {
        if (under == null) return over
        if (over == null) return under
        val takesPaint = under.fill == null && under.gradient == null && (over.fill != null || over.gradient != null)
        val fill = if (takesPaint) over.fill else under.fill
        val gradient = if (takesPaint) over.gradient else under.gradient
        val border = over.border ?: under.border
        val radius = when {
            takesPaint -> over.cornerRadiusPx
            fill != null || gradient != null -> under.cornerRadiusPx
            over.border != null -> over.cornerRadiusPx
            else -> under.cornerRadiusPx
        }
        return DrawnBackground(fill, radius, border, gradient, under.customDraw || over.customDraw)
    }

    /** Whether a drawn background hides everything under it in its bounds: an opaque square fill, no gradient. */
    private fun covers(drawn: DrawnBackground?): Boolean =
        drawn != null && drawn.fill?.length == 7 && drawn.cornerRadiusPx == null && drawn.gradient == null && !drawn.customDraw

    /** Whether layers [a] and [b] of [layers] are drawn in the same bounds: the same insets, size and gravity. */
    private fun sameLayerBounds(layers: android.graphics.drawable.LayerDrawable, a: Int, b: Int): Boolean {
        fun box(i: Int) = listOf(
            layers.getLayerInsetLeft(i), layers.getLayerInsetTop(i), layers.getLayerInsetRight(i), layers.getLayerInsetBottom(i),
            layers.getLayerInsetStart(i), layers.getLayerInsetEnd(i), layers.getLayerWidth(i), layers.getLayerHeight(i),
            layers.getLayerGravity(i),
        )
        return box(a) == box(b)
    }

    /** One drawable [layer] of a View's background as it draws (see [viewBackground]). */
    private fun layerBackground(view: View, layer: Drawable, density: Float, alpha: Float = 1f): DrawnBackground? {
        val tint = backgroundTint(view, layer)
        return when (layer) {
            // getColor() is the color with the drawable's alpha already applied (setAlpha).
            is ColorDrawable -> DrawnBackground(fill = tinted(layer.color, tint)?.let { visibleColor(ComposeVisuals.scaleAlpha(it, alpha)) })
            is GradientDrawable -> gradientBackground(layer, density, tint, view.width, view.height, alpha)
            else -> null
        }
    }

    /** A tint resolved for a drawable: its color, and its blend mode's name (null: the default, SRC_IN). */
    internal class Tint(val color: Int, val mode: String?)

    /**
     * A View's background tint (backgroundTint), resolved as the drawable resolves it at its own
     * state (Drawable.updateTintFilter: `getColorForState(getState(), Color.TRANSPARENT)`), with
     * the View's tint blend mode (null when unset: the drawable default, SRC_IN). View hands both
     * to its background (applyBackgroundTint), and a RippleDrawable to its layers. Null with no
     * tint. When [layer] has a color filter of its own, ColorDrawable and GradientDrawable both
     * draw with that one and ignore the tint's: that filter is the tint ([filterTint]).
     */
    internal fun backgroundTint(view: View, layer: Drawable): Tint? {
        layer.colorFilter?.let { return filterTint(it) }
        val color = stateColor(view.backgroundTintList, layer.state, android.graphics.Color.TRANSPARENT) ?: return null
        val mode = if (Build.VERSION.SDK_INT >= 29) view.backgroundTintBlendMode?.name else view.backgroundTintMode?.name
        return Tint(color, mode)
    }

    /**
     * An explicit drawable color filter as the tint it draws like: a BlendModeColorFilter or a
     * PorterDuffColorFilter's color and mode. Any other filter (lighting, a color matrix), or one
     * whose color or mode can't be read, is a tint in a mode [tinted] doesn't model, so the colors
     * it filters are left out, not reported unfiltered.
     */
    internal fun filterTint(filter: android.graphics.ColorFilter): Tint = when (filter) {
        is android.graphics.BlendModeColorFilter -> Tint(filter.color, filter.mode.name)
        is android.graphics.PorterDuffColorFilter -> porterDuffTint(filter) ?: UNDECODED_FILTER
        else -> UNDECODED_FILTER
    }

    /** A filter whose effect isn't modelled: [tinted] yields null for its mode. */
    private val UNDECODED_FILTER = Tint(0, "undecoded")

    /** A PorterDuffColorFilter's color and mode, which it keeps behind hidden getters (fields on older releases). */
    private fun porterDuffTint(filter: android.graphics.PorterDuffColorFilter): Tint? = try {
        val cls = android.graphics.PorterDuffColorFilter::class.java
        val color = try {
            cls.getDeclaredMethod("getColor").invoke(filter) as? Int
        } catch (_: NoSuchMethodException) {
            cls.getDeclaredField("mColor").apply { isAccessible = true }.get(filter) as? Int
        }
        val mode = try {
            cls.getDeclaredMethod("getMode").invoke(filter) as? android.graphics.PorterDuff.Mode
        } catch (_: NoSuchMethodException) {
            cls.getDeclaredField("mMode").apply { isAccessible = true }.get(filter) as? android.graphics.PorterDuff.Mode
        }
        if (color != null && mode != null) Tint(color, mode.name) else null
    } catch (_: Throwable) {
        null
    }

    /**
     * [argb] under [tint] ([ComposeVisuals.tinted]); unchanged with no tint. Null for a mode that
     * isn't modelled (MULTIPLY, SCREEN, ADD, …): the drawable is still tint-filtered, so its color
     * on screen is unknown, and the visual it would describe is left out, not reported untinted.
     */
    internal fun tinted(argb: Int, tint: Tint?): Int? = if (tint == null) argb else ComposeVisuals.tinted(argb, tint.color, tint.mode)

    /**
     * [argb] with its alpha modulated by a drawable's own alpha ([drawableAlpha], 0..255) the way
     * GradientDrawable.draw modulates its fill and stroke paints (modulateAlpha:
     * `alpha * (a + (a >> 7)) >> 8`): 255 keeps it, 0 clears it.
     */
    internal fun modulate(argb: Int, drawableAlpha: Int): Int {
        val a = drawableAlpha.coerceIn(0, 255)
        val alpha = ((argb ushr 24) * (a + (a shr 7))) shr 8
        return (alpha shl 24) or (argb and 0xFFFFFF)
    }

    /**
     * What a GradientDrawable draws, by its shape (GradientDrawable.draw): a RECTANGLE fills and
     * strokes its rect with its corner radius clamped to it ([gradientCornerRadius]); an OVAL
     * fills and strokes the oval in it, whose corner radius is half the rect's smaller side; a
     * LINE draws only its stroke, as a line across the middle, and a RING only its ring path:
     * neither is a fill, border or gradient of the box, and either is a custom draw when what it
     * draws is visible. [viewW] × [viewH] is the View's size, the drawable's bounds until the
     * View sets them. Every color is faded by the View's [alpha]. A rect the stroke inset leaves
     * empty (a stroke as wide as the smaller side) draws nothing: draw returns before it paints.
     */
    internal fun gradientBackground(
        drawable: GradientDrawable,
        density: Float,
        tint: Tint?,
        viewW: Int,
        viewH: Int,
        alpha: Float = 1f,
    ): DrawnBackground {
        val rect = drawnRectSize(drawable, viewW, viewH)
        if (rect != null && (rect.first <= 0f || rect.second <= 0f)) return DrawnBackground()
        val fill = gradientFill(drawable, tint, alpha)
        val border = gradientBorder(drawable, density, tint, alpha)
        val gradient = gradientInfo(drawable, tint, alpha)
        // A shape with no visible fill, gradient or stroke puts no pixels on screen, so its
        // corners round nothing; a transparent fill with a visible stroke still has them.
        val draws = fill != null || gradient != null || border != null
        return when (drawable.shape) {
            GradientDrawable.OVAL -> DrawnBackground(
                fill = fill,
                cornerRadiusPx = rect?.let { minOf(it.first, it.second) / 2f }?.takeIf { it > 0f && draws },
                border = border,
                gradient = gradient,
            )
            GradientDrawable.LINE -> DrawnBackground(customDraw = border != null)
            GradientDrawable.RING -> DrawnBackground(customDraw = draws)
            else -> DrawnBackground(fill, if (draws) gradientCornerRadius(drawable, rect) else null, border, gradient)
        }
    }

    /**
     * The size (w, h px) of the rect a GradientDrawable draws its shape in (ensureValidRect): its
     * bounds, or [viewW] × [viewH] before the View has set them (View.setBackgroundBounds, at
     * draw), inset by half its stroke width on every side. Null when neither has a size.
     */
    internal fun drawnRectSize(drawable: GradientDrawable, viewW: Int = 0, viewH: Int = 0): Pair<Float, Float>? {
        val b = drawable.bounds
        val (w, h) = when {
            !b.isEmpty -> Pair(b.width(), b.height())
            viewW > 0 && viewH > 0 -> Pair(viewW, viewH)
            else -> return null
        }
        val stroke = strokeWidthPx(drawable)
        return Pair(w - stroke, h - stroke)
    }

    /** The width GradientDrawable strokes at: its stroke paint's, else its GradientState's; 0 with no stroke. */
    private fun strokeWidthPx(drawable: GradientDrawable): Float {
        val paint = try {
            GradientDrawable::class.java.getDeclaredField("mStrokePaint").apply { isAccessible = true }.get(drawable)
        } catch (_: Throwable) {
            null
        }
        val width = (paint as? android.graphics.Paint)?.strokeWidth
            ?: (gradientStateField(drawable.constantState, "mStrokeWidth") as? Int)?.toFloat()
        return width?.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    private fun gradientStateField(state: Any?, name: String): Any? = try {
        state?.javaClass?.getDeclaredField(name)?.apply { isAccessible = true }?.get(state)
    } catch (_: Throwable) {
        null
    }

    private fun extractImageInfo(
        view: ImageView,
        elementId: String,
    ): Triple<String, String, String>? {
        val drawable = view.drawable ?: return null

        val resName =
            try {
                var cls: Class<*> = view.javaClass
                var field: java.lang.reflect.Field? = null
                while (cls != Any::class.java) {
                    try {
                        field = cls.getDeclaredField("mResource")
                        break
                    } catch (_: NoSuchFieldException) {
                    }
                    cls = cls.superclass
                }
                if (field != null) {
                    field.isAccessible = true
                    val resId = field.getInt(view)
                    if (resId != 0 && resId != -1) view.resources.getResourceEntryName(resId) else null
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

        val name = resName ?: elementId

        val imgType =
            when (drawable) {
                is VectorDrawable -> "vector"
                is BitmapDrawable -> if (resName != null) "raster" else "loaded"
                else -> "raster"
            }

        val dir = imageOutputDir ?: return Triple(name, imgType, "images/$name.png")
        val fileName = "$name.png"
        val file = java.io.File(dir, fileName)

        try {
            val origBounds = drawable.copyBounds()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val maxDim = 256
            val scale = if (w > maxDim || h > maxDim) maxDim.toFloat() / maxOf(w, h) else 1f
            val bw = (w * scale).toInt().coerceAtLeast(1)
            val bh = (h * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, bw, bh)
            drawable.draw(canvas)
            drawable.bounds = origBounds
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            bitmap.recycle()
        } catch (_: Exception) {
            return Triple(name, imgType, "images/$fileName")
        }

        return Triple(name, imgType, "images/$fileName")
    }

    // ---- GradientDrawable ----
    //
    // A GradientDrawable keeps what it draws on its GradientState (the drawable's ConstantState,
    // `drawable.constantState`), not on itself: the solid and stroke color lists, the stroke
    // width and the corner radii are GradientState fields (AOSP GradientDrawable.java), so a
    // field lookup on GradientDrawable finds none of them. The public getters (API 24) read the
    // state for us. On a device the state's own fields are hidden API: mSolidColors and mRadius
    // are @UnsupportedAppUsage(maxTargetSdk = P), blocked for an app that targets a later SDK,
    // and mStrokeColors and mStrokeWidth carry no annotation, so they are blocked everywhere.
    // The stroke is read from what draws it instead, the drawable's mStrokePaint, which is
    // @UnsupportedAppUsage with no maxTargetSdk and so readable at any target SDK.

    /**
     * A color list resolved the way a drawable resolves it at [state]: GradientDrawable fills and
     * strokes with `getColorForState(getState(), 0)` (setColor, setStroke, onStateChange), and a
     * tint is `getColorForState(getState(), Color.TRANSPARENT)` (Drawable.updateTintFilter).
     * [fallback] is the color for a state the list has no entry for. Null with no list.
     */
    internal fun stateColor(list: android.content.res.ColorStateList?, state: IntArray?, fallback: Int = 0): Int? =
        list?.getColorForState(state, fallback)

    /**
     * The color a GradientDrawable fills with, as its draw fills: its solid color list
     * (getColor()) at the drawable's current state, its alpha modulated by the drawable's own
     * ([modulate]; draw restores the paint afterwards, so the list holds the unmodulated color),
     * under the background [tint]. With no solid color and no gradient it fills nothing
     * (updateLocalState sets the fill to 0), unless it is tinted: draw then fills with
     * `mAlpha << 24` under the tint's filter (`if (colorFilter != null && st.mSolidColors ==
     * null)`), so a stroke-only shape is filled with the tint. Null when nothing visible is
     * filled ([visibleColor]): a gradient fill, no fill, or a fully transparent one.
     */
    internal fun gradientFill(drawable: GradientDrawable, tint: Tint? = null, alpha: Float = 1f): String? {
        val solid = stateColor(drawable.color, drawable.state)
        val fill = when {
            solid != null -> modulate(solid, drawable.alpha)
            tint != null && drawable.colors == null -> drawable.alpha shl 24
            else -> return null
        }
        return tinted(fill, tint)?.let { visibleColor(ComposeVisuals.scaleAlpha(it, alpha)) }
    }

    /**
     * A GradientDrawable's top-left corner radius in px as its RECTANGLE draws it in [rect] (the
     * drawn rect's w × h, [drawnRectSize]; null when unknown, and then unclamped). Per-corner radii
     * (getCornerRadii()) win, scaled as Path.addRoundRect draws them ([radiiTopLeft]); else its one
     * radius (getCornerRadius()), which draw clamps to half the rect's smaller side
     * (`Math.min(st.mRadius, Math.min(mRect.width(), mRect.height()) * 0.5f)`). Null when that
     * corner isn't rounded.
     */
    internal fun gradientCornerRadius(drawable: GradientDrawable, rect: Pair<Float, Float>? = drawnRectSize(drawable)): Float? {
        val radii = drawable.cornerRadii
        val radius = if (radii != null) {
            radiiTopLeft(radii, rect)
        } else {
            val r = drawable.cornerRadius
            if (rect == null) r else minOf(r, minOf(rect.first, rect.second) / 2f)
        }
        return radius?.takeIf { it.isFinite() && it > 0f }
    }

    /**
     * The top-left x radius a radii array ([tlx, tly, trx, try, brx, bry, blx, bly]) is drawn
     * with in [rect] (w, h; null: as declared), as Path.addRoundRect draws it through Skia's
     * SkRRect::setRectRadii: a corner with either radius at or below 0 is square (both zeroed),
     * and when the radii on any side overrun it every radius is scaled by one factor, the
     * smallest side / (its two radii) (SkRRect::scaleRadii, the factor Compose's corners take,
     * [ComposeVisuals.minRadius]). Null when that corner is square.
     */
    internal fun radiiTopLeft(radii: FloatArray, rect: Pair<Float, Float>?): Float? {
        val r = FloatArray(8) { i -> radii.getOrElse(i) { 0f }.takeIf { it.isFinite() } ?: 0f }
        for (corner in 0 until 4) {
            if (r[2 * corner] <= 0f || r[2 * corner + 1] <= 0f) {
                r[2 * corner] = 0f
                r[2 * corner + 1] = 0f
            }
        }
        if (r[0] <= 0f) return null
        if (rect == null) return r[0]
        val (w, h) = rect
        var scale = 1f
        scale = ComposeVisuals.minRadius(scale, r[0], r[2], w) // top: top-left x + top-right x
        scale = ComposeVisuals.minRadius(scale, r[3], r[5], h) // right: top-right y + bottom-right y
        scale = ComposeVisuals.minRadius(scale, r[4], r[6], w) // bottom: bottom-right x + bottom-left x
        scale = ComposeVisuals.minRadius(scale, r[7], r[1], h) // left: bottom-left y + top-left y
        return r[0] * scale
    }

    /**
     * The stroke a GradientDrawable draws, as a border: what its stroke paint holds
     * ([paintStroke]), or, when that paint can't be read, what its GradientState holds
     * ([stateStroke]), its alpha modulated by the drawable's, under the background [tint] and
     * faded by the View's [alpha]. Null when it draws no stroke.
     */
    internal fun gradientBorder(drawable: GradientDrawable, density: Float, tint: Tint? = null, alpha: Float = 1f): BorderInfo? {
        val paint = try {
            GradientDrawable::class.java.getDeclaredField("mStrokePaint").apply { isAccessible = true }.get(drawable)
        } catch (_: Throwable) {
            return stateStroke(drawable.constantState, drawable.state, density, drawable.alpha, tint, alpha)
        }
        // No stroke paint: setStroke was never called, and nothing is stroked.
        return (paint as? android.graphics.Paint)?.let { paintStroke(it, density, drawable.alpha, tint, alpha) }
    }

    /**
     * A GradientDrawable's stroke paint as a border. GradientDrawable sets that paint's width to
     * the stroke width and its color to the stroke's color list resolved at the drawable's
     * current state (setStroke, then onStateChange on every state change), and strokes with it
     * only when its width is above 0 (draw: `haveStroke = ... mStrokePaint.getStrokeWidth() > 0`),
     * at its alpha modulated by the drawable's [drawableAlpha], under its [tint] and faded by the
     * View's [alpha].
     */
    internal fun paintStroke(
        paint: android.graphics.Paint,
        density: Float,
        drawableAlpha: Int = 255,
        tint: Tint? = null,
        alpha: Float = 1f,
    ): BorderInfo? = strokeBorder(paint.strokeWidth, tinted(modulate(paint.color, drawableAlpha), tint)?.let { ComposeVisuals.scaleAlpha(it, alpha) }, density)

    /**
     * A stroke read from a GradientDrawable's GradientState ([state], the drawable's
     * ConstantState): mStrokeWidth (-1 for none) and mStrokeColors resolved at the drawable's
     * [drawableState] (no list strokes Color.TRANSPARENT), as GradientDrawable resolves them,
     * modulated by [drawableAlpha] and under [tint] as [paintStroke]. Null when the width can't
     * be read.
     */
    internal fun stateStroke(
        state: Any?,
        drawableState: IntArray?,
        density: Float,
        drawableAlpha: Int = 255,
        tint: Tint? = null,
        alpha: Float = 1f,
    ): BorderInfo? {
        if (state == null) return null
        val width = gradientStateField(state, "mStrokeWidth") as? Int ?: return null
        val colors = gradientStateField(state, "mStrokeColors") as? android.content.res.ColorStateList
        val color = stateColor(colors, drawableState) ?: android.graphics.Color.TRANSPARENT
        return strokeBorder(width.toFloat(), tinted(modulate(color, drawableAlpha), tint)?.let { ComposeVisuals.scaleAlpha(it, alpha) }, density)
    }

    /**
     * An ImageView's tint, resolved as its drawable resolves it at the view's current state
     * (Drawable.updateTintFilter: `getColorForState(getState(), Color.TRANSPARENT)`; ImageView
     * keeps a stateful drawable's state in step with its own), faded by the View's [alpha], as its
     * hex ([visibleColor]). Null with no tint, or a fully transparent one.
     */
    internal fun imageTint(view: ImageView, alpha: Float = 1f): String? =
        imageTintColor(view)?.let { visibleColor(ComposeVisuals.scaleAlpha(it, alpha)) }

    private fun imageTintColor(view: ImageView): Int? =
        stateColor(view.imageTintList, view.drawableState, android.graphics.Color.TRANSPARENT)

    /**
     * Whether an ImageView's tint leaves nothing of its image on screen: a fully transparent tint
     * in the default mode (imageTintBlendMode unset: the drawable's SRC_IN) or another mode that
     * replaces the image's pixels ([ComposeVisuals.tintHides], the rule Compose painters go
     * through too).
     */
    internal fun imageTintHides(view: ImageView): Boolean {
        val tint = imageTintColor(view) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) view.imageTintBlendMode?.name else view.imageTintMode?.name
        return ComposeVisuals.tintHides(tint, mode)
    }

    /**
     * A GradientDrawable stroke as a border, or null when it draws nothing: no width, or a stroke
     * color with zero alpha. GradientDrawable.draw strokes only when the stroke paint's alpha is
     * above 0 (`haveStroke = currStrokeAlpha > 0 && ...`), and setStroke with no color list
     * strokes Color.TRANSPARENT, so a missing color is a transparent one. The color goes through
     * the one visible-color rule ([visibleColor]).
     */
    internal fun strokeBorder(strokeWidthPx: Float, strokeColor: Int?, density: Float): BorderInfo? {
        if (!strokeWidthPx.isFinite() || strokeWidthPx <= 0f || strokeColor == null) return null
        val color = visibleColor(strokeColor) ?: return null
        return BorderInfo(width = strokeWidthPx / density, color = color)
    }

    /**
     * A GradientDrawable's gradient: its stops (getColors()) as its fill paint draws them, each
     * with its alpha modulated by the drawable's (draw sets the paint to opaque black under the
     * shader, then modulates its alpha), under the background [tint] and faded by the View's
     * [alpha]. Null with no gradient to report ([gradientStops]).
     */
    internal fun gradientInfo(drawable: GradientDrawable, tint: Tint? = null, alpha: Float = 1f): GradientInfo? {
        // One stop whose tinted color is unknown (a tint mode that isn't modelled) leaves the gradient unknown.
        val colors = drawable.colors?.map { ComposeVisuals.scaleAlpha(tinted(modulate(it, drawable.alpha), tint) ?: return null, alpha) }?.toIntArray()
        val stops = gradientStops(colors) ?: return null

        val type =
            when (drawable.gradientType) {
                GradientDrawable.LINEAR_GRADIENT -> "linear"
                GradientDrawable.RADIAL_GRADIENT -> "radial"
                GradientDrawable.SWEEP_GRADIENT -> "sweep"
                else -> "linear"
            }

        val orientation =
            if (drawable.gradientType == GradientDrawable.LINEAR_GRADIENT) {
                drawable.orientation?.name?.lowercase()
            } else {
                null
            }

        return GradientInfo(
            type = type,
            colors = stops,
            orientation = orientation,
        )
    }

    /**
     * A View gradient's stops as hex, or null when it is no gradient to report: fewer than two
     * stops, or every stop fully transparent, which draws nothing (the same check as Compose
     * gradients, [ComposeVisuals.gradientVisible]). A transparent stop among visible ones is
     * kept: it still shapes the fade.
     */
    internal fun gradientStops(colors: IntArray?): List<String>? {
        if (colors == null || colors.size < 2) return null
        return colors.map { ComposeVisuals.argbToHex(it) }.takeIf { ComposeVisuals.gradientVisible(it) }
    }

    private val externalRenderPatterns =
        listOf(
            "MapView",
            "MapFragment",
            "SurfaceView",
            "TextureView",
            "VideoView",
            "WebView",
            "GLSurfaceView",
            "ExoPlayerView",
        )

    private fun isExternalSurface(view: View): Boolean {
        var cls: Class<*>? = view.javaClass
        while (cls != null && cls != View::class.java) {
            val name = cls.simpleName
            if (externalRenderPatterns.any { name.contains(it) }) return true
            cls = cls.superclass
        }
        return false
    }

    private fun hasClickableAncestor(view: View): Boolean {
        var parent = view.parent
        while (parent is View) {
            if ((parent as View).isClickable || (parent as View).isLongClickable) return true
            parent = parent.getParent()
        }
        return false
    }

    private fun findClickableAncestorBounds(view: View): Bounds? {
        var parent = view.parent
        while (parent is View) {
            val p = parent as View
            if (p.isClickable || p.isLongClickable) {
                val rect = android.graphics.Rect()
                if (p.getGlobalVisibleRect(rect)) {
                    return Bounds(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
                }
            }
            parent = p.parent
        }
        return null
    }

    private fun extractResourceId(view: View): String? {
        if (view.id == View.NO_ID) return null
        return try {
            view.resources.getResourceEntryName(view.id)
        } catch (_: Exception) {
            null
        }
    }

    private fun boundsContains(
        outer: Bounds,
        inner: Bounds,
        tolerance: Int = 2,
    ): Boolean =
        inner.x >= outer.x - tolerance && inner.y >= outer.y - tolerance &&
            inner.x + inner.w <= outer.x + outer.w + tolerance &&
            inner.y + inner.h <= outer.y + outer.h + tolerance

    private fun slugify(s: String): String =
        s
            .map { c -> if (c.isLetterOrDigit()) c.lowercaseChar() else '_' }
            .joinToString("")
            .split("_")
            .filter { it.isNotEmpty() }
            .joinToString("_")
}
