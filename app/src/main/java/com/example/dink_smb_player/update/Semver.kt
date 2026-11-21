package com.example.dink_smb_player.update

/**
 * Semantic-version comparison for release tags like `v1.5.0` / `v1.5.0-rc1`.
 * Leading `v`/`V` is stripped, numeric components are compared as Longs (missing count as 0,
 * so `1.5` == `1.5.0`), `+build` metadata is ignored, and pre-release precedence follows
 * semver 2.0: a version with a pre-release sorts below the same numbers without one;
 * pre-release identifiers compare left to right, numeric ones numerically and below
 * alphanumeric ones, alphanumeric ones in ASCII order, and a longer list wins a tie
 * (`1.5.0-rc1` < `1.5.0-rc2` < `1.5.0-rc10`'s numeric parts handled when dot-separated, e.g. `rc.2` < `rc.10`).
 */
object Semver {
    private data class Parsed(val nums: List<Long>, val pre: List<String>)

    private fun parse(raw: String): Parsed {
        var s = raw.trim().removePrefix("v").removePrefix("V")
        s = s.substringBefore('+')
        val core = s.substringBefore('-')
        val pre = if (s.contains('-')) s.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
        val nums = core.split('.').map { part -> part.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
        return Parsed(nums, pre)
    }

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.nums.size, pb.nums.size)
        for (i in 0 until n) {
            val c = pa.nums.getOrElse(i) { 0L }.compareTo(pb.nums.getOrElse(i) { 0L })
            if (c != 0) return c
        }
        if (pa.pre.isEmpty() && pb.pre.isEmpty()) return 0
        if (pa.pre.isEmpty()) return 1
        if (pb.pre.isEmpty()) return -1
        for (i in 0 until minOf(pa.pre.size, pb.pre.size)) {
            val c = compareIdent(pa.pre[i], pb.pre[i])
            if (c != 0) return c
        }
        return pa.pre.size.compareTo(pb.pre.size)
    }

    private fun compareIdent(x: String, y: String): Int {
        val xn = x.all { it.isDigit() }
        val yn = y.all { it.isDigit() }
        return when {
            xn && yn -> {
                val xs = x.trimStart('0'); val ys = y.trimStart('0')
                if (xs.length != ys.length) xs.length.compareTo(ys.length) else xs.compareTo(ys)
            }
            xn -> -1
            yn -> 1
            else -> x.compareTo(y).coerceIn(-1, 1)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
