package dev.jcode.ext.android.designer

/**
 * One value set on an element, and exactly where it sits in the file.
 *
 * "Attribute" is the XML word for it, but the shape is the same in every language the designer
 * reads: a name, a value, and the character range each occupies. A Compose argument, a Flutter
 * constructor parameter and an XML attribute all edit the same way once they are described like
 * this — replace the value's characters, leave everything else alone.
 */
internal class DesignAttribute(
    val name: String,
    val value: String,
    /** The value's characters, excluding any quotes around them. */
    val valueRange: IntRange,
    /** The whole `name="value"` (or `name = value`), for removal. */
    val range: IntRange,
) {
    /** `android`, `app`, `tools` … or "" for an unprefixed name. */
    val prefix: String get() = name.substringBefore(':', "")

    /** The name without its prefix — what the renderer and the properties panel match on. */
    val local: String get() = name.substringAfter(':')
}

/**
 * One element of a design tree, wherever it was parsed from.
 *
 * Deliberately not an XML type. A layout is a tree of named things with values on them, and that is
 * as true of a Compose call as of an XML tag — so the layer tree, the properties panel, the hit
 * test and the whole drag-and-drop machinery are written against this and work unchanged on any
 * language a [DesignDocument] can parse. What differs between languages is how an edit is *spelled*,
 * and that lives in the document, not here.
 */
internal class DesignElement(
    val tag: String,
    val attributes: List<DesignAttribute>,
    val children: List<DesignElement>,
    /** The whole element, from its first character to its last. */
    val range: IntRange,
    /** Where a new attribute goes: the `>` of an XML open tag, the `)` of a Compose call. */
    val openTagEnd: Int,
    /** True when the element has no body to put a child into yet. */
    val selfClosing: Boolean,
    /** The element's own indentation, so anything inserted lines up with the file's style. */
    val indent: String,
) {
    fun attr(local: String): DesignAttribute? = attributes.firstOrNull { it.local == local }
    fun value(local: String): String? = attr(local)?.value

    /** Depth-first, this element first — the order a hit test wants to consider them in. */
    fun flatten(): List<DesignElement> = buildList {
        add(this@DesignElement)
        children.forEach { addAll(it.flatten()) }
    }
}
