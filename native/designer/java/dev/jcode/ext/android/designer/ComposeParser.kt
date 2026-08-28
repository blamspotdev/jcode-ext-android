package dev.jcode.ext.android.designer

/**
 * Finds the composable call tree in a Kotlin file.
 *
 * Not a Kotlin parser, and deliberately not trying to be one. It knows three things: how to skip
 * over the parts of Kotlin that can contain anything (strings, chars, comments), how to match a
 * bracket, and what a composable call looks like at the start of a statement. Everything else in the
 * file is skipped by the line, which is exactly the behaviour wanted — an unrecognised statement is
 * left completely alone rather than half-understood.
 */
internal class ComposeParser(private val s: String) {

    /**
     * The composable function to design, as the tree root.
     *
     * The function rather than its first call, because a body is often several calls at the top
     * level and there would otherwise be no single node to hang them off — and because the function
     * is the thing the user opened, so it is the honest name for the root of the tree.
     */
    fun parseRoot(): DesignElement? {
        var from = 0
        while (true) {
            val at = s.indexOf("@Composable", from)
            if (at < 0) return null
            from = at + 11
            val fn = functionAfter(at) ?: continue
            val children = parseBlock(fn.bodyStart + 1, fn.bodyEnd)
            if (children.isEmpty()) continue
            return DesignElement(
                tag = fn.name,
                attributes = emptyList(),
                children = children,
                range = fn.start..fn.bodyEnd,
                openTagEnd = fn.bodyStart,
                selfClosing = false,
                indent = indentAt(fn.start),
            )
        }
    }

    private class Fn(val name: String, val start: Int, val bodyStart: Int, val bodyEnd: Int)

    /** The `fun name(...) { ... }` following an annotation, if that is what follows it. */
    private fun functionAfter(annotation: Int): Fn? {
        var i = annotation
        while (i < s.length) {
            i = skipTrivia(i)
            if (i >= s.length) return null
            if (s.startsWith("fun", i) && !isIdentPart(s.getOrNull(i + 3))) break
            // Annotations and modifiers may sit between the annotation and the `fun`; anything else
            // means this was not a function declaration and the search should move on.
            if (s[i] == '@') {
                i++
                i += readAnnotationNameAt(i).length
                i = skipTrivia(i)
                if (s.getOrNull(i) == '(') {
                    val close = matchBracket(i)
                    if (close < 0) return null
                    i = close + 1
                }
                continue
            }
            val word = readIdentAt(i)
            if (word.isEmpty() || word !in MODIFIERS) return null
            i += word.length
        }
        if (i >= s.length) return null
        i = skipTrivia(i + 3)
        val name = readIdentAt(i)
        if (name.isEmpty()) return null
        i += name.length
        i = skipTrivia(i)
        if (s.getOrNull(i) != '(') return null
        val paramsEnd = matchBracket(i)
        if (paramsEnd < 0) return null
        var j = skipTrivia(paramsEnd + 1)
        // An explicit return type sits between the parameters and the body.
        if (s.getOrNull(j) == ':') {
            while (j < s.length && s[j] != '{' && s[j] != '\n') j++
            j = skipTrivia(j)
        }
        if (s.getOrNull(j) != '{') return null
        val bodyEnd = matchBracket(j)
        if (bodyEnd < 0) return null
        return Fn(name, annotation, j, bodyEnd)
    }

    /** Every composable call directly inside [from, to). */
    private fun parseBlock(from: Int, to: Int): List<DesignElement> {
        val out = mutableListOf<DesignElement>()
        var i = from
        while (i < to) {
            i = skipTrivia(i)
            if (i >= to) break
            val call = callAt(i, to)
            if (call != null) {
                out += call
                i = call.range.last + 1
            } else {
                i = endOfStatement(i, to)
            }
        }
        return out
    }

    /**
     * A composable call at [i], or null if this is something else.
     *
     * The uppercase-first rule is the convention Compose is written to and the only signal available
     * without type resolution. A false positive would put a node in the tree that is not UI; the
     * rule costs nothing and there is no known case where composable UI breaks it.
     */
    private fun callAt(i: Int, limit: Int): DesignElement? {
        val name = readIdentAt(i)
        if (name.isEmpty() || !name[0].isUpperCase()) return null
        var j = i + name.length
        // `Modifier.padding(…)` and `Arrangement.Center` are values, not calls to draw something.
        val afterName = skipTrivia(j)
        if (s.getOrNull(afterName) == '.') return null

        var openTagEnd = j
        var attributes = emptyList<DesignAttribute>()
        var end: Int
        if (s.getOrNull(afterName) == '(') {
            val close = matchBracket(afterName)
            if (close < 0 || close >= limit) return null
            attributes = parseArguments(name, afterName + 1, close)
            openTagEnd = close
            end = close
            j = skipTrivia(close + 1)
        } else {
            j = afterName
            end = j - 1
        }

        var children = emptyList<DesignElement>()
        var selfClosing = true
        if (s.getOrNull(j) == '{') {
            val close = matchBracket(j)
            if (close in 0 until limit) {
                children = parseBlock(j + 1, close)
                selfClosing = false
                end = close
            }
        }
        if (end < i) return null

        return DesignElement(
            tag = name,
            attributes = attributes,
            children = children,
            range = i..end,
            openTagEnd = openTagEnd,
            selfClosing = selfClosing,
            indent = indentAt(i),
        )
    }

    /** The arguments between the parentheses, split at top level. */
    private fun parseArguments(call: String, from: Int, to: Int): List<DesignAttribute> {
        val out = mutableListOf<DesignAttribute>()
        val positional = ComposeDocument.POSITIONAL[call].orEmpty()
        var positionalCount = 0
        var i = from
        while (i < to) {
            i = skipTrivia(i)
            if (i >= to) break
            val start = i
            var depth = 0
            var argEnd = to
            var k = i
            while (k < to) {
                val c = s[k]
                when {
                    c == '"' || c == '\'' -> k = skipLiteral(k) - 1
                    c == '(' || c == '{' || c == '[' -> depth++
                    c == ')' || c == '}' || c == ']' -> depth--
                    c == ',' && depth == 0 -> { argEnd = k; k = to }
                    c == '/' && (s.getOrNull(k + 1) == '/' || s.getOrNull(k + 1) == '*') -> k = skipTrivia(k) - 1
                }
                k++
            }
            val raw = s.substring(start, argEnd)
            if (raw.isNotBlank()) {
                val eq = topLevelEquals(start, argEnd)
                if (eq >= 0) {
                    val name = s.substring(start, eq).trim()
                    val valueStart = skipTrivia(eq + 1)
                    val valueEnd = trimEndAt(valueStart, argEnd)
                    out += DesignAttribute(name, s.substring(valueStart, valueEnd), valueStart until valueEnd, start until trimEndAt(start, argEnd))
                } else {
                    val valueEnd = trimEndAt(start, argEnd)
                    val name = positional.getOrNull(positionalCount) ?: "arg$positionalCount"
                    positionalCount++
                    out += DesignAttribute(name, s.substring(start, valueEnd), start until valueEnd, start until valueEnd)
                }
            }
            i = argEnd + 1
        }
        return out
    }

    /** The `=` separating an argument name from its value, or -1 when the argument is positional. */
    private fun topLevelEquals(from: Int, to: Int): Int {
        var depth = 0
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '"' || c == '\'' -> { i = skipLiteral(i); continue }
                c == '(' || c == '{' || c == '[' -> depth++
                c == ')' || c == '}' || c == ']' -> depth--
                c == '=' && depth == 0 && s.getOrNull(i + 1) != '=' && s.getOrNull(i - 1) !in COMPARISON ->
                    return i
            }
            i++
        }
        return -1
    }

    // ---- scanning ----

    /** Past whitespace and comments. */
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

    /** Past a string or character literal, raw strings included. */
    private fun skipLiteral(from: Int): Int {
        if (s.startsWith("\"\"\"", from)) {
            val close = s.indexOf("\"\"\"", from + 3)
            return if (close < 0) s.length else close + 3
        }
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

    /** The index of the bracket closing the one at [open], or -1 when it is never closed. */
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

    /** The end of an unrecognised statement — the line, unless a bracket carries it further. */
    private fun endOfStatement(from: Int, limit: Int): Int {
        var i = from
        while (i < limit) {
            val c = s[i]
            when {
                c == '\n' -> return i + 1
                c == '"' || c == '\'' -> { i = skipLiteral(i); continue }
                c == '(' || c == '{' || c == '[' -> {
                    val close = matchBracket(i)
                    i = if (close < 0) limit else close + 1
                    continue
                }
            }
            i++
        }
        return limit
    }

    /** An annotation name, which may be qualified (`androidx…Composable`) or targeted (`field:`). */
    private fun readAnnotationNameAt(at: Int): String {
        var i = at
        while (i < s.length && (isIdentPart(s[i]) || s[i] == '.' || s[i] == ':')) i++
        return s.substring(at, i)
    }

    private fun readIdentAt(at: Int): String {
        if (at >= s.length || !isIdentStart(s[at])) return ""
        var i = at
        while (i < s.length && isIdentPart(s[i])) i++
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

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char?) = c != null && (c.isLetterOrDigit() || c == '_')

    private companion object {
        val MODIFIERS = setOf(
            "private", "internal", "public", "protected", "inline", "suspend", "expect", "actual",
            "override", "open", "abstract", "final", "operator", "infix", "tailrec", "external",
        )
        val COMPARISON = setOf('!', '<', '>', '=')
    }
}
