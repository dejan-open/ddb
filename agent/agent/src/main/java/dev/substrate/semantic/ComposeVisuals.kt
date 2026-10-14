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
     * The node draws through a custom draw modifier (drawBehind; a Compose Canvas is a Spacer
     * with one). What it draws can't be read, but it is on screen like any other visual.
     */
    val customDraw: Boolean = false,
    /** Bounds of the first drawing modifier, when it differs from the node's (an outer padding). */
    val drawBounds: Bounds? = null,
) {
    val draws: Boolean
        // A shadow is drawn on its own, with no background or content under it.
        get() = background != null || gradient != null || border != null || imageType != null || elevation != null ||
            customDraw
}

internal object ComposeVisuals {
    const val UNSPECIFIED_COLOR = 16L

    /** Elements that put pixels on screen (a layer at alpha 0 hides those after it). */
    private val DRAWING_ELEMENTS = setOf(
        "BackgroundElement", "BorderModifierNodeElement", "PainterElement", "ShadowGraphicsLayerElement",
        "DrawBehindElement",
    )

    /**
     * Modifiers that draw arbitrary content and have no fields to decode: Modifier.drawBehind,
     * which a Compose Canvas is built on. drawWithContent and drawWithCache are left out, since
     * they often only wrap drawContent() and draw nothing of their own.
     */
    private val CUSTOM_DRAW_ELEMENTS = setOf("DrawBehindElement")

    /** Whether a modifier, known by its element class name, is a custom draw. */
    fun isCustomDraw(elementName: String): Boolean = elementName in CUSTOM_DRAW_ELEMENTS

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
            val name = element.javaClass.simpleName
            // Inside a layer at alpha 0 nothing is drawn; modifiers before it still are.
            if (layerAlpha() <= 0f && name in DRAWING_ELEMENTS) continue
            if (isCustomDraw(name)) {
                // A draw like a background: it sets the inside/outside padding boundary and the
                // draw bounds, and inside an alpha-0 layer it was skipped above.
                seenDraw = true
                v = v.copy(customDraw = true, drawBounds = firstDrawBounds(info))
                continue
            }
            when (name) {
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
                        cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
                        drawBounds = firstDrawBounds(info),
                    )
                }
                "BorderModifierNodeElement" -> {
                    val width = borderWidthDp(field(element, "width") as? Float, density)
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
                            cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
                            drawBounds = firstDrawBounds(info),
                        )
                    }
                }
                "PainterElement" -> {
                    // Image(alpha = 0f) / paint(alpha = 0f) draws nothing.
                    val painterAlpha = (field(element, "alpha") as? Float)?.takeIf { it.isFinite() } ?: 1f
                    if (painterAlpha <= 0f) continue
                    val painter = field(element, "painter")
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
                    val type = painterType(painter)
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
                    // A shadow is a draw: it sets the inside/outside boundary like a background.
                    if (elev != null) seenDraw = true
                    v = v.copy(
                        elevation = v.elevation ?: elev,
                        cornerRadius = v.cornerRadius ?: cornerRadius(field(element, "shape"), density, ownBounds(info) ?: nodeBounds),
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
                        )
                    } else {
                        blockLayer(element, composeDensity, density, fontScale, measuredSize(info, nodeBounds))
                    } ?: continue
                    val alpha = layer.alpha?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    if (alpha != null && alpha < 1f) ownAlpha *= alpha
                    // A layer's shadow is drawn with the layer's own alpha: none at 0.
                    val shadow = layer.shadowPx
                        ?.takeIf { it.isFinite() && it > 0f && layerAlpha() > 0f && layer.shadowColorsVisible }
                        ?.let { it / density }
                    if (shadow != null) seenDraw = true
                    v = v.copy(
                        alpha = if (ownAlpha < 1f) ownAlpha else v.alpha,
                        elevation = v.elevation ?: shadow,
                        cornerRadius = v.cornerRadius
                            ?: if (layer.clip || shadow != null) cornerRadius(layer.shape, density, ownBounds(info) ?: nodeBounds) else null,
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
                    val color = textColorHex(producer, style, element, layerAlpha())
                    val maxLines = (field(element, "maxLines") as? Int)?.takeIf { it in 1 until Int.MAX_VALUE }
                    v = v.copy(
                        font = v.font ?: font(style, composeDensity, fontScale, resources),
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

    private fun font(style: Any?, composeDensity: Any?, fontScale: Float, resources: Resources?): FontInfo? {
        if (style == null) return null
        val sizeSp = fontSizeSp(textUnitValue(style, invokePrefix(style, "getFontSize") as? Long)) ?: return null
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
            else -> familyName = classFamilyName(family.javaClass.simpleName)
        }
        return FontInfo(family = familyName, weight = weightName(weight ?: 400), size = spToDp(composeDensity, sizeSp) ?: sizeSp * fontScale)
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
            // NaN is TextUnit.Unspecified; fontSizeSp resolves it to the default.
            m?.invoke(null, packed) as? Float
        } catch (_: Throwable) {
            null
        }
    }

    private fun textColorHex(producer: Any?, style: Any?, anchor: Any, alpha: Float): String? {
        // A TextStyle brush wins over the color producer and the style color, as in
        // Compose's text draw. A gradient or shader has no single color to report (and is
        // not black); a solid brush is its color at the style's alpha.
        val styleBrush = style?.let { invokePrefix(it, "getBrush") }
        if (styleBrush != null) {
            val styleAlpha = (invokePrefix(style, "getAlpha") as? Float)?.takeIf { it.isFinite() } ?: 1f
            return solidBrushHex(styleBrush, anchor, alpha * styleAlpha)
        }
        val stylePacked = style?.let { invokePrefix(it, "getColor") as? Long }
        val packed = textColorPacked(producer != null, producerPacked(producer), stylePacked) ?: return null
        return colorHex(anchor, packed, alpha = alpha)
    }

    private class LayerProps(
        val alpha: Float?,
        val shadowPx: Float?,
        val clip: Boolean,
        val shape: Any?,
        val shadowColorsVisible: Boolean,
    )

    /**
     * The layer properties a `graphicsLayer { … }` block sets, read by running the block on a
     * fresh ReusableGraphicsLayerScope with the screen density (so `8.dp.toPx()` resolves as
     * it does on screen). Null when any of that is unavailable in this Compose release.
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
            (block as Function1<Any?, Any?>).invoke(scope)
            LayerProps(
                alpha = invokePrefix(scope, "getAlpha") as? Float,
                shadowPx = invokePrefix(scope, "getShadowElevation") as? Float,
                clip = invokePrefix(scope, "getClip") as? Boolean ?: false,
                shape = invokePrefix(scope, "getShape"),
                shadowColorsVisible = shadowColorsVisible(
                    argb(scope, field(scope, "ambientShadowColor") as? Long),
                    argb(scope, field(scope, "spotShadowColor") as? Long),
                ),
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

    /**
     * The measured size of the coordinator a modifier sits on (LayoutCoordinates.size): before
     * a layer's scale or rotation and any ancestor clip, which window bounds include. It is the
     * size Compose gives a `graphicsLayer { }` block. Falls back to the node's bounds when the
     * coordinator can't be read.
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
