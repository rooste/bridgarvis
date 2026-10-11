package com.bridgarvis.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeVisitor
import java.text.Normalizer

/**
 * Turns untrusted mail content into plain text that is safe to show and to hand to the assistant.
 * HTML is never passed on: it is converted to text with hidden content dropped first, because hidden
 * text is a common carrier for prompt injection ("display:none" blocks, zero-size fonts, preheaders).
 */
object TextCleaner {

    private val removedTags = "script, style, head, title, template, noscript, iframe, object, embed, svg, math, meta, link"
    private val blockTags = setOf(
        "p", "div", "br", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
        "blockquote", "pre", "section", "article", "header", "footer", "hr", "dt", "dd"
    )
    private val hiddenStyle = listOf(
        Regex("display\\s*:\\s*none"),
        Regex("visibility\\s*:\\s*hidden"),
        Regex("opacity\\s*:\\s*0(\\.0+)?\\s*(;|$|!)"),
        Regex("font-size\\s*:\\s*[01](\\.\\d+)?(px|pt|em|rem|%)?\\s*(;|$|!)"),
        Regex("(max-)?height\\s*:\\s*0(px)?\\s*(;|$|!)"),
        Regex("(max-)?width\\s*:\\s*0(px)?\\s*(;|$|!)"),
        Regex("mso-hide\\s*:\\s*all"),
        Regex("(left|top|text-indent)\\s*:\\s*-\\d{3,}"),
    )

    fun htmlToText(html: String): String {
        val doc = Jsoup.parse(html)
        doc.select(removedTags).remove()
        doc.select("[hidden], [aria-hidden=true]").remove()
        doc.select("[style]").toList().filter { isHidden(it) }.forEach { it.remove() }

        val out = StringBuilder()
        doc.body().traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode && node.isBlank && (out.isEmpty() || out.last() == '\n') -> {}
                    node is TextNode -> out.append(node.text())
                    node is Element && node.normalName() == "br" -> out.append('\n')
                    node is Element && node.normalName() in blockTags -> newline(out)
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockTags) newline(out)
            }
        })
        return out.toString()
    }

    private fun isHidden(element: Element): Boolean {
        val style = element.attr("style").lowercase()
        return hiddenStyle.any { it.containsMatchIn(style) }
    }

    private fun newline(out: StringBuilder) {
        if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
    }

    /** NFKC, strip invisible/formatting characters, normalize whitespace. */
    fun normalize(text: String): String {
        val nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val out = StringBuilder(nfkc.length)
        nfkc.codePoints().forEach { cp -> if (!isInvisible(cp)) out.appendCodePoint(cp) }
        return out.toString()
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace(' ', ' ')
            .lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun isInvisible(cp: Int): Boolean = when {
        cp == '\n'.code || cp == '\t'.code -> false
        cp < 0x20 || cp in 0x7F..0x9F -> true                    // control characters
        cp == 0x00AD || cp == 0x034F || cp == 0x061C -> true      // soft hyphen, grapheme joiner, Arabic mark
        cp == 0x115F || cp == 0x1160 || cp == 0x3164 || cp == 0xFFA0 -> true  // Hangul fillers
        cp == 0x17B4 || cp == 0x17B5 || cp == 0x180E -> true
        cp in 0x200B..0x200F -> true                              // zero-width space/joiners, LRM/RLM
        cp in 0x202A..0x202E -> true                              // bidi embedding/override
        cp in 0x2060..0x2064 -> true                              // word joiner, invisible operators
        cp in 0x2066..0x2069 -> true                              // bidi isolates
        cp == 0xFEFF -> true                                      // zero-width no-break space / BOM
        cp in 0xE0000..0xE007F -> true                            // Unicode tag characters
        cp in 0xE0100..0xE01EF -> true                            // variation selectors supplement
        else -> false
    }

    private val replyHeader = Regex("^On .{3,300}wrote:\\s*$")
    private val forwardMarkers = listOf("-----Original Message-----", "________________________________")

    /** The new part of a reply: drops the quoted history ("On … wrote:", "> " lines, Outlook separators). */
    fun stripQuoted(text: String): String {
        val lines = text.lines()
        val cut = lines.indices.firstOrNull { i ->
            val line = lines[i].trim()
            val joined = if (i + 1 < lines.size) line + " " + lines[i + 1].trim() else line
            line.startsWith(">") || forwardMarkers.any { line.startsWith(it) } ||
                replyHeader.matches(line) || (line.startsWith("On ") && replyHeader.matches(joined))
        } ?: return text
        val head = lines.take(cut).joinToString("\n").trim()
        return head.ifEmpty { text }
    }

    fun preview(text: String, max: Int = 200): String {
        val single = text.replace(Regex("\\s+"), " ").trim()
        return if (single.length <= max) single else single.take(max - 1).trimEnd() + "…"
    }
}
