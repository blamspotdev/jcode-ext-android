package dev.jcode.ext.android.designer

/**
 * A Kotlin file containing composable UI, parsed to the same tree everything else here works on.
 *
 * The plan for this designer originally said Compose was out of scope, on the grounds that Compose
 * UI is code rather than markup and there is no document to write back to. That is true of Compose
 * *in general* and false of the shape almost every screen is actually written in: a tree of calls
 * with literal arguments. This reads that shape and leaves the rest alone.
 *
 * What it will not do is guess. A call it does not recognise is still a node — it appears in the
 * layer tree, it can be selected, moved and deleted — but the canvas draws it as a labelled box
 * rather than inventing a picture of it, and its arguments are shown as the expressions they are
 * rather than being reinterpreted. `if`, `when`, `remember` and everything else in the body is not a
 * node at all and is never touched: an edit is still a splice, so the code the designer does not
 * understand is the code it cannot break.
 *
 * Every value here is **source text**, not a parsed value: `20.sp`, `Modifier.padding(8.dp)`,
 * `"Hello"` with its quotes. The properties panel edits the expression, which is the only honest
 * thing to show for a language where an argument can be anything.
 */
internal class ComposeDocument private constructor(
    override val text: String,
    override val root: DesignElement?,
) : DesignDocument {

    override val format: DesignFormat get() = DesignFormat.Compose

    override fun reparse(text: String): DesignDocument = parse(text)

    override fun suggestionsFor(element: DesignElement, property: String): List<String> =
        SUGGESTIONS[property].orEmpty()

    override fun withTag(element: DesignElement, tag: String): String =
        text.replaceRange(element.range.first, element.range.first + element.tag.length, tag)

    override fun propertiesFor(element: DesignElement): List<String> {
        val known = PROPERTIES[element.tag].orEmpty()
        // Whatever is already written wins a place, so an argument the designer has never heard of
        // is still editable rather than being invisible until someone opens the source view.
        val present = element.attributes.map { it.name }.filterNot { it.startsWith("arg") }
        return (known + present + "modifier").distinct()
    }

    // ---- edits ----

    override fun withAttribute(element: DesignElement, name: String, value: String): String {
        val existing = element.attributes.firstOrNull { it.name == name }
        if (existing != null) {
            if (existing.value == value) return text
            return text.replaceRange(existing.valueRange.first, existing.valueRange.last + 1, value)
        }
        val open = text.lastIndexOf('(', element.openTagEnd)
        if (open < 0 || open >= element.openTagEnd) {
            // A call written without parentheses at all — `Column { … }`. Give it some.
            val nameEnd = element.range.first + element.tag.length
            return text.replaceRange(nameEnd, nameEnd, "($name = $value)")
        }
        val args = text.substring(open + 1, element.openTagEnd)
        val empty = args.isBlank()
        val perLine = args.contains('\n')
        val insertion = when {
            empty && perLine -> "$name = $value"
            empty -> "$name = $value"
            // A trailing comma is Kotlin-legal and is what a multi-line argument list almost always
            // already ends with, so matching it keeps the diff to the one line that changed.
            perLine -> "${element.indent}    $name = $value,\n${element.indent}"
            else -> ", $name = $value"
        }
        val at = if (perLine && !empty) {
            var k = element.openTagEnd
            while (k > open && text[k - 1].isWhitespace()) k--
            if (k > open && text[k - 1] == ',') k else k
        } else {
            element.openTagEnd
        }
        return if (perLine && !empty) {
            val prefix = if (text.getOrNull(at - 1) == ',') "\n" else ",\n"
            text.replaceRange(at, element.openTagEnd, "$prefix$insertion")
        } else {
            text.replaceRange(at, at, insertion)
        }
    }

    override fun withoutAttribute(element: DesignElement, name: String): String {
        val existing = element.attributes.firstOrNull { it.name == name } ?: return text
        var start = existing.range.first
        var end = existing.range.last + 1
        // Take the separator with it, from whichever side has one, or the argument list ends up
        // with a stray comma that does not compile.
        while (end < text.length && (text[end] == ' ' || text[end] == ',')) {
            val wasComma = text[end] == ','
            end++
            if (wasComma) break
        }
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        if (start > 0 && text[start - 1] == '\n' && text.getOrNull(end) != ')') {
            var k = start - 1
            while (k > 0 && text[k - 1] != '\n' && text[k - 1].isWhitespace()) k--
            start = k
        }
        return text.removeRange(start, end)
    }

    override fun withChild(parent: DesignElement, snippet: String): String {
        val childIndent = "${parent.indent}    "
        val block = snippet.trimEnd().lines().joinToString("\n") { if (it.isBlank()) it else "$childIndent$it" }
        val close = trailingLambdaClose(parent)
        if (close < 0) {
            // No content lambda yet: `Card(…)` has to become `Card(…) { … }` before anything fits.
            val after = parent.range.last + 1
            return text.replaceRange(after, after, " {\n$block\n${parent.indent}}")
        }
        var at = close
        while (at > 0 && text[at - 1].isWhitespace()) at--
        return text.replaceRange(at, close, "\n$block\n${parent.indent}")
    }

    override fun withChildAt(parent: DesignElement, index: Int, snippet: String): String {
        val before = parent.children.getOrNull(index) ?: return withChild(parent, snippet)
        val childIndent = "${parent.indent}    "
        val block = snippet.trimEnd().lines().joinToString("\n") { if (it.isBlank()) it else "$childIndent$it" }
        var at = before.range.first
        while (at > 0 && text[at - 1] != '\n') {
            if (!text[at - 1].isWhitespace()) {
                return text.replaceRange(before.range.first, before.range.first, "$block\n$childIndent")
            }
            at--
        }
        return text.replaceRange(at, at, "$block\n")
    }

    override fun without(element: DesignElement): String {
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

    /**
     * Add any import the snippet needs.
     *
     * Compose spells its prerequisites as imports rather than as namespaces, but it is the same
     * problem the XML side has: a widget dropped into a file that cannot name it is a file that does
     * not compile, and the designer would have broken the build in order to add a widget.
     */
    override fun withPrerequisites(item: PaletteItem): String {
        val missing = item.prerequisites.filterNot { fqn ->
            Regex("""^\s*import\s+${Regex.escape(fqn)}\s*$""", RegexOption.MULTILINE).containsMatchIn(text)
        }
        if (missing.isEmpty()) return text

        val lastImport = Regex("""(?m)^import\s+\S+[ \t]*$""").findAll(text).lastOrNull()
        val block = missing.joinToString("\n") { "import $it" }
        if (lastImport != null) {
            val at = lastImport.range.last + 1
            return text.replaceRange(at, at, "\n$block")
        }
        val pkg = Regex("""(?m)^package\s+\S+[ \t]*$""").find(text) ?: return text
        val at = pkg.range.last + 1
        return text.replaceRange(at, at, "\n\n$block")
    }

    /** The `}` closing this call's trailing lambda, or -1 when it has none. */
    private fun trailingLambdaClose(element: DesignElement): Int {
        if (element.selfClosing) return -1
        val last = element.range.last
        return if (text.getOrNull(last) == '}') last else -1
    }

    companion object {

        fun parse(text: String): ComposeDocument = ComposeDocument(text, ComposeParser(text).parseRoot())

        /** The arguments worth offering first, per composable. */
        private val PROPERTIES = mapOf(
            "Text" to listOf("text", "fontSize", "color", "fontWeight", "textAlign", "maxLines"),
            "Button" to listOf("onClick", "enabled"),
            "OutlinedButton" to listOf("onClick", "enabled"),
            "TextButton" to listOf("onClick", "enabled"),
            "Column" to listOf("verticalArrangement", "horizontalAlignment"),
            "Row" to listOf("horizontalArrangement", "verticalAlignment"),
            "Box" to listOf("contentAlignment"),
            "Spacer" to emptyList(),
            "Icon" to listOf("imageVector", "contentDescription", "tint"),
            "Card" to listOf("elevation", "shape"),
            "OutlinedTextField" to listOf("value", "onValueChange", "label", "placeholder"),
            "TextField" to listOf("value", "onValueChange", "label", "placeholder"),
            "Divider" to listOf("thickness", "color"),
            "HorizontalDivider" to listOf("thickness", "color"),
            "LazyColumn" to listOf("verticalArrangement", "contentPadding"),
            "LazyRow" to listOf("horizontalArrangement", "contentPadding"),
        )

        private val SPACING = listOf("Arrangement.spacedBy(4.dp)", "Arrangement.spacedBy(8.dp)", "Arrangement.spacedBy(16.dp)")

        private val SUGGESTIONS = mapOf(
            "verticalArrangement" to listOf(
                "Arrangement.Top", "Arrangement.Center", "Arrangement.Bottom",
                "Arrangement.SpaceBetween", "Arrangement.SpaceAround", "Arrangement.SpaceEvenly",
            ) + SPACING,
            "horizontalArrangement" to listOf(
                "Arrangement.Start", "Arrangement.Center", "Arrangement.End",
                "Arrangement.SpaceBetween", "Arrangement.SpaceAround", "Arrangement.SpaceEvenly",
            ) + SPACING,
            "horizontalAlignment" to listOf("Alignment.Start", "Alignment.CenterHorizontally", "Alignment.End"),
            "verticalAlignment" to listOf("Alignment.Top", "Alignment.CenterVertically", "Alignment.Bottom"),
            "contentAlignment" to listOf(
                "Alignment.TopStart", "Alignment.TopCenter", "Alignment.TopEnd",
                "Alignment.CenterStart", "Alignment.Center", "Alignment.CenterEnd",
                "Alignment.BottomStart", "Alignment.BottomCenter", "Alignment.BottomEnd",
            ),
            "fontWeight" to listOf(
                "FontWeight.Light", "FontWeight.Normal", "FontWeight.Medium",
                "FontWeight.SemiBold", "FontWeight.Bold", "FontWeight.ExtraBold",
            ),
            "textAlign" to listOf("TextAlign.Start", "TextAlign.Center", "TextAlign.End", "TextAlign.Justify"),
            "fontSize" to listOf("12.sp", "14.sp", "16.sp", "20.sp", "24.sp", "32.sp"),
            "maxLines" to listOf("1", "2", "3"),
            "color" to listOf(
                "MaterialTheme.colorScheme.primary", "MaterialTheme.colorScheme.onSurface",
                "MaterialTheme.colorScheme.onSurfaceVariant", "MaterialTheme.colorScheme.error",
                "Color.Black", "Color.White", "Color.Gray",
            ),
        )

        /**
         * What an unnamed argument means, per composable.
         *
         * `Text("Hello")` is overwhelmingly how Text is written, and a designer that showed that
         * string as `arg0` would be describing Kotlin rather than describing the screen.
         */
        val POSITIONAL = mapOf(
            "Text" to listOf("text"),
            "Icon" to listOf("imageVector", "contentDescription"),
            "Image" to listOf("painter", "contentDescription"),
            "Spacer" to listOf("modifier"),
        )
    }
}
