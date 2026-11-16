package com.airplaytv.playback

/**
 * Parses the DMAP "now playing" blob AirPlay audio senders transmit (tag/length/value,
 * 4-byte ASCII tag + 4-byte big-endian length). Only the fields we display are read.
 * Defensive against truncated or malicious lengths.
 */
object DmapParser {
    /** Unusual code points seen in the last parsed blob (diagnostics). */
    @Volatile var lastUnusual: List<String> = emptyList()
        private set

    data class Info(val title: String?, val artist: String?, val album: String?, val explicit: Boolean = false)

    /** Apple Music appends these to explicit tracks; TV fonts usually lack the glyphs. */
    private val explicitMarks = listOf(
        "\uD83C\uDD74", "\uD83C\uDD34", "\uD83C\uDD54", // 🅴 🄴 🅔 (Apple-style)
        "!\u20DD", "E\u20DD", "e\u20DD",                 // "!" / "E" + COMBINING ENCLOSING CIRCLE (Yandex Music)
    )

    /** Removes the explicit marker and reports whether one was present. */
    fun stripExplicit(text: String?): Pair<String?, Boolean> {
        if (text == null) return null to false
        var found = false
        var t: String = text
        for (m in explicitMarks) if (t.contains(m)) { found = true; t = t.replace(m, "") }
        // Apps with their own icon fonts (e.g. Yandex Music) append a private-use glyph.
        val trimmed = t.trimEnd()
        if (trimmed.isNotEmpty()) {
            val last = trimmed.codePointBefore(trimmed.length)
            if (isPrivateUse(last)) {
                found = true
                t = trimmed.substring(0, trimmed.length - Character.charCount(last))
            }
        }
        return t.trim().ifEmpty { null } to found
    }

    private fun isPrivateUse(cp: Int) = cp in 0xE000..0xF8FF || cp in 0xF0000..0x10FFFD

    /** Code points outside ordinary text, for diagnosing unknown markers (logged, never shown). */
    fun unusualCodePoints(text: String?): List<String> {
        if (text == null) return emptyList()
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val script = Character.UnicodeScript.of(cp)
            if (cp >= 0x2000 && script != Character.UnicodeScript.CYRILLIC && script != Character.UnicodeScript.LATIN) {
                out += "U+%04X".format(cp)
            }
            i += Character.charCount(cp)
        }
        return out
    }

    private val containers = setOf("mlit", "cmst", "mdcl")

    fun parse(data: ByteArray): Info {
        val fields = HashMap<String, String>()
        walk(data, 0, data.size, fields, depth = 0)
        lastUnusual = (unusualCodePoints(fields["minm"]) + unusualCodePoints(fields["asar"])).distinct()
        val (title, e1) = stripExplicit(fields["minm"])
        val (artist, e2) = stripExplicit(fields["asar"])
        val (album, e3) = stripExplicit(fields["asal"])
        return Info(title, artist, album, explicit = e1 || e2 || e3)
    }

    private fun walk(d: ByteArray, from: Int, to: Int, out: MutableMap<String, String>, depth: Int) {
        if (depth > 4) return
        var p = from
        while (p + 8 <= to) {
            val tag = String(d, p, 4, Charsets.US_ASCII)
            val len = ((d[p + 4].toInt() and 0xFF) shl 24) or ((d[p + 5].toInt() and 0xFF) shl 16) or
                ((d[p + 6].toInt() and 0xFF) shl 8) or (d[p + 7].toInt() and 0xFF)
            val start = p + 8
            if (len < 0 || len > to - start) return
            if (tag in containers) {
                walk(d, start, start + len, out, depth + 1)
            } else if (tag == "minm" || tag == "asar" || tag == "asal") {
                out.putIfAbsent(tag, String(d, start, minOf(len, 512), Charsets.UTF_8).trim())
            }
            p = start + len
        }
    }
}
