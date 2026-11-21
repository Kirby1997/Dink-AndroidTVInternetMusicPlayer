package com.example.dink_smb_player.update

/**
 * Minimal semantic-version comparison for release tags like `v1.5.0`.
 * Leading `v`/`V` is stripped, numeric dot-components are compared (missing components
 * count as 0, so `1.5` == `1.5.0`), and a `-suffix` (pre-release) sorts below the same
 * numbers without one. `+build` metadata is ignored. Non-numeric components count as 0.
 */
object Semver {
    private data class Parsed(val nums: List<Int>, val pre: Boolean)

    private fun parse(raw: String): Parsed {
        var s = raw.trim().removePrefix("v").removePrefix("V")
        s = s.substringBefore('+')
        val pre = s.contains('-')
        val core = s.substringBefore('-')
        val nums = core.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
        return Parsed(nums, pre)
    }

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.nums.size, pb.nums.size)
        for (i in 0 until n) {
            val c = (pa.nums.getOrElse(i) { 0 }).compareTo(pb.nums.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pa.pre == pb.pre -> 0
            pa.pre -> -1
            else -> 1
        }
    }

    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0
}
