package dev.jcode.ext.android.designer

/**
 * A `.jsx` or `.tsx` file, read as the markup it is.
 *
 * The closest of the four formats to Android XML — elements with attributes and nested elements —
 * with two differences that matter for editing. Attribute values are usually expressions in braces
 * rather than strings, so a value here is source text and is written back verbatim, the same
 * contract the Compose and Dart sides use. And an element can have text content, which is recorded
 * as a `text` value so `<Text>Hello</Text>` is editable at all rather than being a node with
 * nothing in it.
 *
 * React Native is not rendered here — see [DesignFormat.rendersNatively]. The canvas approximates
 * the structure and says so; the edits go into the real source either way.
 */
internal class JsxDocument private constructor(
    override val text: String,
    override val root: DesignElement?,
) : DesignDocument {

    override val format: DesignFormat get() = DesignFormat.ReactNative

    override fun reparse(text: String): DesignDocument = parse(text)

    override fun propertiesFor(element: DesignElement): List<String> {
        val known = PROPERTIES[element.tag].orEmpty()
        val present = element.attributes.map { it.name }
        return (known + present).distinct()
    }

    override fun withAttribute(element: DesignElement, name: String, value: String): String {
        val existing = element.attributes.firstOrNull { it.name == name }
        if (existing != null) {
            if (existing.value == value) return text
            return text.replaceRange(existing.valueRange.first, existing.valueRange.last + 1, value)
        }
        if (name == TEXT) return withTextContent(element, value)
        return text.replaceRange(element.openTagEnd, element.openTagEnd, " $name=$value")
    }

    override fun withoutAttribute(element: DesignElement, name: String): String {
        val existing = element.attributes.firstOrNull { it.name == name } ?: return text
        if (name == TEXT) return withTextContent(element, "")
        var start = existing.range.first
        while (start > 0 && text[start - 1] == ' ') start--
        return text.removeRange(start, existing.range.last + 1)
    }

    override fun withChild(parent: DesignElement, snippet: String): String =
        withChildAt(parent, parent.children.size, snippet)

    override fun withChildAt(parent: DesignElement, index: Int, snippet: String): String {
        val childIndent = "${parent.indent}  "
        val block = snippet.trimEnd().lines().joinToString("\n") { if (it.isBlank()) it else "$childIndent$it" }

        val before = parent.children.getOrNull(index)
        if (before != null) {
            var at = before.range.first
            while (at > 0 && text[at - 1] != '\n') {
                if (!text[at - 1].isWhitespace()) {
                    return text.replaceRange(before.range.first, before.range.first, "$block\n$childIndent")
                }
                at--
            }
            return text.replaceRange(at, at, "$block\n")
        }

        if (parent.selfClosing) {
            // `<View />` has to become `<View>` + child + `</View>` before anything fits inside.
            val slash = text.lastIndexOf('/', parent.range.last)
            if (slash < 0) return text
            val head = text.substring(parent.range.first, slash).trimEnd()
            return text.replaceRange(
                parent.range.first,
                parent.range.last + 1,
                "$head>\n$block\n${parent.indent}</${parent.tag}>",
            )
        }
        val close = text.lastIndexOf("</", parent.range.last)
        if (close <= 0) return text
        var at = close
        while (at > 0 && text[at - 1].isWhitespace()) at--
        return text.replaceRange(at, close, "\n$block\n${parent.indent}")
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
     * Add the components this snippet uses to the imports.
     *
     * Prerequisites read `Name:module`, and a named import is merged into an existing
     * `import { … } from 'module'` rather than a second import line being added beside it — which
     * is legal but is not what anyone writes, and would show up in review as noise.
     */
    override fun withPrerequisites(item: PaletteItem): String {
        var result = text
        item.prerequisites.forEach { requirement ->
            val name = requirement.substringBefore(':')
            val module = requirement.substringAfter(':', "react-native")
            val existing = Regex("""import\s*\{([^}]*)\}\s*from\s*['"]${Regex.escape(module)}['"]""")
                .find(result)
            if (existing == null) {
                result = "import { $name } from '$module';\n$result"
                return@forEach
            }
            val names = existing.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (name in names) return@forEach
            val merged = (names + name).joinToString(", ")
            result = result.replaceRange(existing.range.first, existing.range.last + 1, "import { $merged } from '$module'")
        }
        return result
    }

    /** Replace (or clear) an element's text content, which is not an attribute but reads like one. */
    private fun withTextContent(element: DesignElement, value: String): String {
        if (element.selfClosing) {
            val slash = text.lastIndexOf('/', element.range.last)
            if (slash < 0) return text
            val head = text.substring(element.range.first, slash).trimEnd()
            return text.replaceRange(element.range.first, element.range.last + 1, "$head>$value</${element.tag}>")
        }
        val close = text.lastIndexOf("</", element.range.last)
        if (close <= element.openTagEnd) return text
        return text.replaceRange(element.openTagEnd + 1, close, value)
    }

    companion object {

        /** The name the parser gives an element's text content. */
        const val TEXT = "text"

        fun parse(text: String): JsxDocument = JsxDocument(text, JsxParser(text).parseRoot())

        private val PROPERTIES = mapOf(
            "Text" to listOf(TEXT, "style", "numberOfLines"),
            "View" to listOf("style"),
            "ScrollView" to listOf("style", "contentContainerStyle", "horizontal"),
            "Image" to listOf("source", "style", "resizeMode"),
            "TextInput" to listOf("value", "placeholder", "style", "onChangeText"),
            "Pressable" to listOf("onPress", "style"),
            "TouchableOpacity" to listOf("onPress", "style", "activeOpacity"),
            "Button" to listOf("title", "onPress", "color"),
            "FlatList" to listOf("data", "renderItem", "keyExtractor", "style"),
            "SafeAreaView" to listOf("style"),
        )
    }
}
