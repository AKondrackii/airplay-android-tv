package com.airplaytv.security

import com.airplaytv.core.InterfaceAddress
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

class SubnetFilterTest {
    private lateinit var filter: SubnetFilter
    private fun ip(s: String): ByteArray = InetAddress.getByName(s).address

    private val tvWifi = ip("192.168.1.50")
    private val tvV6LinkLocal = ip("fe80::1234:5678:9abc:def0")

    @Before fun setUp() {
        filter = SubnetFilter()
        filter.update(listOf(InterfaceAddress(tvWifi, 24), InterfaceAddress(tvV6LinkLocal, 64)))
    }

    @Test fun `peer in the same subnet on the allowed interface is accepted`() {
        assertThat(filter.isAllowed(tvWifi, ip("192.168.1.23"))).isTrue()
    }

    @Test fun `peer from another subnet is rejected even if it is private`() {
        assertThat(filter.isAllowed(tvWifi, ip("192.168.2.23"))).isFalse()
        assertThat(filter.isAllowed(tvWifi, ip("10.0.0.5"))).isFalse()
    }

    @Test fun `guest network on a different subnet is rejected`() {
        // Typical router guest SSID: 192.168.100.0/24 routed to the main LAN.
        assertThat(filter.isAllowed(tvWifi, ip("192.168.100.7"))).isFalse()
    }

    @Test fun `public internet address is rejected`() {
        assertThat(filter.isAllowed(tvWifi, ip("8.8.8.8"))).isFalse()
    }

    @Test fun `connection that arrived on a non-allowed local address is rejected`() {
        // e.g. a VPN tun0 or mobile interface address, peer "in the same subnet" of that interface
        assertThat(filter.isAllowed(ip("10.8.0.2"), ip("10.8.0.1"))).isFalse()
    }

    @Test fun `network and broadcast addresses are rejected`() {
        assertThat(filter.isAllowed(tvWifi, ip("192.168.1.0"))).isFalse()
        assertThat(filter.isAllowed(tvWifi, ip("192.168.1.255"))).isFalse()
    }

    @Test fun `self, multicast and unspecified are rejected`() {
        assertThat(filter.isAllowed(tvWifi, tvWifi)).isFalse()
        assertThat(filter.isAllowed(tvWifi, ip("224.0.0.251"))).isFalse()
        assertThat(filter.isAllowed(tvWifi, ip("0.0.0.0"))).isFalse()
    }

    @Test fun `non-octet prefix is honoured`() {
        filter.update(listOf(InterfaceAddress(ip("10.1.2.130"), 25)))
        assertThat(filter.isAllowed(ip("10.1.2.130"), ip("10.1.2.200"))).isTrue()
        assertThat(filter.isAllowed(ip("10.1.2.130"), ip("10.1.2.100"))).isFalse()
    }

    @Test fun `ipv6 link-local peer on the same link is accepted, other prefixes are not`() {
        assertThat(filter.isAllowed(tvV6LinkLocal, ip("fe80::aaaa:bbbb:cccc:dddd"))).isTrue()
        assertThat(filter.isAllowed(tvV6LinkLocal, ip("2001:db8::1"))).isFalse()
    }

    @Test fun `mismatched address families are rejected`() {
        assertThat(filter.isAllowed(tvWifi, ip("fe80::1"))).isFalse()
    }

    @Test fun `loopback only when explicitly enabled and only from loopback`() {
        val lo = ip("127.0.0.1")
        assertThat(filter.isAllowed(lo, lo)).isFalse()
        filter.allowLoopback = true
        assertThat(filter.isAllowed(lo, lo)).isTrue()
        assertThat(filter.isAllowed(tvWifi, lo)).isFalse()
    }

    @Test fun `overly wide prefixes are not accepted as local networks`() {
        filter.update(listOf(InterfaceAddress(ip("100.64.1.1"), 4)))
        assertThat(filter.allowedAddresses).isEmpty()
        assertThat(filter.isAllowed(ip("100.64.1.1"), ip("100.64.1.2"))).isFalse()
    }

    @Test fun `no network means nothing is allowed`() {
        filter.update(emptyList())
        assertThat(filter.isAllowed(tvWifi, ip("192.168.1.23"))).isFalse()
    }

    @Test fun `malformed address lengths are rejected`() {
        assertThat(filter.isAllowed(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))).isFalse()
    }
}
