package dev.jcode.ext.android.designer

/**
 * A Dart file containing a Flutter widget tree.
 *
 * The same shape as the Compose side — a tree of calls with named arguments — with one structural
 * difference that changes every edit: **children are arguments**. A Flutter widget holds its
 * children in `child:` or `children: [...]` rather than in a trailing block, so inserting one means
 * writing into an argument list, and a widget that has neither yet needs the right one invented for
 * it. Which of the two it needs is a property of the widget, which is why [MULTI_CHILD] exists.
 *
 * Flutter is not rendered here — see [DesignFormat.rendersNatively]. There is no Dart runtime in
 * this process and never will be; the canvas draws an approximation and says so, and every edit
 * still goes into the user's real source.
 */
internal class DartDocument private constructor(
    override val text: String,
    override val root: DesignElement?,
) : DesignDocument {

    override val format: DesignFormat get() = DesignFormat.Flutter

    override fun reparse(text: String): DesignDocument = parse(text)

    override fun propertiesFor(element: DesignElement): List<String> {
        val known = PROPERTIES[element.tag].orEmpty()
        val present = element.attributes.map { it.name }
            .filterNot { it.startsWith("arg") || it == "child" || it == "children" }
        return (known + present).distinct()
    }

    override fun withAttribute(element: DesignElement, name: String, value: String): String {
        val existing = element.attributes.firstOrNull { it.name == name }
        if (existing != null) {
            if (existing.value == value) return text
            return text.replaceRange(existing.valueRange.first, existing.valueRange.last + 1, value)
        }
        val open = text.lastIndexOf('(', element.openTagEnd)
        if (open < 0 || open >= element.openTagEnd) return text
        val args = text.substring(open + 1, element.openTagEnd)
        // Dart argument lists are conventionally one per line with a trailing comma, and matching
        // that keeps the diff to the line that changed rather than reflowing the call.
        val insertion = when {
            args.isBlank() -> "$name: $value"
            args.contains('\n') -> "${element.indent}  $name: $value,\n${element.indent}"
            else -> ", $name: $value"
        }
        return if (args.contains('\n') && args.isNotBlank()) {
            var at = element.openTagEnd
            while (at > open && text[at - 1].isWhitespace()) at--
            val prefix = if (text.getOrNull(at - 1) == ',') "\n" else ",\n"
            text.replaceRange(at, element.openTagEnd, "$prefix$insertion")
        } else {
            text.replaceRange(element.openTagEnd, element.openTagEnd, insertion)
        }
    }

    override fun withoutAttribute(element: DesignElement, name: String): String {
        val existing = element.attributes.firstOrNull { it.name == name } ?: return text
        var start = existing.range.first
        var end = existing.range.last + 1
        while (end < text.length && (text[end] == ' ' || text[end] == ',')) {
            val comma = text[end] == ','
            end++
            if (comma) break
        }
        while (start > 0 && (text[start - 1] == ' ' || text[start - 1] == '\t')) start--
        if (start > 0 && text[start - 1] == '\n') start--
        return text.removeRange(start, end)
    }

    override fun withChild(parent: DesignElement, snippet: String): String =
        withChildAt(parent, parent.children.size, snippet)

    override fun withChildAt(parent: DesignElement, index: Int, snippet: String): String {
        val childIndent = "${parent.indent}  "
        val block = snippet.trimEnd().lines()
            .joinToString("\n") { if (it.isBlank()) it else "$childIndent  $it" }
            .trimStart()

        val before = parent.children.getOrNull(index)
        if (before != null) {
            var at = before.range.first
            while (at > 0 && text[at - 1] != '\n') {
                if (!text[at - 1].isWhitespace()) {
                    return text.replaceRange(before.range.first, before.range.first, "$block, ")
                }
                at--
            }
            val indent = text.substring(at, before.range.first)
            return text.replaceRange(at, at, "$indent$block,\n")
        }

        // Appending. Where depends on what the widget already has to hold children with.
        val children = parent.attributes.firstOrNull { it.name == "children" }
        if (children != null) {
            val close = text.lastIndexOf(']', children.valueRange.last)
            if (close < 0) return text
            var at = close
            while (at > 0 && text[at - 1].isWhitespace()) at--
            val separator = if (text.getOrNull(at - 1) == ',' || at == children.valueRange.first) "" else ","
            return text.replaceRange(at, close, "$separator\n$childIndent  $block,\n$childIndent")
        }
        if (parent.attributes.any { it.name == "child" }) {
            // A single-child widget that already has one. Refusing is correct: silently discarding
            // the child it has, or emitting a second `child:`, would both be worse than saying no.
            return text
        }
        val slot = if (parent.tag in MULTI_CHILD) "children: [$block]" else "child: $block"
        return withAttribute(parent, slot.substringBefore(':'), slot.substringAfter(": "))
    }

    override fun without(element: DesignElement): String {
        var start = element.range.first
        var end = element.range.last + 1
        while (end < text.length && (text[end] == ' ' || text[end] == ',')) {
            val comma = text[end] == ','
            end++
            if (comma) break
        }
        while (start > 0 && text[start - 1] != '\n') {
            if (!text[start - 1].isWhitespace()) return text.removeRange(start, end)
            start--
        }
        while (end < text.length && text[end] != '\n') {
            if (!text[end].isWhitespace()) return text.removeRange(start, end)
            end++
        }
        if (end < text.length) end++
        return text.removeRange(start, end)
    }

    /** Dart spells its prerequisites as package imports, at the top of the file. */
    override fun withPrerequisites(item: PaletteItem): String {
        val missing = item.prerequisites.filterNot { text.contains("import '$it'") }
        if (missing.isEmpty()) return text
        val block = missing.joinToString("\n") { "import '$it';" }
        val lastImport = Regex("""(?m)^import\s+'[^']+';[ \t]*$""").findAll(text).lastOrNull()
        if (lastImport != null) {
            val at = lastImport.range.last + 1
            return text.replaceRange(at, at, "\n$block")
        }
        return "$block\n\n$text"
    }

    companion object {

        fun parse(text: String): DartDocument = DartDocument(text, DartParser(text).parseRoot())

        /** Widgets that hold a list rather than a single child. */
        val MULTI_CHILD = setOf(
            "Column", "Row", "Stack", "Wrap", "ListView", "GridView", "Flex", "IndexedStack",
            "CustomMultiChildLayout", "Table",
        )

        private val PROPERTIES = mapOf(
            "Text" to listOf("data", "style", "textAlign", "maxLines", "overflow"),
            "Container" to listOf("width", "height", "padding", "margin", "color", "alignment"),
            "Padding" to listOf("padding"),
            "Column" to listOf("mainAxisAlignment", "crossAxisAlignment", "mainAxisSize"),
            "Row" to listOf("mainAxisAlignment", "crossAxisAlignment", "mainAxisSize"),
            "SizedBox" to listOf("width", "height"),
            "ElevatedButton" to listOf("onPressed", "style"),
            "TextButton" to listOf("onPressed", "style"),
            "OutlinedButton" to listOf("onPressed", "style"),
            "Icon" to listOf("size", "color"),
            "Center" to emptyList(),
            "Expanded" to listOf("flex"),
            "Card" to listOf("elevation", "color"),
            "Scaffold" to listOf("appBar", "backgroundColor"),
        )

        /** What an unnamed argument means, per widget. */
        val POSITIONAL = mapOf(
            "Text" to listOf("data"),
            "Icon" to listOf("icon"),
            "SizedBox" to emptyList<String>(),
        )
    }
}
