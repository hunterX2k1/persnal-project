package com.blockbridge

import java.util.TreeSet

/**
 * A port of uBlock Origin's static network filter compiler, limited to what can be
 * enforced for the whole phone (hostname level):
 *
 *  - uBO's `!#if` / `!#else` / `!#endif` preprocessor, evaluated as uBO on Chromium for Android
 *  - `||hostname^` block rules, `@@||hostname^` exceptions, hosts files and plain hostnames
 *    (uBO treats both as `||hostname^`)
 *  - `$important` (beats exceptions), `$badfilter` (cancels a rule in any list),
 *    option aliases (`third-party` = `3p`, `document` = `doc`, …) and `$reason=`
 *
 * Rules that need to see the page (cosmetic `##`, scriptlets `+js`) or the full URL
 * (`/path`, `$domain=`, `$redirect`, `$removeparam`, `$csp`) can't be applied outside a
 * browser and are skipped — exactly like uBO Lite skips what its platform can't do.
 */
object UboCompiler {
    /** `!#if` tokens that are true for uBO running in Chromium on Android. Others are false. */
    private val ENV_TRUE = setOf("env_chromium", "env_mobile", "ext_ublock", "cap_user_stylesheet")

    private val PATTERN = Regex("^\\|\\|([a-z0-9._*-]+)(\\^\\|?)?$")
    private val DOMAIN_RE =
        Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
    private val IPV4_RE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val COSMETIC = Regex("#@?[?$%]?#|#@?\\+js")
    private val WS = Regex("\\s+")
    private val IGNORED_HOSTS = setOf(
        "localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost",
        "ip6-loopback", "ip6-localnet", "ip6-mcastprefix", "ip6-allnodes", "ip6-allrouters",
        "ip6-allhosts",
    )
    private val ALIASES = mapOf(
        "third-party" to "3p", "~first-party" to "3p", "~1p" to "3p",
        "first-party" to "1p", "~third-party" to "1p", "~3p" to "1p",
        "document" to "doc", "xmlhttprequest" to "xhr", "subdocument" to "frame",
        "stylesheet" to "css", "strict-third-party" to "strict3p",
    )

    /** Block options where blocking the whole hostname is what uBO would do anyway. */
    private val BLOCK_OK = setOf("important", "all", "doc", "popup", "3p", "strict3p")

    /** Exception options that still mean "this hostname is fine" (no site restriction). */
    private val ALLOW_OK = setOf(
        "important", "all", "doc", "popup", "3p", "1p", "script", "image", "xhr", "frame",
        "media", "font", "css", "ping", "other", "websocket", "object",
    )

    class Rule(val allow: Boolean, val important: Boolean, val domain: String, val key: String, val list: String)

    sealed class Parsed {
        class Ok(val rule: Rule) : Parsed()
        class Bad(val key: String) : Parsed()
    }

    /** Parses one filter line. Returns null for comments and rules that need a browser. */
    fun parseRule(line0: String, list: String): Parsed? {
        var line = line0.trim()
        if (line.isEmpty()) return null
        val c = line[0]
        if (c == '!' || c == '[') return null
        if (COSMETIC.containsMatchIn(line)) return null
        if (c == '#') return null
        val hash = line.indexOf('#')
        if (hash > 0 && line[hash - 1].isWhitespace()) line = line.substring(0, hash).trim()
        var s = line.lowercase()

        var allow = false
        if (s.startsWith("@@")) { allow = true; s = s.substring(2) }

        if (s.startsWith("||")) {
            val dollar = s.indexOf('$')
            val pattern = if (dollar >= 0) s.substring(0, dollar) else s
            val opts = if (dollar >= 0) s.substring(dollar + 1) else ""
            val m = PATTERN.matchEntire(pattern) ?: return null
            val domain = cleanDomain(m.groupValues[1]) ?: return null
            return fromOptions(allow, domain, opts, list)
        }
        if (allow || s.startsWith("|") || s.startsWith("/") || s.contains('$') || s.contains('/')) return null

        // hosts file ("0.0.0.0 a.com b.com") or plain hostname — uBO reads both as ||hostname^
        val parts = s.split(WS)
        val host = when {
            parts.size == 1 -> parts[0]
            parts.size >= 2 && looksLikeIp(parts[0]) -> parts[1] // uBO keeps the first host only
            else -> return null
        }
        val domain = cleanDomain(host) ?: return null
        return Parsed.Ok(Rule(false, false, domain, domain, list))
    }

    private fun fromOptions(allow: Boolean, domain: String, opts: String, list: String): Parsed? {
        var badfilter = false
        var important = false
        val kept = TreeSet<String>()
        if (opts.isNotEmpty()) for (raw in opts.split(',')) {
            val o = raw.trim()
            if (o.isEmpty()) continue
            val m = ALIASES[o] ?: o
            when {
                m == "badfilter" -> badfilter = true
                m.startsWith("reason=") -> Unit
                (if (allow) m in ALLOW_OK else m in BLOCK_OK) -> {
                    if (m == "important") important = true
                    kept.add(m)
                }
                else -> return null
            }
        }
        val key = (if (allow) "@@" else "") + domain +
            (if (kept.isEmpty()) "" else "$" + kept.joinToString(","))
        if (badfilter) return Parsed.Bad(key)
        return Parsed.Ok(Rule(allow, important && !allow, domain, key, list))
    }

    private fun cleanDomain(s: String): String? {
        val d = s.removePrefix("*.").trimEnd('.')
        if (d in IGNORED_HOSTS || !DOMAIN_RE.matches(d) || IPV4_RE.matches(d)) return null
        return d
    }

    private fun looksLikeIp(s: String) = s.contains(':') || IPV4_RE.matches(s)

    /** Evaluates a uBO `!#if` expression: tokens, `!`, `&&`, `||` and parentheses. */
    fun evalCondition(expr: String): Boolean {
        val tokens = Regex("\\(|\\)|!|&&|\\|\\||[A-Za-z0-9_]+").findAll(expr).map { it.value }.toList()
        return ConditionParser(tokens).run { val v = or(); v }
    }

    private class ConditionParser(val t: List<String>) {
        var i = 0
        fun or(): Boolean {
            var v = and()
            while (i < t.size && t[i] == "||") { i++; val r = and(); v = v || r }
            return v
        }
        fun and(): Boolean {
            var v = unary()
            while (i < t.size && t[i] == "&&") { i++; val r = unary(); v = v && r }
            return v
        }
        fun unary(): Boolean {
            if (i >= t.size) return false
            return when (val tok = t[i++]) {
                "!" -> !unary()
                "(" -> or().also { if (i < t.size && t[i] == ")") i++ }
                else -> tok in ENV_TRUE
            }
        }
    }

    /** Per-list counters, like uBO's "used / total" next to each list. */
    class ListCount(var used: Int = 0, var total: Int = 0)

    /** Collects rules from all lists, then applies `$badfilter` across them. */
    class Compiler {
        private val rules = ArrayList<Rule>(200_000)
        private val badfilters = HashSet<String>()
        val counts = LinkedHashMap<String, ListCount>()

        fun addList(list: String, lines: Sequence<String>) {
            val count = counts.getOrPut(list) { ListCount() }
            val cond = ArrayList<Boolean>()
            for (raw in lines) {
                val t = raw.trim()
                if (t.startsWith("!#")) {
                    when {
                        t.startsWith("!#if ") -> cond.add(evalCondition(t.substring(5)))
                        t.startsWith("!#else") -> if (cond.isNotEmpty()) cond[cond.size - 1] = !cond[cond.size - 1]
                        t.startsWith("!#endif") -> if (cond.isNotEmpty()) cond.removeAt(cond.size - 1)
                    }
                    continue
                }
                if (cond.isNotEmpty() && cond.contains(false)) continue
                if (t.isEmpty() || t[0] == '!' || t[0] == '[' || (t[0] == '#' && !COSMETIC.containsMatchIn(t))) continue
                count.total++
                when (val p = parseRule(t, list)) {
                    is Parsed.Bad -> badfilters.add(p.key)
                    is Parsed.Ok -> { rules.add(p.rule); count.used++ }
                    null -> Unit
                }
            }
        }

        fun build(): Ruleset {
            val block = HashMap<String, String>(rules.size * 2)
            val important = HashMap<String, String>()
            val allow = HashMap<String, String>()
            for (r in rules) {
                if (r.key in badfilters) continue
                val target = when { r.allow -> allow; r.important -> important; else -> block }
                target.putIfAbsent(r.domain, r.list.intern())
            }
            return Ruleset(block, important, allow)
        }
    }
}

/** Compiled rules: hostname → key of the list it came from (shown in the logger). */
class Ruleset(
    val block: Map<String, String>,
    val important: Map<String, String>,
    val allow: Map<String, String>,
) {
    val size get() = block.size + important.size

    companion object {
        val EMPTY = Ruleset(emptyMap(), emptyMap(), emptyMap())
    }
}

/**
 * Decides what happens to a hostname, in uBO's order:
 * trusted site → `$important` block → `@@` exception → block → nothing.
 * Every rule also covers subdomains, like `||hostname^` does in uBO.
 */
object Filter {
    const val BY_USER = "user"
    const val BY_TRUSTED = "trusted"

    class Verdict(val blocked: Boolean, val by: String?)

    private val NONE = Verdict(false, null)

    @Volatile var lists: Ruleset = Ruleset.EMPTY
        private set
    @Volatile var user: Ruleset = Ruleset.EMPTY
        private set
    @Volatile var trusted: Set<String> = emptySet()
        private set
    @Volatile var listsLoaded = false
        private set
    /** The ad-block switch. Off = nothing is blocked (the VPN keeps working). */
    @Volatile var enabled = true

    val blockCount: Int get() = lists.size

    fun setLists(r: Ruleset) { lists = r; listsLoaded = true }
    fun setUser(myFilters: Ruleset, trustedSites: Set<String>) { user = myFilters; trusted = trustedSites }

    fun isBlocked(name: String) = match(name).blocked

    fun match(name: String): Verdict {
        if (!enabled) return NONE
        val d = name.lowercase().trimEnd('.')
        if (d.isEmpty()) return NONE
        val chain = ArrayList<String>(6)
        var cur = d
        while (true) {
            chain.add(cur)
            val dot = cur.indexOf('.')
            if (dot < 0) break
            cur = cur.substring(dot + 1)
        }
        val l = lists
        val u = user
        if (chain.any { it in trusted }) return Verdict(false, BY_TRUSTED)
        find(chain, u.important)?.let { return Verdict(true, BY_USER) }
        find(chain, l.important)?.let { return Verdict(true, it) }
        find(chain, u.allow)?.let { return Verdict(false, BY_USER) }
        find(chain, l.allow)?.let { return Verdict(false, it) }
        find(chain, u.block)?.let { return Verdict(true, BY_USER) }
        find(chain, l.block)?.let { return Verdict(true, it) }
        return NONE
    }

    private fun find(chain: List<String>, map: Map<String, String>): String? {
        if (map.isEmpty()) return null
        for (c in chain) map[c]?.let { return it }
        return null
    }

    /** Normalises a typed trusted site ("https://www.x.com/page" → "www.x.com"). */
    fun hostOf(raw: String): String? {
        var s = raw.trim().lowercase()
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null
        s = s.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("*.")
        return if (Regex("^[a-z0-9_.-]+\\.[a-z0-9_-]+$").matches(s)) s else null
    }
}
