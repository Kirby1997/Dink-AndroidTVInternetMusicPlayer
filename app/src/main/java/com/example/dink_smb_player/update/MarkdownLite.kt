package com.example.dink_smb_player.update

/** Inline run: plain text with optional bold / code styling. */
data class MdSpan(val text: String, val bold: Boolean = false, val code: Boolean = false)

/** Block-level element of a release body. */
sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Bullet(val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
}

/**
 * Tiny markdown subset for release notes: `#`..`######` headings, `-`/`*`/`+` bullets
 * (indented continuation lines are folded into the bullet), paragraphs (adjacent lines
 * joined by a space, blank line separates), `**bold**` and `` `code` ``. Anything else
 * (links, tables, html) is shown as plain text.
 */
object MarkdownLite {
    private val headingRe = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bulletRe = Regex("^\\s{0,3}[-*+]\\s+(.*)$")

    fun parse(src: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = StringBuilder()
        var bullet: StringBuilder? = null

        fun flushPara() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(spans(para.toString())); para.clear() }
        }
        fun flushBullet() {
            bullet?.let { out += MdBlock.Bullet(spans(it.toString())) }
            bullet = null
        }

        for (raw in src.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) { flushPara(); flushBullet(); continue }
            val h = headingRe.matchEntire(line.trimStart())
            if (h != null && !raw.startsWith("    ")) {
                flushPara(); flushBullet()
                out += MdBlock.Heading(h.groupValues[1].length, spans(h.groupValues[2]))
                continue
            }
            val b = bulletRe.matchEntire(line)
            if (b != null) {
                flushPara(); flushBullet()
                bullet = StringBuilder(b.groupValues[1].trim())
                continue
            }
            val cur = bullet
            if (cur != null && raw.startsWith(" ")) {
                cur.append(' ').append(line.trim())
                continue
            }
            flushBullet()
            if (para.isNotEmpty()) para.append(' ')
            para.append(line.trim())
        }
        flushPara(); flushBullet()
        return out
    }

    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")

    /** Splits inline text into styled runs. Unterminated markers are kept literally. */
    fun spans(raw: String): List<MdSpan> {
        // Links can't be opened on a TV: show only their text.
        val text = LINK.replace(raw) { it.groupValues[1] }
        val out = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        fun flush() { if (plain.isNotEmpty()) { out += MdSpan(plain.toString()); plain.clear() } }
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        out += boldRuns(text.substring(i + 2, end))
                        i = end + 2
                    } else { plain.append("**"); i += 2 }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        out += MdSpan(text.substring(i + 1, end), code = true)
                        i = end + 1
                    } else { plain.append('`'); i++ }
                }
                else -> { plain.append(text[i]); i++ }
            }
        }
        flush()
        return out
    }

    /** Bold run that may itself contain `code`. */
    private fun boldRuns(text: String): List<MdSpan> {
        val out = mutableListOf<MdSpan>()
        val plain = StringBuilder()
        var i = 0
        while (i < text.length) {
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    if (plain.isNotEmpty()) { out += MdSpan(plain.toString(), bold = true); plain.clear() }
                    out += MdSpan(text.substring(i + 1, end), bold = true, code = true)
                    i = end + 1
                    continue
                }
            }
            plain.append(text[i]); i++
        }
        if (plain.isNotEmpty()) out += MdSpan(plain.toString(), bold = true)
        return out
    }
}
