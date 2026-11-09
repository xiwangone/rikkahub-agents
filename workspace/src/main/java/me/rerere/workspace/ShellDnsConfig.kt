package me.rerere.workspace

object ShellDnsConfig {
    const val MODE_AUTO = "auto"
    const val MODE_CUSTOM = "custom"

    val DEFAULT_FALLBACK_DNS: List<String> = listOf("223.5.5.5", "119.29.29.29")

    private val SPLIT_REGEX = Regex("[,\\s]+")
    private val IPV6_CHARS_REGEX = Regex("^[0-9a-fA-F:.]+$")
    private val DISALLOWED_IPV6 =
        setOf(
            "::",
            "::1",
            "0:0:0:0:0:0:0:1",
            "0:0:0:0:0:0:0:0",
        )

    /**
     * Normalizes the persisted DNS mode string idempotently.
     * Any null, blank, or unrecognized value falls back to [MODE_AUTO].
     */
    fun normalizeMode(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            MODE_CUSTOM -> MODE_CUSTOM
            else -> MODE_AUTO
        }
    }

    /**
     * Parses raw custom DNS server entries separated by commas, newlines, or whitespace.
     * Accepts valid global/LAN IPv4 and IPv6 literals, filtering out loopback and unspecified addresses,
     * and deduplicating entries in insertion order.
     */
    fun parseServers(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (token in raw.split(SPLIT_REGEX)) {
            val trimmed = token.trim()
            if (trimmed.isEmpty()) continue
            if (isValidDnsIp(trimmed)) {
                seen.add(trimmed)
            }
        }
        return seen.toList()
    }

    /**
     * Resolves the effective custom DNS server list for the given [mode] and [rawServers].
     * Returns a non-empty list only when [mode] is [MODE_CUSTOM] and at least one valid IP is configured;
     * otherwise returns [emptyList] (indicating automatic system DNS resolution).
     */
    fun effectiveServers(mode: String?, rawServers: String?): List<String> {
        if (normalizeMode(mode) != MODE_CUSTOM) return emptyList()
        return parseServers(rawServers)
    }

    fun isValidDnsIp(candidate: String): Boolean {
        val text = candidate.trim()
        if (text.isEmpty()) return false
        return if (text.contains(':')) {
            isValidIpv6(text)
        } else {
            isValidIpv4(text)
        }
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part.startsWith('0')) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            octets[i] = value
        }
        // Reject 0.0.0.0/8 (unspecified), 127.0.0.0/8 (loopback), and 255.255.255.255 (broadcast)
        if (octets[0] == 0 || octets[0] == 127) return false
        if (octets.all { it == 255 }) return false
        return true
    }

    private fun isValidIpv6(ip: String): Boolean {
        if (!IPV6_CHARS_REGEX.matches(ip)) return false
        if (ip.count { it == ':' } < 2) return false
        if (ip.contains(":::")) return false
        val doubleColonCount = Regex("::").findAll(ip).count()
        if (doubleColonCount > 1) return false

        val normalized = ip.lowercase()
        if (normalized in DISALLOWED_IPV6) {
            return false
        }

        val halves = ip.split("::", limit = 2)
        return if (halves.size == 2) {
            val leftGroups = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
            val rightGroups = if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
            val groupsValid =
                validateIpv6Groups(leftGroups, allowTrailingIpv4 = false) &&
                    validateIpv6Groups(rightGroups, allowTrailingIpv4 = true)
            val totalGroups = countIpv6Groups(leftGroups) + countIpv6Groups(rightGroups)
            groupsValid && totalGroups in 1..7
        } else {
            val groups = ip.split(':')
            validateIpv6Groups(groups, allowTrailingIpv4 = true) && countIpv6Groups(groups) == 8
        }
    }

    private fun validateIpv6Groups(groups: List<String>, allowTrailingIpv4: Boolean): Boolean {
        for ((index, group) in groups.withIndex()) {
            if (group.isEmpty()) return false
            val isLast = index == groups.lastIndex
            if (allowTrailingIpv4 && isLast && group.contains('.')) {
                if (!isValidIpv4(group)) return false
            } else {
                if (!isHexGroup(group)) return false
            }
        }
        return true
    }

    private fun isHexGroup(group: String): Boolean {
        if (group.length !in 1..4) return false
        return group.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    private fun countIpv6Groups(groups: List<String>): Int {
        if (groups.isEmpty()) return 0
        val last = groups.last()
        return if (last.contains('.')) groups.size + 1 else groups.size
    }
}
