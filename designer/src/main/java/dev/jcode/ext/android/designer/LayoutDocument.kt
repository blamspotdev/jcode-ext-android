package dev.jcode.ext.android.designer

/**
 * A layout XML file, parsed to a tree that remembers **where every piece came from**.
 *
 * The ranges are the whole point. A designer that re-serialised the tree on every edit would
 * reformat the user's file — reordering attributes, collapsing their line breaks, dropping their
 * comments — and a visual tweak would show up in `git diff` as a rewrite of the file. Keeping each
 * attribute's exact character range instead means an edit is a splice: the bytes the user did not
 * touch are the bytes that were already there.
 *
 * Hand-rolled rather than XmlPullParser because the parser reports line/column, not offsets, and
 * because it is intolerant of the half-typed file a designer is routinely looking at. This one keeps
 * going and returns what it understood.
 */
internal class LayoutDocument private constructor(val text: String, val root: Element?) {

    /** One attribute, and exactly where its name, value and whole `name="value"` span sit. */
    class Attribute(
        val name: String,
        val value: String,
        /** The value's characters, excluding the quotes. */
        val valueRange: IntRange,
        /** `name="value"` in full, for removal. */
        val range: IntRange,
    ) {
        /** `android`, `app`, `tools` … or "" for an unprefixed name. */
        val prefix: String get() = name.substringBefore(':', "")

        /** The name without its prefix — what the renderer and the properties panel match on. */
        val local: String get() = name.substringAfter(':')
    }

    class Element(
        val tag: String,
        val attributes: List<Attribute>,
        val children: List<Element>,
        /** The whole element, open tag through close tag. */
        val range: IntRange,
        /** Index of the `>` (or the `/` of `/>`) that ends the open tag — where a new attribute goes. */
        val openTagEnd: Int,
        val selfClosing: Boolean,
        /** The element's own indentation, so inserted children line up with the file's style. */
        val indent: String,
    ) {
        fun attr(local: String): Attribute? = attributes.firstOrNull { it.local == local }
        fun value(local: String): String? = attr(local)?.value

        /** Depth-first, this element first — the order a hit test wants to consider them in. */
        fun flatten(): List<Element> = buildList {
            add(this@Element)
            children.forEach { addAll(it.flatten()) }
        }
    }

    // ---- edits: every one returns the new file text, spliced ----

    /** Set (or add) an attribute, returning the new text — or the text unchanged if nothing moved. */
    fun withAttribute(element: Element, name: String, value: String): String {
        val existing = element.attributes.firstOrNull { it.name == name }
        if (existing != null) {
            if (existing.value == value) return text
            return text.replaceRange(existing.valueRange.first, existing.valueRange.last + 1, escape(value))
        }
        // New attributes join the ones already there, on their own line at the same indent, so an
        // element written one-attribute-per-line stays that way.
        val perLine = element.attributes.size > 1 &&
            text.substring(element.range.first, element.openTagEnd).contains('\n')
        val insertion = if (perLine) {
            "\n${element.indent}    $name=\"${escape(value)}\""
        } else {
            " $name=\"${escape(value)}\""
        }
        return text.replaceRange(element.openTagEnd, element.openTagEnd, insertion)
    }

    /** Remove an attribute along with the whitespace that introduced it. */
    fun withoutAttribute(element: Element, name: String): String {
        val existing = element.attributes.firstOrNull { it.name == name } ?: return text
        var start = existing.range.first
        while (start > 0 && text[start - 1].isWhitespace() && text[start - 1] != '\n') start--
        if (start > 0 && text[start - 1] == '\n') start--
        return text.removeRange(start, existing.range.last + 1)
    }

    /** Insert [xml] as [parent]'s last child, indented to match. */
    fun withChild(parent: Element, xml: String): String {
        val childIndent = "${parent.indent}    "
        val block = xml.trimEnd().lines().joinToString("\n") { if (it.isBlank()) it else "$childIndent$it" }
        if (parent.selfClosing) {
            // `<Foo … />` has to become `<Foo …>` + child + `</Foo>` before anything can go inside.
            val slash = text.lastIndexOf('/', parent.range.last)
            val head = text.substring(parent.range.first, slash).trimEnd()
            return text.replaceRange(
                parent.range.first,
                parent.range.last + 1,
                "$head>\n\n$block\n${parent.indent}</${parent.tag}>",
            )
        }
        val closeStart = text.lastIndexOf("</", parent.range.last)
        if (closeStart <= 0) return text
        var at = closeStart
        while (at > 0 && text[at - 1].isWhitespace()) at--
        return text.replaceRange(at, at, "\n\n$block\n")
    }

    /** Remove an element and the blank line it left behind. */
    fun without(element: Element): String {
        var start = element.range.first
        while (start > 0 && text[start - 1] != '\n') {
            if (!text[start - 1].isWhitespace()) return text.removeRange(element.range)
            start--
        }
        var end = element.range.last + 1
        while (end < text.length && text[end] != '\n') end++
        if (end < text.length) end++
        return text.removeRange(start, end)
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")

    companion object {

        fun parse(text: String): LayoutDocument = LayoutDocument(text, Parser(text).parseRoot())
    }

    /**
     * Tolerant enough for a file being typed into: an unclosed tag ends at the end of the file
     * rather than failing the parse, because the designer still has something useful to draw.
     */
    private class Parser(private val s: String) {

        private var i = 0

        fun parseRoot(): Element? {
            while (true) {
                skipTo('<')
                if (i >= s.length) return null
                when {
                    s.startsWith("<?", i) -> skipPast("?>")
                    s.startsWith("<!--", i) -> skipPast("-->")
                    s.startsWith("<!", i) -> skipPast(">")
                    else -> return element()
                }
            }
        }

        private fun element(): Element? {
            val start = i
            if (s.getOrNull(i) != '<') return null
            i++
            val tag = readName()
            if (tag.isEmpty()) return null

            val attributes = mutableListOf<Attribute>()
            while (i < s.length) {
                skipSpace()
                if (i >= s.length) break
                if (s[i] == '>' || s.startsWith("/>", i)) break
                val attrStart = i
                val name = readName()
                if (name.isEmpty()) { i++; continue }
                skipSpace()
                if (s.getOrNull(i) != '=') continue
                i++
                skipSpace()
                val quote = s.getOrNull(i) ?: break
                if (quote != '"' && quote != '\'') continue
                i++
                val valueStart = i
                while (i < s.length && s[i] != quote) i++
                val value = s.substring(valueStart, i)
                val valueEnd = i - 1
                if (i < s.length) i++
                attributes += Attribute(name, unescape(value), valueStart..valueEnd, attrStart..(i - 1))
            }

            val selfClosing = s.startsWith("/>", i)
            val openTagEnd = i
            i += if (selfClosing) 2 else 1

            val children = mutableListOf<Element>()
            if (!selfClosing) {
                while (i < s.length) {
                    skipTo('<')
                    if (i >= s.length) break
                    when {
                        s.startsWith("</", i) -> { skipPast(">"); break }
                        s.startsWith("<!--", i) -> skipPast("-->")
                        s.startsWith("<?", i) -> skipPast("?>")
                        else -> element()?.let { children += it } ?: break
                    }
                }
            }

            return Element(
                tag = tag,
                attributes = attributes,
                children = children,
                range = start..(i - 1).coerceAtLeast(start),
                openTagEnd = openTagEnd,
                selfClosing = selfClosing,
                indent = indentAt(start),
            )
        }

        private fun indentAt(at: Int): String {
            var lineStart = at
            while (lineStart > 0 && s[lineStart - 1] != '\n') lineStart--
            return s.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
        }

        private fun readName(): String {
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] in "._:-")) i++
            return s.substring(start, i)
        }

        private fun skipSpace() { while (i < s.length && s[i].isWhitespace()) i++ }
        private fun skipTo(c: Char) { while (i < s.length && s[i] != c) i++ }
        private fun skipPast(marker: String) {
            val at = s.indexOf(marker, i)
            i = if (at < 0) s.length else at + marker.length
        }

        private fun unescape(v: String): String = v
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
