package com.chandan.assistant.core

object Fuzzy {
    private val subs = listOf(
        "ph" to "f", "bh" to "b", "kh" to "k", "sh" to "s", "th" to "t", "dh" to "d",
        "gh" to "g", "ch" to "c", "ck" to "k", "aa" to "a", "ee" to "i", "oo" to "u",
        "ou" to "u", "y" to "i", "w" to "v", "z" to "j", "q" to "k"
    )

    fun norm(s: String): String {
        var x = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()
        for ((a, b) in subs) x = x.replace(a, b)
        return x.replace(Regex("(.)\\1+"), "$1")
    }

    private fun lev(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    }

    /** 0.0 .. 1.0 similarity between what I said and a stored name. */
    fun score(query: String, target: String): Double {
        val q = norm(query)
        val t = norm(target)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        var best = sim(q, t)
        if (q.length >= 3 && t.contains(q)) best = maxOf(best, 0.9)
        for (tok in t.split(" ")) {
            if (tok.length >= 2) best = maxOf(best, sim(q, tok) * 0.95)
        }
        return best
    }
}
