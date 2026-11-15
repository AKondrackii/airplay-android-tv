package com.airplaytv.playback

/**
 * Parses the DMAP "now playing" blob AirPlay audio senders transmit (tag/length/value,
 * 4-byte ASCII tag + 4-byte big-endian length). Only the fields we display are read.
 * Defensive against truncated or malicious lengths.
 */
object DmapParser {
    data class Info(val title: String?, val artist: String?, val album: String?)

    private val containers = setOf("mlit", "cmst", "mdcl")

    fun parse(data: ByteArray): Info {
        val fields = HashMap<String, String>()
        walk(data, 0, data.size, fields, depth = 0)
        return Info(fields["minm"], fields["asar"], fields["asal"])
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
