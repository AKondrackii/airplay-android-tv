package com.airplaytv.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsTest {
    @Test fun `bonjour-unsafe characters are removed from the device name`() {
        assertThat(ReceiverSettings.sanitizeName("Living.Room@TV\n")).isEqualTo("LivingRoomTV")
    }

    @Test fun `blank names fall back to the default`() {
        assertThat(ReceiverSettings.sanitizeName("   ")).isEqualTo(ReceiverSettings.DEFAULT_NAME)
        assertThat(ReceiverSettings.sanitizeName("....")).isEqualTo(ReceiverSettings.DEFAULT_NAME)
    }

    @Test fun `names are length-limited and cyrillic is kept`() {
        assertThat(ReceiverSettings.sanitizeName("ДОМАШНИЙ ТВ")).isEqualTo("ДОМАШНИЙ ТВ")
        assertThat(ReceiverSettings.sanitizeName("x".repeat(200))).hasLength(ReceiverSettings.MAX_NAME_LENGTH)
    }

    @Test fun `auth modes map to UxPlay pin_pw values`() {
        assertThat(AuthMode.NONE.nativeValue).isEqualTo(0)
        assertThat(AuthMode.PIN_PAIRING.nativeValue).isEqualTo(1)
        assertThat(AuthMode.PASSWORD.nativeValue).isEqualTo(2)
        assertThat(AuthMode.ONE_TIME_CODE.nativeValue).isEqualTo(3)
    }
}
