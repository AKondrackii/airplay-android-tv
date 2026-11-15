package com.airplaytv.playback

/** Minimal Annex-B NAL unit scanner for H.264 / H.265 parameter sets. Pure Kotlin. */
object NalParser {
    data class Nal(val offset: Int, val length: Int, val type: Int)

    /** Codec configuration found in an access unit (each including its 00 00 00 01 start code). */
    data class ParameterSets(val vps: ByteArray?, val sps: ByteArray, val pps: ByteArray) {
        fun sameAs(o: ParameterSets?) = o != null && sps.contentEquals(o.sps) && pps.contentEquals(o.pps) &&
            (vps?.contentEquals(o.vps) ?: (o.vps == null))
    }

    const val H264_SPS = 7
    const val H264_PPS = 8
    const val H264_IDR = 5
    const val H265_VPS = 32
    const val H265_SPS = 33
    const val H265_PPS = 34

    fun nalType(firstByte: Byte, h265: Boolean): Int =
        if (h265) (firstByte.toInt() shr 1) and 0x3F else firstByte.toInt() and 0x1F

    /** Splits [data] (length [len]) into NAL units. Offsets point at the NAL header (after the start code). */
    fun split(data: ByteArray, len: Int, h265: Boolean): List<Nal> {
        val starts = ArrayList<Pair<Int, Int>>() // (startCodeOffset, headerOffset)
        var i = 0
        while (i + 3 <= len) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) { starts += i to i + 3; i += 3; continue }
                if (i + 4 <= len && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts += i to i + 4; i += 4; continue
                }
            }
            i++
        }
        return starts.mapIndexedNotNull { idx, (_, header) ->
            val end = if (idx + 1 < starts.size) starts[idx + 1].first else len
            if (header >= end) null else Nal(header, end - header, nalType(data[header], h265))
        }
    }

    fun isKeyFrame(nals: List<Nal>, h265: Boolean): Boolean =
        nals.any { if (h265) it.type in 16..21 else it.type == H264_IDR }

    fun parameterSets(data: ByteArray, nals: List<Nal>, h265: Boolean): ParameterSets? {
        fun copy(n: Nal?) = n?.let { byteArrayOf(0, 0, 0, 1) + data.copyOfRange(it.offset, it.offset + it.length) }
        return if (h265) {
            val vps = nals.firstOrNull { it.type == H265_VPS }
            val sps = nals.firstOrNull { it.type == H265_SPS } ?: return null
            val pps = nals.firstOrNull { it.type == H265_PPS } ?: return null
            ParameterSets(copy(vps), copy(sps)!!, copy(pps)!!)
        } else {
            val sps = nals.firstOrNull { it.type == H264_SPS } ?: return null
            val pps = nals.firstOrNull { it.type == H264_PPS } ?: return null
            ParameterSets(null, copy(sps)!!, copy(pps)!!)
        }
    }
}
