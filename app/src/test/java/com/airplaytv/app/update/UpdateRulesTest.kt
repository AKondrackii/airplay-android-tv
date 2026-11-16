package com.airplaytv.app.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UpdateRulesTest {
    @Test fun `tag to version code matches the release workflow`() {
        assertThat(UpdateRules.versionCode("v1.2.3")).isEqualTo(10203)
        assertThat(UpdateRules.versionCode("v1.0.0")).isEqualTo(10000)
        assertThat(UpdateRules.versionCode("v2.10.0")).isEqualTo(21000)
    }

    @Test fun `non-release tags are ignored`() {
        assertThat(UpdateRules.versionCode("1.2.3")).isNull()
        assertThat(UpdateRules.versionCode("v1.2")).isNull()
        assertThat(UpdateRules.versionCode("v1.2.3-beta")).isNull()
        assertThat(UpdateRules.versionCode("v1.2.3; rm")).isNull()
    }

    private val assets = listOf(
        "AirPlayReceiver-universal.apk", "AirPlayReceiver-armeabi-v7a.apk",
        "AirPlayReceiver-arm64-v8a.apk", "SHA256SUMS.txt",
    )

    @Test fun `abi specific apk is preferred in device order`() {
        assertThat(UpdateRules.pickApk(assets, listOf("armeabi-v7a", "armeabi"))).isEqualTo("AirPlayReceiver-armeabi-v7a.apk")
        assertThat(UpdateRules.pickApk(assets, listOf("arm64-v8a", "armeabi-v7a"))).isEqualTo("AirPlayReceiver-arm64-v8a.apk")
    }

    @Test fun `falls back to universal and to nothing`() {
        assertThat(UpdateRules.pickApk(assets, listOf("mips"))).isEqualTo("AirPlayReceiver-universal.apk")
        assertThat(UpdateRules.pickApk(listOf("SHA256SUMS.txt"), listOf("arm64-v8a"))).isNull()
    }

    @Test fun `checksum lookup in sha256sum output`() {
        val h = "a".repeat(64)
        val sums = "$h  AirPlayReceiver-armeabi-v7a.apk\n${"b".repeat(64)}  AirPlayReceiver-universal.apk\n"
        assertThat(UpdateRules.sha256For(sums, "AirPlayReceiver-armeabi-v7a.apk")).isEqualTo(h)
        assertThat(UpdateRules.sha256For(sums, "missing.apk")).isNull()
        assertThat(UpdateRules.sha256For("zz  AirPlayReceiver-universal.apk", "AirPlayReceiver-universal.apk")).isNull()
    }
}
