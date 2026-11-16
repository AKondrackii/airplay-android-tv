package com.airplaytv.app.update

/** Pure update rules (unit-tested): version from a tag, which APK suits this device. */
object UpdateRules {
    private val TAG = Regex("""^v(\d{1,3})\.(\d{1,2})\.(\d{1,2})$""")

    /** Same formula as the Release workflow: v1.2.3 → 10203. Null for anything else. */
    fun versionCode(tag: String): Int? {
        val m = TAG.matchEntire(tag.trim()) ?: return null
        val (a, b, c) = m.destructured
        return a.toInt() * 10_000 + b.toInt() * 100 + c.toInt()
    }

    /**
     * Picks the release asset for this device: the first supported ABI that has its own
     * APK (smaller download), otherwise the universal one.
     */
    fun pickApk(assetNames: List<String>, supportedAbis: List<String>): String? {
        for (abi in supportedAbis) {
            val name = "AirPlayReceiver-$abi.apk"
            if (name in assetNames) return name
        }
        return "AirPlayReceiver-universal.apk".takeIf { it in assetNames }
    }

    /** Finds the hex SHA-256 for [fileName] in a `sha256sum`-style listing. */
    fun sha256For(sums: String, fileName: String): String? =
        sums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == fileName }
            ?.get(0)?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
}
