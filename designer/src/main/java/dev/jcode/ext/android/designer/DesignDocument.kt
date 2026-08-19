package dev.jcode.ext.android.designer

import java.io.File

/**
 * A file the designer can edit, whatever language it is written in.
 *
 * Every method returns the **new file text** rather than a new tree. That is the whole contract: an
 * edit is a splice into the user's own bytes, so their formatting, ordering and comments survive a
 * visual change instead of a designer rewriting the file around it.
 *
 * A format that cannot express an operation returns the text unchanged. Silently doing nothing is
 * correct here in a way it usually is not — the alternative is emitting something the compiler will
 * reject, which is a designer that breaks the build in order to move a widget.
 */
internal interface DesignDocument {
    val text: String
    val root: DesignElement?

    /** What this format is called, for the parts of the UI that have to say so. */
    val format: DesignFormat

    fun withAttribute(element: DesignElement, name: String, value: String): String
    fun withoutAttribute(element: DesignElement, name: String): String

    /** Insert [snippet] as [parent]'s last child. */
    fun withChild(parent: DesignElement, snippet: String): String

    /** Insert [snippet] as [parent]'s child at [index], pushing the rest down. */
    fun withChildAt(parent: DesignElement, index: Int, snippet: String): String

    fun without(element: DesignElement): String

    /**
     * Whatever [item] needs declared before it can be used — an `xmlns:` on the root, an `import`
     * at the top of the file.
     *
     * Checked on every insert rather than assumed, because dropping a widget into a file that does
     * not declare what it needs produces a file that does not compile, and the designer would have
     * broken the build in order to add a widget.
     */
    fun withPrerequisites(item: PaletteItem): String

    /** The same document, re-read from [text] — an edit invalidates every range in this one. */
    fun reparse(text: String): DesignDocument

    /** The attribute names the properties panel offers for [element], most useful first. */
    fun propertiesFor(element: DesignElement): List<String>
}

/**
 * The languages the designer knows how to read.
 *
 * [rendersNatively] is the honest half of this. Android XML and Compose are rendered by running the
 * real thing — the plugin lives inside JCode's own Compose runtime and inflates against the real
 * Android widget set. Flutter and React Native have no runtime here to render with, and a designer
 * that drew an approximation of them while implying otherwise would be worse than one that says so:
 * their trees can be restructured and their properties edited, and the canvas shows the structure
 * rather than a picture that would be a guess.
 */
internal enum class DesignFormat(
    val label: String,
    val rendersNatively: Boolean,
) {
    AndroidXml("Android XML", rendersNatively = true),
    Compose("Jetpack Compose", rendersNatively = true),
    Flutter("Flutter", rendersNatively = false),
    ReactNative("React Native", rendersNatively = false),
    ;

    companion object {
        /**
         * The format [file] is written in, or null when the designer has nothing to offer for it.
         *
         * Compose and React Native are decided by content, not by extension: most `.kt` files are
         * not UI and most `.tsx` files are not either, and opening a designer on a data class helps
         * nobody. A file has to actually contain a composable, or actually return JSX.
         */
        fun of(file: File, text: String): DesignFormat? = when {
            file.extension == "xml" && text.contains("<") && file.parentFile?.name?.startsWith("layout") == true ->
                AndroidXml
            file.extension == "kt" && COMPOSABLE.containsMatchIn(text) -> Compose
            file.extension == "dart" && text.contains("Widget build(") -> Flutter
            file.extension in setOf("jsx", "tsx") && JSX_RETURN.containsMatchIn(text) -> ReactNative
            else -> null
        }

        private val COMPOSABLE = Regex("""@Composable\s+(private\s+|internal\s+|public\s+)?fun""")
        private val JSX_RETURN = Regex("""return\s*\(?\s*<[A-Z]""")
    }
}
