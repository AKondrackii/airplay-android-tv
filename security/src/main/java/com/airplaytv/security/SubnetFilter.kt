package com.airplaytv.security

import com.airplaytv.core.InterfaceAddress

/**
 * Decides whether a TCP peer may talk to the receiver at all.
 *
 * A peer is admitted only if
 *  1. the connection arrived on one of the explicitly allowed local addresses (the
 *     Wi-Fi/Ethernet interface the receiver is bound to — never mobile data, VPN, etc.),
 *  2. the remote address lies inside that interface's subnet (address + prefix length,
 *     not merely "a private range"),
 *  3. the remote address is a plausible unicast host address of that subnet (not the
 *     network/broadcast address, multicast, unspecified, or this device itself).
 *
 * Loopback peers are refused unless [allowLoopback] is set (needed only while the
 * native HLS proxy serves the local player).
 *
 * Pure Kotlin, thread-safe (immutable snapshot swapped atomically).
 */
class SubnetFilter {
    @Volatile private var allowed: List<InterfaceAddress> = emptyList()
    @Volatile var allowLoopback: Boolean = false

    fun update(addresses: List<InterfaceAddress>) {
        allowed = addresses.filter { valid(it) }.toList()
    }

    val allowedAddresses: List<InterfaceAddress> get() = allowed

    /** True if [remote] is a host inside any allowed interface subnet (for outgoing requests). */
    fun isLocalPeer(remote: ByteArray): Boolean {
        val iface = allowed.firstOrNull { it.address.size == remote.size && sameSubnet(it.address, remote, it.prefixLength) }
            ?: return false
        return isAllowed(iface.address, remote)
    }

    fun isAllowed(local: ByteArray, remote: ByteArray): Boolean {
        if (local.size != remote.size || (local.size != 4 && local.size != 16)) return false

        if (isLoopback(remote)) return allowLoopback && isLoopback(local)

        val iface = allowed.firstOrNull { it.address.contentEquals(local) } ?: return false
        if (remote.contentEquals(local)) return false
        if (isUnspecified(remote) || isMulticast(remote)) return false
        if (!sameSubnet(iface.address, remote, iface.prefixLength)) return false

        if (remote.size == 4 && iface.prefixLength <= 30) {
            if (hostBitsAll(remote, iface.prefixLength, zero = true)) return false   // network address
            if (hostBitsAll(remote, iface.prefixLength, zero = false)) return false  // broadcast
        }
        return true
    }

    companion object {
        /** Smallest IPv4 prefix we accept as a "local" network; anything wider is suspicious. */
        const val MIN_IPV4_PREFIX = 8
        const val MIN_IPV6_PREFIX = 48

        internal fun valid(a: InterfaceAddress): Boolean = when (a.address.size) {
            4 -> a.prefixLength in MIN_IPV4_PREFIX..32 && !isLoopback(a.address) && !isUnspecified(a.address)
            16 -> a.prefixLength in MIN_IPV6_PREFIX..128 && !isLoopback(a.address) && !isUnspecified(a.address)
            else -> false
        }

        fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            if (a.size != b.size || prefix < 0 || prefix > a.size * 8) return false
            val full = prefix / 8
            for (i in 0 until full) if (a[i] != b[i]) return false
            val rem = prefix % 8
            if (rem == 0) return true
            val mask = (0xFF shl (8 - rem)) and 0xFF
            return (a[full].toInt() and mask) == (b[full].toInt() and mask)
        }

        private fun hostBitsAll(addr: ByteArray, prefix: Int, zero: Boolean): Boolean {
            for (bit in prefix until addr.size * 8) {
                val set = (addr[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1
                if (set == zero) return false
            }
            return true
        }

        fun isLoopback(a: ByteArray): Boolean = when (a.size) {
            4 -> (a[0].toInt() and 0xFF) == 127
            16 -> (0 until 15).all { a[it].toInt() == 0 } && a[15].toInt() == 1
            else -> false
        }

        fun isUnspecified(a: ByteArray): Boolean = a.all { it.toInt() == 0 }

        fun isMulticast(a: ByteArray): Boolean = when (a.size) {
            4 -> (a[0].toInt() and 0xF0) == 0xE0
            16 -> (a[0].toInt() and 0xFF) == 0xFF
            else -> false
        }
    }
}
