package dev.jcode.ext.android.designer

/**
 * Finds the JSX a component returns.
 *
 * Markup, so this is much closer to the XML parser than to the two call-tree parsers — the work is
 * in the two places JSX is not XML. Attribute values can be `{any expression at all}`, which means
 * counting braces rather than looking for a closing quote; and an element can have text between its
 * tags, which is recorded as a value rather than lost, because `<Text>Hello</Text>` would otherwise
 * be an element with nothing in it to edit.
 */
internal class JsxParser(private val s: String) {

    fun parseRoot(): DesignElement? {
        var from = 0
        while (true) {
            val at = RETURN.find(s, from) ?: break
            from = at.range.last
            val open = s.indexOf('<', at.range.first)
            if (open < 0) break
            elementAt(open)?.let { return it }
        }
        // A component written as an arrow returning JSX directly, with no `return` in sight.
        val arrow = ARROW.find(s) ?: return null
        val open = s.indexOf('<', arrow.range.last)
        return if (open < 0) null else elementAt(open)
    }

    private fun elementAt(at: Int): DesignElement? {
        if (s.getOrNull(at) != '<') return null
        var i = at + 1
        val tag = readTagAt(i)
        if (tag.isEmpty()) return null
        i += tag.length

        val attributes = mutableListOf<DesignAttribute>()
        while (i < s.length) {
            i = skipTrivia(i)
            if (i >= s.length) return null
            if (s[i] == '>' || s.startsWith("/>", i)) break
            val start = i
            val name = readNameAt(i)
            if (name.isEmpty()) { i++; continue }
            i = skipTrivia(i + name.length)
            if (s.getOrNull(i) != '=') {
                // A bare attribute (`horizontal`) is shorthand for `={true}`.
                attributes += DesignAttribute(name, "{true}", start until start, start until (start + name.length))
                continue
            }
            i = skipTrivia(i + 1)
            val valueStart = i
            val valueEnd = when (s.getOrNull(i)) {
                '{' -> matchBracket(i).let { if (it < 0) return null else it + 1 }
                '"', '\'' -> skipLiteral(i)
                else -> return null
            }
            attributes += DesignAttribute(
                name,
                s.substring(valueStart, valueEnd),
                valueStart until valueEnd,
                start until valueEnd,
            )
            i = valueEnd
        }

        val selfClosing = s.startsWith("/>", i)
        val openTagEnd = i
        i += if (selfClosing) 2 else 1

        val children = mutableListOf<DesignElement>()
        var end = i - 1
        if (!selfClosing) {
            val contentStart = i
            while (i < s.length) {
                when {
                    s.startsWith("</", i) -> {
                        val close = s.indexOf('>', i)
                        end = if (close < 0) s.length - 1 else close
                        break
                    }
                    s[i] == '<' -> {
                        val child = elementAt(i)
                        if (child == null) { i++ } else { children += child; i = child.range.last + 1 }
                    }
                    // `{items.map(…)}` between tags is an expression, not markup: skip it whole.
                    s[i] == '{' -> {
                        val close = matchBracket(i)
                        i = if (close < 0) s.length else close + 1
                    }
                    else -> i++
                }
            }
            if (children.isEmpty()) {
                val closeTag = s.lastIndexOf("</", end)
                if (closeTag > contentStart) {
                    val content = s.substring(contentStart, closeTag)
                    if (content.isNotBlank() && !content.contains('<')) {
                        attributes += DesignAttribute(
                            JsxDocument.TEXT,
                            content.trim(),
                            contentStart until closeTag,
                            contentStart until closeTag,
                        )
                    }
                }
            }
        }

        return DesignElement(
            tag = tag,
            attributes = attributes,
            children = children,
            range = at..end,
            openTagEnd = openTagEnd,
            selfClosing = selfClosing,
            indent = indentAt(at),
        )
    }

    // ---- scanning ----

    private fun readTagAt(at: Int): String {
        if (at >= s.length || !(s[at].isLetter() || s[at] == '_')) return ""
        var i = at
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
        return s.substring(at, i)
    }

    private fun readNameAt(at: Int): String {
        if (at >= s.length || !(s[at].isLetter() || s[at] == '_')) return ""
        var i = at
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '-')) i++
        return s.substring(at, i)
    }

    private fun skipTrivia(from: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && s.getOrNull(i + 1) == '/' -> {
                    val nl = s.indexOf('\n', i)
                    i = if (nl < 0) s.length else nl
                }
                c == '/' && s.getOrNull(i + 1) == '*' -> {
                    val close = s.indexOf("*/", i + 2)
                    i = if (close < 0) s.length else close + 2
                }
                else -> return i
            }
        }
        return i
    }

    private fun skipLiteral(from: Int): Int {
        val quote = s[from]
        var i = from + 1
        while (i < s.length) {
            when (s[i]) {
                '\\' -> i++
                quote -> return i + 1
            }
            i++
        }
        return s.length
    }

    private fun matchBracket(open: Int): Int {
        val closer = when (s[open]) {
            '{' -> '}'
            '(' -> ')'
            '[' -> ']'
            else -> return -1
        }
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' || c == '`' -> { i = skipLiteral(i); continue }
                c == s[open] -> depth++
                c == closer -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun indentAt(at: Int): String {
        var lineStart = at
        while (lineStart > 0 && s[lineStart - 1] != '\n') lineStart--
        return s.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
    }

    private companion object {
        val RETURN = Regex("""return\s*\(?\s*""")
        val ARROW = Regex("""=>\s*\(?\s*(?=<[A-Za-z])""")
    }
}
