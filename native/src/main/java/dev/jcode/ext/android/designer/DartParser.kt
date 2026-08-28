package dev.jcode.ext.android.designer

/**
 * Finds the Flutter widget tree in a Dart file.
 *
 * Starts at the `build` method, because that is where a widget tree is by definition, and because
 * starting anywhere else would mean treating every capitalised call in the file as UI.
 *
 * Children are read out of the `child:` and `children:` arguments rather than from a block, which
 * is the one place this differs meaningfully from the Compose parser. Everything else — skipping
 * strings and comments, matching brackets, splitting an argument list at top level — is the same
 * problem, and the same deliberately small amount of language knowledge answers it.
 */
internal class DartParser(private val s: String) {

    fun parseRoot(): DesignElement? {
        var from = 0
        while (true) {
            val build = BUILD.find(s, from) ?: return null
            from = build.range.last + 1
            val brace = s.indexOf('{', build.range.last)
            if (brace < 0) return null
            val end = matchBracket(brace)
            if (end < 0) return null
            returnedWidget(brace + 1, end)?.let { return it }
        }
    }

    /** The widget a build method returns, whether it says `return` or is an arrow body. */
    private fun returnedWidget(from: Int, to: Int): DesignElement? {
        var i = from
        while (i < to) {
            val at = s.indexOf("return", i)
            if (at < 0 || at >= to) break
            val after = skipTrivia(at + 6)
            callAt(after, to)?.let { return it }
            i = at + 6
        }
        // `Widget build(…) => Column(…);`
        val arrow = s.indexOf("=>", from)
        if (arrow in from until to) return callAt(skipTrivia(arrow + 2), to)
        return null
    }

    /** A widget construction at [i], or null when this is something else. */
    private fun callAt(i: Int, limit: Int): DesignElement? {
        var start = i
        // `const Text('hi')` and `new Container()` are the same tree node as without them.
        for (keyword in KEYWORDS) {
            if (s.startsWith(keyword, start) && s.getOrNull(start + keyword.length)?.isWhitespace() == true) {
                start = skipTrivia(start + keyword.length)
            }
        }
        val name = readIdentAt(start)
        if (name.isEmpty() || !name[0].isUpperCase()) return null
        var j = skipTrivia(start + name.length)
        // `Colors.blue` and `MainAxisAlignment.center` are values, not widgets. A named constructor
        // (`Icon.filled(…)`) is one, so the dot is only disqualifying when no call follows.
        if (s.getOrNull(j) == '.') {
            val member = readIdentAt(j + 1)
            val afterMember = skipTrivia(j + 1 + member.length)
            if (s.getOrNull(afterMember) != '(') return null
            j = afterMember
        }
        if (s.getOrNull(j) != '(') return null

        val close = matchBracket(j)
        if (close < 0 || close > limit) return null

        val parsed = parseArguments(name, j + 1, close)
        return DesignElement(
            tag = name,
            attributes = parsed.attributes,
            children = parsed.children,
            range = start..close,
            openTagEnd = close,
            selfClosing = parsed.children.isEmpty(),
            indent = indentAt(start),
        )
    }

    private class Args(val attributes: List<DesignAttribute>, val children: List<DesignElement>)

    private fun parseArguments(widget: String, from: Int, to: Int): Args {
        val attributes = mutableListOf<DesignAttribute>()
        val children = mutableListOf<DesignElement>()
        val positional = DartDocument.POSITIONAL[widget].orEmpty()
        var positionalCount = 0

        var i = from
        while (i < to) {
            i = skipTrivia(i)
            if (i >= to) break
            val start = i
            val argEnd = endOfArgument(start, to)
            if (s.substring(start, argEnd).isNotBlank()) {
                val colon = topLevelColon(start, argEnd)
                if (colon >= 0) {
                    val name = s.substring(start, colon).trim()
                    val valueStart = skipTrivia(colon + 1)
                    val valueEnd = trimEndAt(valueStart, argEnd)
                    when (name) {
                        "child" -> callAt(valueStart, valueEnd)?.let { children += it }
                        "children" -> children += listItems(valueStart, valueEnd)
                    }
                    attributes += DesignAttribute(
                        name,
                        s.substring(valueStart, valueEnd),
                        valueStart until valueEnd,
                        start until valueEnd,
                    )
                } else {
                    val valueEnd = trimEndAt(start, argEnd)
                    val name = positional.getOrNull(positionalCount) ?: "arg$positionalCount"
                    positionalCount++
                    attributes += DesignAttribute(
                        name,
                        s.substring(start, valueEnd),
                        start until valueEnd,
                        start until valueEnd,
                    )
                }
            }
            i = argEnd + 1
        }
        return Args(attributes, children)
    }

    /** The widgets inside a `[ … ]` list. */
    private fun listItems(from: Int, to: Int): List<DesignElement> {
        val open = s.indexOf('[', from)
        if (open < 0 || open >= to) return emptyList()
        val close = matchBracket(open)
        if (close < 0 || close > to) return emptyList()
        val out = mutableListOf<DesignElement>()
        var i = open + 1
        while (i < close) {
            i = skipTrivia(i)
            if (i >= close) break
            val end = endOfArgument(i, close)
            callAt(i, end)?.let { out += it }
            i = end + 1
        }
        return out
    }

    /** The end of one argument: the next top-level comma, or [to]. */
    private fun endOfArgument(from: Int, to: Int): Int {
        var depth = 0
        var i = from
        while (i < to) {
            when (val c = s[i]) {
                '"', '\'' -> { i = skipLiteral(i); continue }
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                ',' -> if (depth == 0) return i
                '/' -> if (s.getOrNull(i + 1) == '/' || s.getOrNull(i + 1) == '*') { i = skipTrivia(i); continue }
                else -> Unit
            }
            i++
        }
        return to
    }

    /** The `:` separating an argument name from its value, or -1 when the argument is positional. */
    private fun topLevelColon(from: Int, to: Int): Int {
        var depth = 0
        var i = from
        while (i < to) {
            when (val c = s[i]) {
                '"', '\'' -> { i = skipLiteral(i); continue }
                '(', '{', '[' -> depth++
                ')', '}', ']' -> depth--
                // `a ? b : c` also has a top-level colon; a name is a bare identifier before it.
                ':' -> if (depth == 0 && s.substring(from, i).trim().all { it.isLetterOrDigit() || it == '_' }) {
                    return i
                }
                else -> Unit
            }
            i++
        }
        return -1
    }

    // ---- scanning ----

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
        if (s.startsWith("$quote$quote$quote", from)) {
            val close = s.indexOf("$quote$quote$quote", from + 3)
            return if (close < 0) s.length else close + 3
        }
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
            '(' -> ')'
            '{' -> '}'
            '[' -> ']'
            else -> return -1
        }
        var depth = 0
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> { i = skipLiteral(i); continue }
                c == '/' && (s.getOrNull(i + 1) == '/' || s.getOrNull(i + 1) == '*') -> { i = skipTrivia(i); continue }
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

    private fun readIdentAt(at: Int): String {
        if (at >= s.length || !(s[at].isLetter() || s[at] == '_')) return ""
        var i = at
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(at, i)
    }

    private fun trimEndAt(from: Int, to: Int): Int {
        var i = to
        while (i > from && s[i - 1].isWhitespace()) i--
        return i
    }

    private fun indentAt(at: Int): String {
        var lineStart = at
        while (lineStart > 0 && s[lineStart - 1] != '\n') lineStart--
        return s.substring(lineStart, at).takeWhile { it == ' ' || it == '\t' }
    }

    private companion object {
        val BUILD = Regex("""Widget\s+build\s*\(""")
        val KEYWORDS = listOf("const", "new")
    }
}
