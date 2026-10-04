package android.graphics

/**
 * The static half of Android's Color: packed-ARGB helpers and HSV
 * conversions, with Android's exact arithmetic (HSV in degrees, 0..1
 * saturation and value; channels rounded, not truncated).
 */
object Color {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val TRANSPARENT = 0
    const val RED = 0xFFFF0000.toInt()
    const val GREEN = 0xFF00FF00.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val GRAY = 0xFF888888.toInt()

    @JvmStatic fun alpha(color: Int): Int = color ushr 24
    @JvmStatic fun red(color: Int): Int = (color shr 16) and 0xFF
    @JvmStatic fun green(color: Int): Int = (color shr 8) and 0xFF
    @JvmStatic fun blue(color: Int): Int = color and 0xFF

    @JvmStatic fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int =
        (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    @JvmStatic fun argb(alpha: Float, red: Float, green: Float, blue: Float): Int =
        argb((alpha * 255f + 0.5f).toInt(), (red * 255f + 0.5f).toInt(), (green * 255f + 0.5f).toInt(), (blue * 255f + 0.5f).toInt())

    @JvmStatic fun rgb(red: Int, green: Int, blue: Int): Int = argb(255, red, green, blue)
    @JvmStatic fun rgb(red: Float, green: Float, blue: Float): Int = argb(1f, red, green, blue)

    /** Relative luminance, as Android's (sRGB transfer undone, Rec. 709 weights). */
    @JvmStatic fun luminance(color: Int): Float {
        fun lin(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4) }
        return (0.2126 * lin(red(color)) + 0.7152 * lin(green(color)) + 0.0722 * lin(blue(color))).toFloat()
    }

    /** "#RRGGBB", "#AARRGGBB" or one of Android's named colours. */
    @JvmStatic fun parseColor(colorString: String): Int {
        if (colorString.startsWith('#')) {
            val hex = colorString.substring(1)
            val value = hex.toLong(16)
            return when (hex.length) {
                6 -> (value or 0xFF000000L).toInt()
                8 -> value.toInt()
                else -> throw IllegalArgumentException("Unknown color")
            }
        }
        return NAMED[colorString.lowercase()] ?: throw IllegalArgumentException("Unknown color")
    }

    @JvmStatic fun colorToHSV(color: Int, hsv: FloatArray) = RGBToHSV(red(color), green(color), blue(color), hsv)

    @JvmStatic fun RGBToHSV(red: Int, green: Int, blue: Int, hsv: FloatArray) {
        val r = red / 255f; val g = green / 255f; val b = blue / 255f
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val delta = max - min
        var h = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (h < 0f) h += 360f
        hsv[0] = h
        hsv[1] = if (max == 0f) 0f else delta / max
        hsv[2] = max
    }

    @JvmStatic fun HSVToColor(hsv: FloatArray): Int = HSVToColor(255, hsv)

    @JvmStatic fun HSVToColor(alpha: Int, hsv: FloatArray): Int {
        val h = ((hsv[0] % 360f) + 360f) % 360f
        val s = hsv[1].coerceIn(0f, 1f)
        val v = hsv[2].coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60f) % 2f - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255f + 0.5f).toInt(), ((g + m) * 255f + 0.5f).toInt(), ((b + m) * 255f + 0.5f).toInt())
    }

    private val NAMED = mapOf(
        "black" to BLACK, "white" to WHITE, "red" to RED, "green" to GREEN, "blue" to BLUE,
        "gray" to GRAY, "grey" to GRAY, "transparent" to TRANSPARENT,
        "yellow" to 0xFFFFFF00.toInt(), "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(),
        "darkgray" to 0xFF444444.toInt(), "lightgray" to 0xFFCCCCCC.toInt(),
    )
}
