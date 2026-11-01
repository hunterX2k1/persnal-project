package com.blockbridge

/**
 * Parses blocklists in the formats uBlock Origin / AdGuard / hosts files use:
 *   0.0.0.0 ads.example.com        (hosts file)
 *   ||ads.example.com^             (adblock syntax, DNS-level rules only)
 *   @@||good.example.com^          (adblock exception -> allowlist)
 *   ads.example.com / *.ads.example.com   (plain domain lists)
 * Cosmetic and URL-path rules can't be applied at DNS level and are skipped.
 */
object ListParser {
    private val DOMAIN_RE =
        Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val WS = Regex("\\s+")
    private val IGNORED = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost",
        "ip6-localhost", "ip6-loopback", "ip6-localnet", "ip6-mcastprefix",
        "ip6-allnodes", "ip6-allrouters", "ip6-allhosts"
    )
    private val ALLOWED_MODIFIERS = setOf("important")

    fun parseLine(raw: String, block: MutableSet<String>, allow: MutableSet<String>) {
        var line = raw.trim()
        if (line.isEmpty()) return
        val first = line[0]
        if (first == '#' || first == '!' || first == '[') return
        val comment = line.indexOfAny(charArrayOf('#'))
        if (comment > 0 && line[comment - 1].isWhitespace()) line = line.substring(0, comment).trim()
        line = line.lowercase()

        when {
            line.startsWith("@@||") -> adblockDomain(line.substring(4))?.let { allow.add(it) }
            line.startsWith("||") -> adblockDomain(line.substring(2))?.let { block.add(it) }
            line.startsWith("@@") || line.startsWith("|") || line.startsWith("/") ||
                line.contains("##") || line.contains("#@#") -> Unit
            else -> {
                val parts = line.split(WS)
                val candidates = when {
                    parts.size > 1 && looksLikeIp(parts[0]) -> parts.subList(1, parts.size)
                    parts.size == 1 -> parts
                    else -> return
                }
                for (p in candidates) cleanDomain(p)?.let { block.add(it) }
            }
        }
    }

    /** Normalises a user-typed entry ("https://ads.x.com/path", "*.x.com", "||x.com^"). */
    fun userDomain(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null
        s = s.removePrefix("@@").removePrefix("||").substringBefore('^')
        s = s.removePrefix("http://").removePrefix("https://").substringBefore('/').substringBefore(':')
        return cleanDomain(s)
    }

    private fun adblockDomain(s: String): String? {
        val caret = s.indexOf('^')
        val body = if (caret >= 0) s.substring(0, caret) else s
        val rest = if (caret >= 0) s.substring(caret + 1) else ""
        if (rest.isNotEmpty() && rest != "|") {
            if (!rest.startsWith("$")) return null
            val mods = rest.substring(1).split(',')
            if (mods.any { it !in ALLOWED_MODIFIERS }) return null
        }
        return cleanDomain(body)
    }

    private fun cleanDomain(s: String): String? {
        val d = s.removePrefix("*.").trimEnd('.')
        if (d in IGNORED || !DOMAIN_RE.matches(d) || IPV4_RE.matches(d)) return null
        return d
    }

    private fun looksLikeIp(s: String) = s.contains(':') || IPV4_RE.matches(s)
}

/** Fast domain matcher. A rule for "example.com" also covers "ads.example.com". */
object Filter {
    @Volatile private var block: Set<String> = emptySet()
    @Volatile private var allow: Set<String> = emptySet()
    @Volatile private var userBlock: Set<String> = emptySet()
    @Volatile private var userAllow: Set<String> = emptySet()
    @Volatile var listsLoaded = false

    val blockCount: Int get() = block.size

    fun setLists(b: Set<String>, a: Set<String>) {
        block = b; allow = a; listsLoaded = true
    }

    fun setUserRules(b: Set<String>, a: Set<String>) {
        userBlock = b; userAllow = a
    }

    /** Priority: your allowed > your blocked > list exceptions > list blocks. */
    fun isBlocked(name: String): Boolean {
        val d = name.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        if (matches(d, userAllow)) return false
        if (matches(d, userBlock)) return true
        if (matches(d, allow)) return false
        return matches(d, block)
    }

    private fun matches(name: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var cur = name
        while (true) {
            if (cur in set) return true
            val dot = cur.indexOf('.')
            if (dot < 0) return false
            cur = cur.substring(dot + 1)
        }
    }
}
