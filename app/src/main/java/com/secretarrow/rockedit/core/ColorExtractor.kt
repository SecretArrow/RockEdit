package com.secretarrow.rockedit.core

/**
 * Extracts color notations from the document for the inline color preview
 * (v0.11.0). Supported notations:
 * - #RGB, #RGBA, #RRGGBB, #RRGGBBAA (case-insensitive hex)
 * - rgb(r,g,b) / rgba(r,g,b,a) with integer channels 0..255 and alpha 0..1
 * - hsl(h,s%,l%) / hsla(h,s%,l%,a) with h 0..360, s/l 0..100
 * - CSS named colors (full standard set below)
 *
 * Defensive contract:
 * - Malformed notations are SKIPPED (fail-safe), never fatal: a color
 *   preview tool must not punish the user for a half-typed value. The
 *   number of skipped occurrences is reported in [ColorSummary.skipped].
 * - Occurrences are capped at [MAX_OCCURRENCES]; exceeding sets truncated.
 * - Overlap is resolved longest-first so "#aabbcc" is never parsed as
 *   "#aab" + garbage; "rgb(" inside "rgba(" is handled by ordering.
 * - Results are plain (start, end, argb) triples: the UI can jump to any
 *   occurrence and paint swatches without re-parsing.
 *
 * Documented assumptions:
 * - Percent channels (rgb(50%, 0, 0)) are skipped in v1 (rare in code).
 * - Alpha floats use '.' only (no locale commas).
 */
object ColorExtractor {

    const val MAX_OCCURRENCES = 1_000

    data class ColorOccurrence(
        val start: Int,
        val end: Int,
        val argb: Long,
        val source: String
    )

    data class ColorSummary(
        val colors: List<ColorOccurrence>,
        val skipped: Int,
        val truncated: Boolean
    )

    fun extract(text: String): ColorSummary {
        if (text.length > 2_000_000) {
            // Same hard cap as the formatter: bounded memory on huge docs.
            return ColorSummary(emptyList(), 0, false)
        }
        val colors = ArrayList<ColorOccurrence>()
        var skipped = 0
        var index = 0
        while (index < text.length) {
            if (colors.size >= MAX_OCCURRENCES) {
                return ColorSummary(colors, skipped, true)
            }
            val c = text[index]
            when {
                c == '#' -> {
                    val consumed = consumeHex(text, index)?.let { (end, value) ->
                        colors.add(ColorOccurrence(index, end, value, text.substring(index, end)))
                        end
                    }
                    if (consumed != null) {
                        index = consumed
                    } else {
                        skipped++
                        index++
                    }
                }
                c == 'r' || c == 'R' || c == 'h' || c == 'H' -> {
                    val advanced = consumeFunc(text, index, colors, isHsl = c == 'h' || c == 'H')
                    if (advanced == null) {
                        // Not a color function (e.g. "red", "hotpink"):
                        // fall through to named-color word handling.
                        index = consumeWord(text, index, colors)
                    } else {
                        skipped += advanced.first
                        index = advanced.second
                    }
                }
                c.isLetter() -> {
                    index = consumeWord(text, index, colors)
                }
                else -> index++
            }
        }
        return ColorSummary(colors, skipped, false)
    }

    // ---------------------------------------------------------------- hex

    /** Returns (endIndex, argb) or null when the run is not a valid hex color. */
    private fun consumeHex(text: String, start: Int): Pair<Int, Long>? {
        var end = start + 1
        while (end < text.length && text[end].isHexChar()) end++
        val len = end - start - 1
        if (len != 3 && len != 4 && len != 6 && len != 8) return null
        // Must not be part of a longer word (e.g. "#beefc0de" handled as 8,
        // but "code#abc" inside an identifier is fine to match).
        val hex = text.substring(start + 1, end)
        val r: Int
        val g: Int
        val b: Int
        val a: Int
        when (len) {
            3 -> {
                r = hex[0].dupDigit(); g = hex[1].dupDigit(); b = hex[2].dupDigit(); a = 0xFF
            }
            4 -> {
                r = hex[0].dupDigit(); g = hex[1].dupDigit(); b = hex[2].dupDigit()
                a = hex[3].dupDigit()
            }
            6 -> {
                r = hex.substring(0, 2).toInt(16); g = hex.substring(2, 4).toInt(16)
                b = hex.substring(4, 6).toInt(16); a = 0xFF
            }
            else -> {
                r = hex.substring(0, 2).toInt(16); g = hex.substring(2, 4).toInt(16)
                b = hex.substring(4, 6).toInt(16); a = hex.substring(6, 8).toInt(16)
            }
        }
        return end to argb(a, r, g, b)
    }

    private fun Char.isHexChar(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun Char.dupDigit(): Int {
        val v = Character.digit(this, 16)
        return (v shl 4) or v
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Long =
        ((a and 0xFF).toLong() shl 24) or
            ((r and 0xFF).toLong() shl 16) or
            ((g and 0xFF).toLong() shl 8) or
            (b and 0xFF).toLong()

    // --------------------------------------------------------------- rgb/hsl

    /**
     * Tries to parse rgb()/rgba()/hsl()/hsla() starting at [start]. The
     * longest word (rgba/hsla) always wins over the shorter prefix.
     * Returns null when the text at [start] is not a color function at all
     * (the caller falls through to named-word handling); otherwise returns
     * (skippedCount, newIndex): on success newIndex jumps past the closing
     * paren; on malformed args the whole occurrence is skipped.
     */
    private fun consumeFunc(
        text: String,
        start: Int,
        out: MutableList<ColorOccurrence>,
        isHsl: Boolean
    ): Pair<Int, Int>? {
        val alphaWord = if (isHsl) "hsla" else "rgba"
        val plainWord = if (isHsl) "hsl" else "rgb"
        val hasAlpha = text.regionMatchesSafe(start, alphaWord)
        val hasPlain = !hasAlpha && text.regionMatchesSafe(start, plainWord)
        val wordLen = when {
            hasAlpha -> 4
            hasPlain -> 3
            else -> return null
        }
        var i = start + wordLen
        // Skip whitespace, then expect '('. A bare "rgb" word is not a
        // function: fall through so named colors starting with r/h still work.
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '(') return null
        i++
        val close = text.indexOf(')', i)
        if (close < 0) return 1 to start + 1 // unbalanced: skipped, not fatal
        val body = text.substring(i, close)
        val parts = body.split(',').map { it.trim() }
        val parsed = if (isHsl) parseHsl(parts) else parseRgb(parts)
        if (parsed == null) {
            return 1 to close + 1 // malformed args: skip whole occurrence
        }
        if (out.size < MAX_OCCURRENCES) {
            out.add(ColorOccurrence(start, close + 1, parsed, text.substring(start, close + 1)))
        }
        return 0 to close + 1
    }

    private fun String.regionMatchesSafe(start: Int, word: String): Boolean =
        start + word.length <= length && substring(start, start + word.length).equals(word, ignoreCase = true)

    private fun parseRgb(parts: List<String>): Long? {
        if (parts.size !in 3..4) return null
        val r = parts[0].toIntOrNull() ?: return null
        val g = parts[1].toIntOrNull() ?: return null
        val b = parts[2].toIntOrNull() ?: return null
        if (r !in 0..255 || g !in 0..255 || b !in 0..255) return null
        val a = if (parts.size == 4) parseAlpha(parts[3]) ?: return null else 1.0
        return argb((a * 255).toInt(), r, g, b)
    }

    private fun parseHsl(parts: List<String>): Long? {
        if (parts.size !in 3..4) return null
        val h = parts[0].removeSuffix("°").toDoubleOrNull() ?: return null
        val sPct = parts[1].removeSuffix("%")
        val lPct = parts[2].removeSuffix("%")
        val s = sPct.toDoubleOrNull() ?: return null
        val l = lPct.toDoubleOrNull() ?: return null
        if (h !in 0.0..360.0 || s !in 0.0..100.0 || l !in 0.0..100.0) return null
        val a = if (parts.size == 4) parseAlpha(parts[3]) ?: return null else 1.0
        val (r, g, b) = hslToRgb(h, s / 100.0, l / 100.0)
        return argb((a * 255).toInt(), r, g, b)
    }

    private fun parseAlpha(raw: String): Double? {
        val v = raw.toDoubleOrNull() ?: return null
        return if (v in 0.0..1.0) v else null
    }

    /** Standard HSL -> RGB conversion (0..255 channels). */
    private fun hslToRgb(h: Double, s: Double, l: Double): Triple<Int, Int, Int> {
        val c = (1.0 - Math.abs(2.0 * l - 1.0)) * s
        val hp = h / 60.0
        val x = c * (1.0 - Math.abs(hp % 2.0 - 1.0))
        val (r1, g1, b1) = when {
            hp < 1.0 -> Triple(c, x, 0.0)
            hp < 2.0 -> Triple(x, c, 0.0)
            hp < 3.0 -> Triple(0.0, c, x)
            hp < 4.0 -> Triple(0.0, x, c)
            hp < 5.0 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        val m = l - c / 2.0
        return Triple(
            ((r1 + m) * 255.0).toInt().coerceIn(0, 255),
            ((g1 + m) * 255.0).toInt().coerceIn(0, 255),
            ((b1 + m) * 255.0).toInt().coerceIn(0, 255)
        )
    }

    // --------------------------------------------------------------- named

    private fun consumeWord(text: String, start: Int, colors: MutableList<ColorOccurrence>): Int {
        var end = start
        while (end < text.length && (text[end].isLetter() || text[end] == '-')) end++
        val word = text.substring(start, end).lowercase()
        val argb = if (word.length >= 3) NAMED_COLORS[word] else null
        if (argb != null && colors.size < MAX_OCCURRENCES) {
            colors.add(ColorOccurrence(start, end, argb, word))
        }
        return if (end > start) end else start + 1
    }

    /** Full CSS Level 4 named color set (X11 subset), lowercase keys. */
    private val NAMED_COLORS: Map<String, Long> = mapOf(
        "aliceblue" to 0xFFF0F8FF, "antiquewhite" to 0xFFFAEBD7, "aqua" to 0xFF00FFFF,
        "aquamarine" to 0xFF7FFFD4, "azure" to 0xFFF0FFFF, "beige" to 0xFFF5F5DC,
        "bisque" to 0xFFFFE4C4, "black" to 0xFF000000, "blanchedalmond" to 0xFFFFEBCD,
        "blue" to 0xFF0000FF, "blueviolet" to 0xFF8A2BE2, "brown" to 0xFFA52A2A,
        "burlywood" to 0xFFDEB887, "cadetblue" to 0xFF5F9EA0, "chartreuse" to 0xFF7FFF00,
        "chocolate" to 0xFFD2691E, "coral" to 0xFFFF7F50, "cornflowerblue" to 0xFF6495ED,
        "cornsilk" to 0xFFFFF8DC, "crimson" to 0xFFDC143C, "cyan" to 0xFF00FFFF,
        "darkblue" to 0xFF00008B, "darkcyan" to 0xFF008B8B, "darkgoldenrod" to 0xFFB8860B,
        "darkgray" to 0xFFA9A9A9, "darkgreen" to 0xFF006400, "darkgrey" to 0xFFA9A9A9,
        "darkkhaki" to 0xFFBDB76B, "darkmagenta" to 0xFF8B008B, "darkolivegreen" to 0xFF556B2F,
        "darkorange" to 0xFFFF8C00, "darkorchid" to 0xFF9932CC, "darkred" to 0xFF8B0000,
        "darksalmon" to 0xFFE9967A, "darkseagreen" to 0xFF8FBC8F, "darkslateblue" to 0xFF483D8B,
        "darkslategray" to 0xFF2F4F4F, "darkslategrey" to 0xFF2F4F4F, "darkturquoise" to 0xFF00CED1,
        "darkviolet" to 0xFF9400D3, "deeppink" to 0xFFFF1493, "deepskyblue" to 0xFF00BFFF,
        "dimgray" to 0xFF696969, "dimgrey" to 0xFF696969, "dodgerblue" to 0xFF1E90FF,
        "firebrick" to 0xFFB22222, "floralwhite" to 0xFFFFFAF0, "forestgreen" to 0xFF228B22,
        "fuchsia" to 0xFFFF00FF, "gainsboro" to 0xFFDCDCDC, "ghostwhite" to 0xFFF8F8FF,
        "gold" to 0xFFFFD700, "goldenrod" to 0xFFDAA520, "gray" to 0xFF808080,
        "green" to 0xFF008000, "greenyellow" to 0xFFADFF2F, "grey" to 0xFF808080,
        "honeydew" to 0xFFF0FFF0, "hotpink" to 0xFFFF69B4, "indianred" to 0xFFCD5C5C,
        "indigo" to 0xFF4B0082, "ivory" to 0xFFFFFFF0, "khaki" to 0xFFF0E68C,
        "lavender" to 0xFFE6E6FA, "lavenderblush" to 0xFFFFF0F5, "lawngreen" to 0xFF7CFC00,
        "lemonchiffon" to 0xFFFFFACD, "lightblue" to 0xFFADD8E6, "lightcoral" to 0xFFF08080,
        "lightcyan" to 0xFFE0FFFF, "lightgoldenrodyellow" to 0xFFFAFAD2, "lightgray" to 0xFFD3D3D3,
        "lightgreen" to 0xFF90EE90, "lightgrey" to 0xFFD3D3D3, "lightpink" to 0xFFFFB6C1,
        "lightsalmon" to 0xFFFFA07A, "lightseagreen" to 0xFF20B2AA, "lightskyblue" to 0xFF87CEFA,
        "lightslategray" to 0xFF778899, "lightslategrey" to 0xFF778899, "lightsteelblue" to 0xFFB0C4DE,
        "lightyellow" to 0xFFFFFFE0, "lime" to 0xFF00FF00, "limegreen" to 0xFF32CD32,
        "linen" to 0xFFFAF0E6, "magenta" to 0xFFFF00FF, "maroon" to 0xFF800000,
        "mediumaquamarine" to 0xFF66CDAA, "mediumblue" to 0xFF0000CD, "mediumorchid" to 0xFFBA55D3,
        "mediumpurple" to 0xFF9370DB, "mediumseagreen" to 0xFF3CB371, "mediumslateblue" to 0xFF7B68EE,
        "mediumspringgreen" to 0xFF00FA9A, "mediumturquoise" to 0xFF48D1CC, "mediumvioletred" to 0xFFC71585,
        "midnightblue" to 0xFF191970, "mintcream" to 0xFFF5FFFA, "mistyrose" to 0xFFFFE4E1,
        "moccasin" to 0xFFFFE4B5, "navajowhite" to 0xFFFFDEAD, "navy" to 0xFF000080,
        "oldlace" to 0xFFFDF5E6, "olive" to 0xFF808000, "olivedrab" to 0xFF6B8E23,
        "orange" to 0xFFFFA500, "orangered" to 0xFFFF4500, "orchid" to 0xFFDA70D6,
        "palegoldenrod" to 0xFFEEE8AA, "palegreen" to 0xFF98FB98, "paleturquoise" to 0xFFAFEEEE,
        "palevioletred" to 0xFFDB7093, "papayawhip" to 0xFFFFEFD5, "peachpuff" to 0xFFFFDAB9,
        "peru" to 0xFFCD853F, "pink" to 0xFFFFC0CB, "plum" to 0xFFDDA0DD,
        "powderblue" to 0xFFB0E0E6, "purple" to 0xFF800080, "rebeccapurple" to 0xFF663399,
        "red" to 0xFFFF0000, "rosybrown" to 0xFFBC8F8F, "royalblue" to 0xFF4169E1,
        "saddlebrown" to 0xFF8B4513, "salmon" to 0xFFFA8072, "sandybrown" to 0xFFF4A460,
        "seagreen" to 0xFF2E8B57, "seashell" to 0xFFFFF5EE, "sienna" to 0xFFA0522D,
        "silver" to 0xFFC0C0C0, "skyblue" to 0xFF87CEEB, "slateblue" to 0xFF6A5ACD,
        "slategray" to 0xFF708090, "slategrey" to 0xFF708090, "snow" to 0xFFFFFAFA,
        "springgreen" to 0xFF00FF7F, "steelblue" to 0xFF4682B4, "tan" to 0xFFD2B48C,
        "teal" to 0xFF008080, "thistle" to 0xFFD8BFD8, "tomato" to 0xFFFF6347,
        "turquoise" to 0xFF40E0D0, "violet" to 0xFFEE82EE, "wheat" to 0xFFF5DEB3,
        "white" to 0xFFFFFFFF, "whitesmoke" to 0xFFF5F5F5, "yellow" to 0xFFFFFF00,
        "yellowgreen" to 0xFF9ACD32
    )
}
