package com.airplaytv.security

import com.airplaytv.core.ClientInfo
import com.airplaytv.core.InterfaceAddress
import com.airplaytv.core.UserPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReceiverPolicyTest {
    private class FakeTrust : TrustRegistry {
        val devices = mutableListOf<TrustedDevice>()
        override fun isTrustedId(deviceId: String?) = devices.any { it.deviceId == deviceId }
        override fun isTrustedKey(publicKey: String) = devices.any { it.publicKey == publicKey }
        override fun add(device: TrustedDevice) { devices.removeAll { it.deviceId == device.deviceId }; devices += device }
    }

    private fun ip(s: String): ByteArray = InetAddress.getByName(s).address
    private val tv = ip("192.168.1.50")
    private val phone = ClientInfo("AA:BB:CC:DD:EE:FF", "iPhone15,2", "Phone")

    private lateinit var trust: FakeTrust
    private lateinit var subnets: SubnetFilter
    private lateinit var policy: ReceiverPolicy
    private var now = 1_000_000L

    @Before fun setUp() {
        trust = FakeTrust()
        subnets = SubnetFilter().apply { update(listOf(InterfaceAddress(tv, 24))) }
        policy = ReceiverPolicy(subnets, trust, RateLimiter(maxPerWindow = 3, clock = { now }), clock = { now })
    }

    @Test fun `accept delegates to the subnet filter`() {
        assertThat(policy.acceptConnection(tv, ip("192.168.1.2"))).isTrue()
        assertThat(policy.acceptConnection(tv, ip("172.16.0.2"))).isFalse()
    }

    @Test fun `flooding host gets rate limited`() {
        repeat(3) { assertThat(policy.acceptConnection(tv, ip("192.168.1.2"))).isTrue() }
        assertThat(policy.acceptConnection(tv, ip("192.168.1.2"))).isFalse()
        // other hosts are unaffected
        assertThat(policy.acceptConnection(tv, ip("192.168.1.3"))).isTrue()
    }

    @Test fun `without confirmation every client on the LAN is admitted`() {
        assertThat(policy.admitClient(phone)).isTrue()
    }

    @Test fun `trusted device skips confirmation`() {
        policy.config = PolicyConfig(confirmNewDevices = true)
        trust.add(TrustedDevice(phone.deviceId!!, "Phone", null, 0))
        assertThat(policy.admitClient(phone)).isTrue()
    }

    @Test fun `confirmation timeout denies`() {
        policy.config = PolicyConfig(confirmNewDevices = true, confirmTimeoutMillis = 100)
        assertThat(policy.admitClient(phone)).isFalse()
        assertThat(policy.prompt.value).isNull()
    }

    @Test fun `user approval admits and can remember the device`() {
        policy.config = PolicyConfig(confirmNewDevices = true, confirmTimeoutMillis = 5_000)
        val exec = Executors.newSingleThreadExecutor()
        val result = exec.submit<Boolean> { policy.admitClient(phone) }
        val prompt = waitForPrompt()
        policy.answerPrompt(prompt.id, allow = true, remember = true)
        assertThat(result.get(2, TimeUnit.SECONDS)).isTrue()
        assertThat(trust.isTrustedId(phone.deviceId)).isTrue()
        exec.shutdown()
    }

    @Test fun `denied device is auto-denied for a while`() {
        policy.config = PolicyConfig(confirmNewDevices = true, confirmTimeoutMillis = 5_000)
        val exec = Executors.newSingleThreadExecutor()
        val result = exec.submit<Boolean> { policy.admitClient(phone) }
        policy.answerPrompt(waitForPrompt().id, allow = false, remember = false)
        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse()
        // immediate retry is refused without prompting again
        assertThat(policy.admitClient(phone)).isFalse()
        exec.shutdown()
    }

    @Test fun `stale prompt answers are ignored`() {
        policy.answerPrompt(42, allow = true, remember = true)
        assertThat(trust.devices).isEmpty()
    }

    @Test fun `pairing registers the key and pair-verify checks it`() {
        assertThat(policy.isPairedKeyTrusted("pk1")).isFalse()
        policy.onClientPaired(phone.deviceId!!, "pk1", "Phone")
        assertThat(policy.isPairedKeyTrusted("pk1")).isTrue()
        assertThat(policy.isPairedKeyTrusted("pk2")).isFalse()
    }

    @Test fun `pins are ignored when no code-based mode is enabled`() {
        policy.onPinDisplayed("1234")
        assertThat(policy.prompt.value).isNull()
    }

    @Test fun `pin overlay expires on its own`() {
        policy.config = PolicyConfig(showPins = true, pinDisplayMillis = 50)
        policy.onPinDisplayed("4321")
        assertThat(policy.prompt.value).isInstanceOf(UserPrompt.ShowPin::class.java)
        val deadline = System.currentTimeMillis() + 2_000
        while (policy.prompt.value != null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertThat(policy.prompt.value).isNull()
    }

    @Test fun `only 4-digit pins are surfaced`() {
        policy.config = PolicyConfig(showPins = true)
        policy.onPinDisplayed("12a4")
        assertThat(policy.prompt.value).isNull()
        policy.onPinDisplayed("0429")
        assertThat((policy.prompt.value as UserPrompt.ShowPin).pin).isEqualTo("0429")
    }

    private fun waitForPrompt(): UserPrompt.ConfirmClient {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            (policy.prompt.value as? UserPrompt.ConfirmClient)?.let { return it }
            Thread.sleep(5)
        }
        error("no prompt")
    }
}
