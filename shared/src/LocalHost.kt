package org.meshtastic.aether

/** IPv6 link-local (fe80::/10) and unique-local (fc00::/7) prefixes. */
private val LocalIpv6Prefixes = listOf("fe8", "fe9", "fea", "feb", "fc", "fd")

/** Private (RFC 1918) and link-local (RFC 3927) IPv4 blocks, as a first octet and the second octets it covers. */
private val LocalIpv4Blocks: List<Pair<Int, IntRange>> = listOf(
    10 to 0..255,
    172 to 16..31,
    192 to 168..168,
    169 to 254..254,
)

/**
 * Whether [host] is on the local network, which Android 17 blocks without the Nearby devices permission: an `.local`
 * mDNS name, a bare single-label name, or a private, link-local or unique-local address. Other names may still resolve
 * to the LAN, so this is true only where that is certain.
 */
internal fun isLocalHost(host: String): Boolean {
    val h = host.trim().removePrefix("[").removeSuffix("]").lowercase()
    val ipv4 = ipv4Octets(h)
    return when {
        ':' in h -> LocalIpv6Prefixes.any { h.startsWith(it) }
        ipv4 != null -> LocalIpv4Blocks.any { (first, seconds) -> ipv4[0] == first && ipv4[1] in seconds }
        else -> h.endsWith(".local") || ('.' !in h && h.isNotEmpty() && h != "localhost")
    }
}

private fun ipv4Octets(host: String): List<Int>? {
    val octets = host.split('.').map { it.toIntOrNull() ?: return null }
    return octets.takeIf { it.size == IPV4_PARTS && it.all { o -> o in 0..MAX_OCTET } }
}

private const val IPV4_PARTS = 4
private const val MAX_OCTET = 255

/** The host part of a `ws://` or `wss://` [url]. */
internal fun webSocketHost(url: String): String {
    val rest = url.substringAfter("://").substringBefore('/')
    return if (rest.startsWith("[")) rest.substringBefore(']') + "]" else rest.substringBefore(':')
}
