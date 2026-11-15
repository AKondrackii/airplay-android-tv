package com.airplaytv.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ParsersTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `h264 access unit with SPS PPS IDR is split and recognised`() {
        val au = b(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f, 0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80, 0, 0, 1, 0x65, 0x88, 0x84)
        val nals = NalParser.split(au, au.size, h265 = false)
        assertThat(nals.map { it.type }).containsExactly(7, 8, 5).inOrder()
        assertThat(NalParser.isKeyFrame(nals, false)).isTrue()
        val ps = NalParser.parameterSets(au, nals, false)!!
        assertThat(ps.sps).isEqualTo(b(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f))
        assertThat(ps.pps).isEqualTo(b(0, 0, 0, 1, 0x68, 0xce, 0x3c, 0x80))
    }

    @Test fun `non-key frame without parameter sets`() {
        val au = b(0, 0, 0, 1, 0x41, 0x9a, 0x00)
        val nals = NalParser.split(au, au.size, false)
        assertThat(NalParser.isKeyFrame(nals, false)).isFalse()
        assertThat(NalParser.parameterSets(au, nals, false)).isNull()
    }

    @Test fun `h265 nal types use the 6-bit header field`() {
        val au = b(0, 0, 0, 1, 0x40, 0x01, 0x0c, 0, 0, 0, 1, 0x42, 0x01, 0x01, 0, 0, 0, 1, 0x44, 0x01, 0xc0,
            0, 0, 0, 1, 0x26, 0x01, 0xaf)
        val nals = NalParser.split(au, au.size, true)
        assertThat(nals.map { it.type }).containsExactly(32, 33, 34, 19).inOrder()
        assertThat(NalParser.isKeyFrame(nals, true)).isTrue()
        assertThat(NalParser.parameterSets(au, nals, true)!!.vps).isNotNull()
    }

    @Test fun `garbage input does not crash`() {
        val junk = ByteArray(1000) { (it * 31).toByte() }
        NalParser.split(junk, junk.size, false)
        NalParser.split(ByteArray(0), 0, true)
        NalParser.split(b(0, 0, 1), 3, false)
    }

    private fun tag(name: String, payload: ByteArray): ByteArray {
        val n = payload.size
        return name.toByteArray(Charsets.US_ASCII) + b(n ushr 24, (n ushr 16) and 0xff, (n ushr 8) and 0xff, n and 0xff) + payload
    }

    @Test fun `dmap now playing fields are extracted`() {
        val inner = tag("minm", "Song".toByteArray()) + tag("asar", "Артист".toByteArray()) +
            tag("asal", "Album".toByteArray()) + tag("astm", b(0, 0, 0, 5))
        val info = DmapParser.parse(tag("mlit", inner))
        assertThat(info.title).isEqualTo("Song")
        assertThat(info.artist).isEqualTo("Артист")
        assertThat(info.album).isEqualTo("Album")
    }

    @Test fun `dmap with a lying length is rejected safely`() {
        val bad = "minm".toByteArray() + b(0x7f, 0xff, 0xff, 0xff) + "x".toByteArray()
        assertThat(DmapParser.parse(bad).title).isNull()
        assertThat(DmapParser.parse(ByteArray(3)).title).isNull()
    }
}
