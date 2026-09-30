package com.example.dink_smb_player.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

/** LYR-15: one-pass entity decode and the scraper HTML → text pipeline. */
class LyricHtmlTest {

    @Test
    fun `entities decode once - an escaped entity stays literal`() {
        assertEquals("&lt;", LyricHtml.decodeEntities("&amp;lt;"))
        assertEquals("&#39;", LyricHtml.decodeEntities("&amp;#39;"))
        assertEquals("Tom & Jerry", LyricHtml.decodeEntities("Tom &amp; Jerry"))
    }

    @Test
    fun `numeric entities cover supplementary code points`() {
        assertEquals("😀", LyricHtml.decodeEntities("&#128512;"))
        assertEquals("😀", LyricHtml.decodeEntities("&#x1F600;"))
        assertEquals("it's it's", LyricHtml.decodeEntities("it&#39;s it&#x27;s"))
        assertEquals("é", LyricHtml.decodeEntities("&#233;"))
    }

    @Test
    fun `named entities decode, unknown and invalid ones stay verbatim`() {
        assertEquals("<b> \"q\" ' ’ …", LyricHtml.decodeEntities("&lt;b&gt;&nbsp;&quot;q&quot; &apos; &rsquo; &hellip;"))
        assertEquals("&bogus; & alone &#xD800; &#1114112; &#0;", LyricHtml.decodeEntities("&bogus; & alone &#xD800; &#1114112; &#0;"))
        assertEquals("no entities", LyricHtml.decodeEntities("no entities"))
    }

    @Test
    fun `htmlToText breaks lines, strips tags, trims and collapses blank runs`() {
        val html = "<p class=\"v\">  First line<br>Second &amp; more <i>italic</i></p><p></p><p></p>" +
            "<BR/>Third<br />&#128512;"
        assertEquals("First line\nSecond & more italic\n\nThird\n😀", LyricHtml.htmlToText(html))
    }
}
