package com.example.dink_smb_player.update

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownLiteTest {
    @Test fun headingsBulletsParagraphs() {
        val b = MarkdownLite.parse("## Title\n\n### Radio\n- one\n- two\n  more\n\nHello\nworld\n")
        assertEquals(
            listOf(
                MdBlock.Heading(2, listOf(MdSpan("Title"))),
                MdBlock.Heading(3, listOf(MdSpan("Radio"))),
                MdBlock.Bullet(listOf(MdSpan("one"))),
                MdBlock.Bullet(listOf(MdSpan("two more"))),
                MdBlock.Paragraph(listOf(MdSpan("Hello world"))),
            ),
            b,
        )
    }

    @Test fun boldAndCode() {
        assertEquals(
            listOf(MdSpan("a "), MdSpan("b", bold = true), MdSpan(" "), MdSpan("c", code = true)),
            MarkdownLite.spans("a **b** `c`"),
        )
    }

    @Test fun codeInsideBold() {
        assertEquals(
            listOf(MdSpan("x ", bold = true), MdSpan("y", bold = true, code = true)),
            MarkdownLite.spans("**x `y`**"),
        )
    }

    @Test fun unterminatedMarkersLiteral() {
        assertEquals(listOf(MdSpan("a **b `c")), MarkdownLite.spans("a **b `c"))
    }

    @Test fun crlfAndStarBullets() {
        val b = MarkdownLite.parse("* x\r\n+ y\r\n")
        assertEquals(2, b.size)
        assertEquals(MdBlock.Bullet(listOf(MdSpan("y"))), b[1])
    }

    @Test fun emptyBody() = assertEquals(emptyList<MdBlock>(), MarkdownLite.parse(""))

    @Test fun linksShowTextOnly() = assertEquals(
        listOf(MdSpan("Full list in CHANGELOG.md.")),
        MarkdownLite.spans("Full list in [CHANGELOG.md](https://github.com/x/y/blob/v1.5.0/CHANGELOG.md)."),
    )
}
