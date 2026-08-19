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
internal class LayoutDocument private constructor(
    override val text: String,
    override val root: DesignElement?,
) : DesignDocument {

    override val format: DesignFormat get() = DesignFormat.AndroidXml

    override fun reparse(text: String): DesignDocument = parse(text)

    override fun propertiesFor(element: DesignElement): List<String> {
        val textish = TEXT_TAGS.any { element.tag.endsWith(it) }
        return COMMON_ATTRS + if (textish) TEXT_ATTRS else emptyList()
    }

    // ---- edits: every one returns the new file text, spliced ----

    /** Set (or add) an attribute, returning the new text — or the text unchanged if nothing moved. */
    override fun withAttribute(element: DesignElement, name: String, value: String): String {
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
    override fun withoutAttribute(element: DesignElement, name: String): String {
        val existing = element.attributes.firstOrNull { it.name == name } ?: return text
        var start = existing.range.first
        while (start > 0 && text[start - 1].isWhitespace() && text[start - 1] != '\n') start--
        if (start > 0 && text[start - 1] == '\n') start--
        return text.removeRange(start, existing.range.last + 1)
    }

    /** Insert [xml] as [parent]'s last child, indented to match. */
    override fun withChild(parent: DesignElement, xml: String): String {
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
        // The whitespace before the closing tag is replaced, not inserted in front of. Inserting
        // leaves it sitting after the new child, so every insert that follows a removal adds another
        // blank line and the file slowly fills with holes.
        return text.replaceRange(at, closeStart, "\n\n$block\n${parent.indent}")
    }

    /**
     * Insert [xml] as [parent]'s child at [index], pushing the rest down.
     *
     * Order is position for a LinearLayout, so a drag that lands between two widgets has to insert
     * between them — appending and letting the user sort it out afterwards would make dragging
     * useless for the one container where dragging matters most.
     */
    override fun withChildAt(parent: DesignElement, index: Int, xml: String): String {
        val before = parent.children.getOrNull(index) ?: return withChild(parent, xml)
        val childIndent = "${parent.indent}    "
        val block = xml.trimEnd().lines().joinToString("\n") { if (it.isBlank()) it else "$childIndent$it" }
        // Back up to the start of the line so the insertion lands above the sibling's own indent
        // rather than between that indent and its opening angle bracket.
        var at = before.range.first
        while (at > 0 && text[at - 1] != '\n') {
            if (!text[at - 1].isWhitespace()) return text.replaceRange(before.range.first, before.range.first, "$block\n\n$childIndent")
            at--
        }
        return text.replaceRange(at, at, "$block\n\n")
    }

    /**
     * Ensure the root declares [prefixes], adding any it lacks.
     *
     * Dropping a `MaterialCardView` into a layout whose root has no `xmlns:app` produces a file that
     * does not compile — the designer would have broken the build in order to add a widget. Checked
     * on every insert rather than assumed, because a hand-written layout often declares only
     * `android`.
     */
    override fun withPrerequisites(item: PaletteItem): String {
        val prefixes = item.prerequisites
        val root = root ?: return text
        var result = text
        var doc = this
        prefixes.forEach { prefix ->
            val name = "xmlns:$prefix"
            if (doc.root?.attributes?.any { it.name == name } == true) return@forEach
            val uri = NAMESPACE_URIS[prefix] ?: return@forEach
            result = doc.withAttribute(doc.root ?: root, name, uri)
            doc = parse(result)
        }
        return result
    }

    /** Remove an element and the blank line it left behind. */
    override fun without(element: DesignElement): String {
        var start = element.range.first
        while (start > 0 && text[start - 1] != '\n') {
            if (!text[start - 1].isWhitespace()) return text.removeRange(element.range)
            start--
        }
        var end = element.range.last + 1
        while (end < text.length && text[end] != '\n') end++
        if (end < text.length) end++
        return collapseBlankRun(text.removeRange(start, end), start)
    }

    /**
     * Collapse the run of newlines at [at] down to a single blank line.
     *
     * A widget usually has a blank line on either side of it, and removing the widget leaves both —
     * so the gap where it used to be stays in the file as a hole that grows with every edit. Applied
     * only at the seam, so the rest of the user's spacing is exactly as they wrote it.
     */
    private fun collapseBlankRun(text: String, at: Int): String {
        var from = at
        while (from > 0 && text[from - 1] == '\n') from--
        var to = at
        while (to < text.length && text[to] == '\n') to++
        return if (to - from <= 2) text else text.replaceRange(from, to, "\n\n")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")

    companion object {

        private val COMMON_ATTRS = listOf(
            "android:id", "android:layout_width", "android:layout_height",
            "android:layout_margin", "android:padding", "android:background", "android:visibility",
        )
        private val TEXT_ATTRS = listOf(
            "android:text", "android:textSize", "android:textColor", "android:textStyle", "android:gravity",
        )
        private val TEXT_TAGS = listOf("TextView", "Button", "EditText", "CheckBox", "Switch")

        /** The prefixes a layout can meaningfully declare, and what they must point at. */
        private val NAMESPACE_URIS = mapOf(
            "android" to "http://schemas.android.com/apk/res/android",
            "app" to "http://schemas.android.com/apk/res-auto",
            "tools" to "http://schemas.android.com/tools",
        )

        fun parse(text: String): LayoutDocument = LayoutDocument(text, Parser(text).parseRoot())
    }

    /**
     * Tolerant enough for a file being typed into: an unclosed tag ends at the end of the file
     * rather than failing the parse, because the designer still has something useful to draw.
     */
    private class Parser(private val s: String) {

        private var i = 0

        fun parseRoot(): DesignElement? {
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

        private fun element(): DesignElement? {
            val start = i
            if (s.getOrNull(i) != '<') return null
            i++
            val tag = readName()
            if (tag.isEmpty()) return null

            val attributes = mutableListOf<DesignAttribute>()
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
                attributes += DesignAttribute(name, unescape(value), valueStart..valueEnd, attrStart..(i - 1))
            }

            val selfClosing = s.startsWith("/>", i)
            val openTagEnd = i
            i += if (selfClosing) 2 else 1

            val children = mutableListOf<DesignElement>()
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

            return DesignElement(
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
