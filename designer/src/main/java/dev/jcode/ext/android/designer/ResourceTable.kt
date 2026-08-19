package dev.jcode.ext.android.designer

import android.graphics.Color
import java.io.File

/**
 * The project's `res/values` read from **source**.
 *
 * Deliberately not from the built APK. A designer is looked at while the file is being edited, and
 * the whole value of it is that changing `colors.xml` and coming back shows the new colour — which a
 * compiled resource table cannot do until the next build. The compiled table is still the right
 * source for the *widgets* (see the renderer's fallback), but not for these.
 *
 * Parsed with the same tolerant scanner as the layouts, so a half-typed `colors.xml` degrades to
 * "that one name does not resolve" rather than to an empty table.
 */
internal class ResourceTable private constructor(
    private val colors: Map<String, String>,
    private val strings: Map<String, String>,
    private val dimens: Map<String, String>,
    private val bools: Map<String, String>,
    private val integers: Map<String, String>,
) {

    /** A `@string/…` reference resolved to its text, or the reference itself when it does not. */
    fun string(ref: String): String = resolve(ref, strings) ?: ref

    /**
     * A colour reference or literal as an ARGB int, or null when it cannot be resolved.
     *
     * Null rather than a guess: a widget drawn in the wrong colour is worse than one drawn in the
     * default, because it looks deliberate.
     */
    fun color(ref: String): Int? {
        val literal = resolve(ref, colors) ?: ref
        if (!literal.startsWith("#")) return null
        return runCatching { Color.parseColor(expandShorthand(literal)) }.getOrNull()
    }

    /** A dimension in pixels, given the display density. `sp` is treated as `dp` — see the renderer. */
    fun dimension(ref: String, density: Float): Float? {
        val literal = resolve(ref, dimens) ?: ref
        val number = literal.takeWhile { it.isDigit() || it == '.' || it == '-' }
        if (number.isEmpty()) return null
        val v = number.toFloatOrNull() ?: return null
        return when {
            literal.endsWith("px") -> v
            literal.endsWith("dp") || literal.endsWith("dip") -> v * density
            literal.endsWith("sp") -> v * density
            else -> v * density
        }
    }

    fun bool(ref: String): Boolean? = (resolve(ref, bools) ?: ref).toBooleanStrictOrNull()

    fun integer(ref: String): Int? = (resolve(ref, integers) ?: ref).toIntOrNull()

    /**
     * Follow `@type/name` to its value, one hop at a time so `@color/a` → `@color/b` → `#fff` works.
     * Bounded, because a resource file can genuinely contain a cycle and a designer must not hang on
     * one — it must draw the rest of the screen.
     */
    private fun resolve(ref: String, table: Map<String, String>): String? {
        var current = ref
        repeat(8) {
            if (!current.startsWith("@")) return current
            if (current.startsWith("@android:")) return null
            val name = current.substringAfter('/', "")
            current = table[name] ?: return null
        }
        return null
    }

    private fun expandShorthand(hex: String): String = when (hex.length) {
        4 -> "#" + hex.drop(1).flatMap { listOf(it, it) }.joinToString("")
        5 -> "#" + hex.drop(1).flatMap { listOf(it, it) }.joinToString("")
        else -> hex
    }

    companion object {

        val EMPTY = ResourceTable(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

        /**
         * Read every values XML under [projectDir] — `res/values`, `res/values-night`, and so on.
         *
         * Every values directory, not just `values/`: a project that keeps its palette in
         * `values-night/` would otherwise resolve nothing at all. Later files win, which is
         * arbitrary but predictable, and the designer's own theme switch is what the user reaches
         * for when they want to see the other one.
         */
        fun read(projectDir: File?): ResourceTable {
            if (projectDir == null) return EMPTY
            val colors = mutableMapOf<String, String>()
            val strings = mutableMapOf<String, String>()
            val dimens = mutableMapOf<String, String>()
            val bools = mutableMapOf<String, String>()
            val integers = mutableMapOf<String, String>()

            valuesFiles(projectDir).forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                LayoutDocument.parse(text).root?.children?.forEach { entry ->
                    val name = entry.value("name") ?: return@forEach
                    val body = bodyOf(text, entry)
                    when (entry.tag) {
                        "color" -> colors[name] = body
                        "string" -> strings[name] = body
                        "dimen" -> dimens[name] = body
                        "bool" -> bools[name] = body
                        "integer" -> integers[name] = body
                    }
                }
            }
            return ResourceTable(colors, strings, dimens, bools, integers)
        }

        /** The text between an element's tags — the scanner records ranges, not character data. */
        private fun bodyOf(text: String, e: LayoutDocument.Element): String {
            if (e.selfClosing) return ""
            val start = (e.openTagEnd + 1).coerceAtMost(text.length)
            val close = text.lastIndexOf("</", e.range.last).takeIf { it > start } ?: return ""
            return text.substring(start, close).trim()
        }

        private fun valuesFiles(projectDir: File): List<File> {
            // Both layouts: a single-module project with res/ at the root, and the ordinary
            // app/src/main/res. Walking the whole project would be slower and would pick up build
            // output, which holds a stale copy of exactly these files.
            val roots = listOf(
                File(projectDir, "app/src/main/res"),
                File(projectDir, "src/main/res"),
                File(projectDir, "res"),
            )
            return roots.filter { it.isDirectory }.flatMap { root ->
                root.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
                    .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            }
        }
    }
}
