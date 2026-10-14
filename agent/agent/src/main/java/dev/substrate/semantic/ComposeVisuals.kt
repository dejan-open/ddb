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
    /** Bounds of the first drawing modifier, when it differs from the node's (an outer padding). */
    val drawBounds: Bounds? = null,
) {
    val draws: Boolean
        // A shadow is drawn on its own, with no background or content under it.
        get() = background != null || gradient != null || border != null || imageType != null || elevation != null
}

internal object ComposeVisuals {
    const val UNSPECIFIED_COLOR = 16L

    fun read(
        layoutNode: Any,
        density: Float,
        fontScale: Float,
        resources: Resources?,
        nodeBounds: Bounds?,
    ): ComposeDrawInfo {
        val infos = invoke0(layoutNode, "getModifierInfo") as? List<*> ?: return ComposeDrawInfo()
        var v = ComposeDrawInfo()
        var outerPad: FloatArray? = null
        var innerPad: FloatArray? = null
        var seenDraw = false
        // The first drawing modifier owns the draw bounds, even when they equal the node's
        // (recorded as null): a later modifier inside padding must not replace them.
        var drawBoundsTaken = false
        fun firstDrawBounds(info: Any): Bounds? {
            if (drawBoundsTaken) return v.drawBounds
            drawBoundsTaken = true
            return modifierBounds(info, nodeBounds)
        }
        for (info in infos) {
            if (info == null) continue
            val element = invoke0(info, "getModifier") ?: continue
            when (element.javaClass.simpleName) {
                "BackgroundElement" -> {
                    seenDraw = true
                    val brush = field(element, "brush")
                    val color = colorHex(element, (field(element, "color") as? Long) ?: UNSPECIFIED_COLOR)
                    val grad = gradient(brush, element)
                    val solid = color ?: solidBrushHex(brush, element)
                    v = v.copy(
                        background = v.background ?: solid,
                        gradient = v.gradient ?: grad,
                        cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
                        drawBounds = firstDrawBounds(info),
                    )
                }
                "BorderModifierNodeElement" -> {
                    seenDraw = true
                    val width = borderWidthDp(field(element, "width") as? Float, density)
                    val brush = field(element, "brush")
                    val color = solidBrushHex(brush, element)
                    // A solid border with no color left is a fully transparent one: not drawn.
                    val invisible = color == null && brush?.javaClass?.simpleName == "SolidColor"
                    if (width != null && !invisible && v.border == null) {
                        v = v.copy(
                            border = BorderInfo(width = width, color = color),
                            cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
                            drawBounds = firstDrawBounds(info),
                        )
                    }
                }
                "PainterElement" -> {
                    seenDraw = true
                    val painter = field(element, "painter")
                    val type = painterType(painter)
                    if (type != null && v.imageType == null) {
                        v = v.copy(
                            imageType = type,
                            imageTint = colorFilterHex(field(element, "colorFilter"), element),
                            drawBounds = firstDrawBounds(info),
                        )
                    } else if (painter?.javaClass?.simpleName == "ColorPainter" && v.background == null) {
                        // ColorPainter fills its bounds with one color: that is a background, not an image.
                        val fill = (field(painter, "color") as? Long)?.let { colorHex(element, it) }
                        if (fill != null) {
                            v = v.copy(
                                background = fill,
                                drawBounds = firstDrawBounds(info),
                            )
                        }
                    }
                }
                "ShadowGraphicsLayerElement" -> {
                    val elev = (field(element, "elevation") as? Float)?.takeIf { it.isFinite() && it > 0f }
                    v = v.copy(
                        elevation = v.elevation ?: elev,
                        cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
                    )
                }
                "GraphicsLayerElement" -> {
                    val alpha = field(element, "alpha") as? Float
                    val shadow = (field(element, "shadowElevation") as? Float)?.takeIf { it > 0f }?.let { it / density }
                    val clip = field(element, "clip") as? Boolean ?: false
                    v = v.copy(
                        alpha = if (alpha != null && alpha < 1f) minOf(alpha, v.alpha ?: 1f) else v.alpha,
                        elevation = v.elevation ?: shadow,
                        cornerRadius = v.cornerRadius
                            ?: if (clip || shadow != null) cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds) else null,
                    )
                }
                "PaddingElement" -> {
                    val p = floatArrayOf(
                        dp(field(element, "top")),
                        dp(field(element, "bottom")),
                        dp(field(element, "start")),
                        dp(field(element, "end")),
                    )
                    if (seenDraw) innerPad = add(innerPad, p) else outerPad = add(outerPad, p)
                }
                "TextStringSimpleElement", "TextAnnotatedStringElement" -> {
                    val style = field(element, "style")
                    val producer = field(element, "color")
                    val color = textColorHex(producer, style, element)
                    val maxLines = (field(element, "maxLines") as? Int)?.takeIf { it in 1 until Int.MAX_VALUE }
                    v = v.copy(
                        font = v.font ?: font(style, fontScale, resources),
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
        )
    }

    // ---- pure helpers (unit-tested) ----

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
     * Border width in dp. `Dp.Hairline` is stored as 0 and Compose draws it as one physical
     * pixel, so 0 reports one pixel in dp rather than "no border".
     */
    fun borderWidthDp(width: Float?, density: Float): Float? = when {
        width == null || !width.isFinite() || width < 0f -> null
        width == 0f -> if (density.isFinite() && density > 0f) 1f / density else null
        else -> width
    }

    fun argbToHex(color: Int): String {
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return if (a == 255) String.format("#%02X%02X%02X", r, g, b) else String.format("#%02X%02X%02X%02X", a, r, g, b)
    }

    // ---- reflection ----

    private fun font(style: Any?, fontScale: Float, resources: Resources?): FontInfo? {
        if (style == null) return null
        val sizeSp = textUnitValue(style, invokePrefix(style, "getFontSize") as? Long) ?: return null
        val family = invoke0(style, "getFontFamily")
        var weight = invoke0(invoke0(style, "getFontWeight"), "getWeight") as? Int
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
            else -> familyName = family.javaClass.simpleName.lowercase()
        }
        return FontInfo(family = familyName, weight = weightName(weight ?: 400), size = sizeSp * fontScale)
    }

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

    private fun textUnitValue(anchor: Any, packed: Long?): Float? {
        if (packed == null) return null
        return try {
            val cls = Class.forName("androidx.compose.ui.unit.TextUnit", false, anchor.javaClass.classLoader)
            val m = cls.methods.firstOrNull { it.name.startsWith("getValue-impl") && it.parameterCount == 1 }
            (m?.invoke(null, packed) as? Float)?.takeIf { it.isFinite() && it > 0f }
        } catch (_: Throwable) {
            null
        }
    }

    private fun textColorHex(producer: Any?, style: Any?, anchor: Any): String? {
        val stylePacked = style?.let { invokePrefix(it, "getColor") as? Long }
        val packed = textColorPacked(producer != null, producerPacked(producer), stylePacked) ?: return null
        return colorHex(anchor, packed)
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

    private fun colorFilterHex(filter: Any?, anchor: Any): String? {
        if (filter == null) return null
        return (field(filter, "color") as? Long)?.let { colorHex(anchor, it) }
    }

    private fun solidBrushHex(brush: Any?, anchor: Any): String? {
        if (brush?.javaClass?.simpleName != "SolidColor") return null
        return (field(brush, "value") as? Long)?.let { colorHex(anchor, it) }
    }

    private fun gradient(brush: Any?, anchor: Any): GradientInfo? {
        val name = brush?.javaClass?.simpleName ?: return null
        val type = when (name) {
            "LinearGradient" -> "linear"
            "RadialGradient" -> "radial"
            "SweepGradient" -> "sweep"
            else -> return null
        }
        val colors = (field(brush, "colors") as? List<*>)?.mapNotNull { c ->
            val packed = c?.javaClass?.methods?.firstOrNull { it.name == "unbox-impl" }?.invoke(c) as? Long
            // A transparent stop is still a stop: Compose hands every color to the shader,
            // so a fade to Color.Transparent keeps its #00000000 end.
            packed?.let { colorHex(anchor, it, keepTransparent = true) }
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

    /** Radius in dp of the top-start corner, the same corner the View path reports. */
    private fun cornerRadius(shape: Any?, density: Float, nodeBounds: Bounds?): Float? {
        if (shape == null) return null
        val corner = field(shape, "topStart") ?: return null
        val r = when (corner.javaClass.simpleName) {
            "DpCornerSize" -> field(corner, "size") as? Float
            "PxCornerSize" -> (field(corner, "size") as? Float)?.div(density)
            "PercentCornerSize" -> {
                val pct = field(corner, "percent") as? Float
                if (pct != null && nodeBounds != null) pct / 100f * minOf(nodeBounds.w, nodeBounds.h) / density else null
            }
            else -> null
        }
        return r?.takeIf { it.isFinite() && it > 0f }
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

    private fun colorHex(anchor: Any, packed: Long, keepTransparent: Boolean = false): String? {
        if (packed == UNSPECIFIED_COLOR) return null
        return try {
            val kt = Class.forName("androidx.compose.ui.graphics.ColorKt", false, anchor.javaClass.classLoader)
            val m = kt.methods.firstOrNull {
                it.name.startsWith("toArgb") && it.parameterCount == 1 && it.parameterTypes[0] == java.lang.Long.TYPE
            }
            (m?.invoke(null, packed) as? Int)?.let { argbHex(it, keepTransparent) }
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
