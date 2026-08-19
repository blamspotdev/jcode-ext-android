package dev.jcode.ext.android.designer

/**
 * What the palette can insert.
 *
 * Two things are deliberate here.
 *
 * **Material and AndroidX widgets are offered even though they render as placeholders.** The
 * alternative — hiding everything this plugin cannot draw — leaves a palette that is a poor
 * description of Android, and the user reaches for a card or a FAB constantly. So they are offered
 * and [rendersForReal] says which is which, and the palette says so on the row. Being told "this
 * will show as an outline until you build" is workable; silently drawing a grey box the user did
 * not expect, or silently not offering the widget at all, is not.
 *
 * **Every entry carries the namespaces it needs.** Dropping a `MaterialCardView` into a layout whose
 * root declares no `xmlns:app` produces a file that does not compile, and the designer would have
 * broken the build to add a widget. See [LayoutDocument.withNamespaces].
 */
internal data class PaletteItem(
    val label: String,
    val category: String,
    val xml: String,
    /** False when the renderer will draw this as a labelled placeholder rather than as itself. */
    val rendersForReal: Boolean = true,
    /** Namespace prefixes the snippet uses, so the root can be given them if it lacks them. */
    val namespaces: List<String> = emptyList(),
    /** Extra words the search should match — how someone might look for it. */
    val keywords: String = "",
)

internal object Palette {

    const val TEXT = "Text"
    const val BUTTONS = "Buttons"
    const val LAYOUTS = "Layouts"
    const val WIDGETS = "Widgets"
    const val MATERIAL = "Material"
    const val ANDROIDX = "AndroidX"

    val categories = listOf(TEXT, BUTTONS, LAYOUTS, WIDGETS, MATERIAL, ANDROIDX)

    val items: List<PaletteItem> = listOf(
        // ---- Text ----
        PaletteItem(
            "TextView", TEXT,
            wrap("TextView", "android:text=\"Text\""),
            keywords = "label caption",
        ),
        PaletteItem(
            "Heading", TEXT,
            wrap("TextView", "android:text=\"Heading\"", "android:textSize=\"24sp\"", "android:textStyle=\"bold\""),
            keywords = "title h1 bold",
        ),
        PaletteItem(
            "EditText", TEXT,
            match("EditText", "android:hint=\"Enter text\"", "android:inputType=\"text\""),
            keywords = "input field form",
        ),
        PaletteItem(
            "Password field", TEXT,
            match("EditText", "android:hint=\"Password\"", "android:inputType=\"textPassword\""),
            keywords = "input secure",
        ),

        // ---- Buttons ----
        PaletteItem("Button", BUTTONS, wrap("Button", "android:text=\"Button\""), keywords = "action tap"),
        PaletteItem(
            "ImageButton", BUTTONS,
            "<ImageButton\n    android:layout_width=\"48dp\"\n    android:layout_height=\"48dp\" />",
            keywords = "icon action",
        ),
        PaletteItem("CheckBox", BUTTONS, wrap("CheckBox", "android:text=\"Check me\""), keywords = "tick toggle"),
        PaletteItem("RadioButton", BUTTONS, wrap("RadioButton", "android:text=\"Option\""), keywords = "choice"),
        PaletteItem("Switch", BUTTONS, wrap("Switch", "android:text=\"Enabled\""), keywords = "toggle on off"),

        // ---- Layouts ----
        PaletteItem(
            "LinearLayout (vertical)", LAYOUTS,
            container("LinearLayout", "android:orientation=\"vertical\""),
            keywords = "column stack rows",
        ),
        PaletteItem(
            "LinearLayout (horizontal)", LAYOUTS,
            container("LinearLayout", "android:orientation=\"horizontal\""),
            keywords = "row side by side",
        ),
        PaletteItem("FrameLayout", LAYOUTS, container("FrameLayout"), keywords = "stack overlay"),
        PaletteItem(
            "ConstraintLayout", LAYOUTS,
            container("androidx.constraintlayout.widget.ConstraintLayout"),
            namespaces = listOf("app"),
            keywords = "constraints flat",
        ),
        PaletteItem("ScrollView", LAYOUTS, container("ScrollView"), keywords = "scroll overflow"),
        PaletteItem(
            "Spacer", LAYOUTS,
            "<Space\n    android:layout_width=\"0dp\"\n    android:layout_height=\"16dp\" />",
            keywords = "gap padding",
        ),
        PaletteItem(
            "Divider", LAYOUTS,
            "<View\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"1dp\"\n    android:background=\"#1F000000\" />",
            keywords = "rule separator line",
        ),

        // ---- Widgets ----
        PaletteItem(
            "ImageView", WIDGETS,
            "<ImageView\n    android:layout_width=\"48dp\"\n    android:layout_height=\"48dp\" />",
            keywords = "picture icon drawable",
        ),
        PaletteItem("ProgressBar", WIDGETS, wrap("ProgressBar"), keywords = "spinner loading"),
        PaletteItem(
            "ProgressBar (bar)", WIDGETS,
            match("ProgressBar", "style=\"?android:attr/progressBarStyleHorizontal\"", "android:progress=\"40\""),
            keywords = "loading determinate",
        ),
        PaletteItem("SeekBar", WIDGETS, match("SeekBar"), keywords = "slider range"),

        // ---- Material (placeholders until the project is built) ----
        PaletteItem(
            "MaterialButton", MATERIAL,
            wrap("com.google.android.material.button.MaterialButton", "android:text=\"Button\""),
            rendersForReal = false, namespaces = listOf("app"), keywords = "filled tonal",
        ),
        PaletteItem(
            "MaterialCardView", MATERIAL,
            container("com.google.android.material.card.MaterialCardView", "app:cardCornerRadius=\"12dp\""),
            rendersForReal = false, namespaces = listOf("app"), keywords = "card surface",
        ),
        PaletteItem(
            "TextInputLayout", MATERIAL,
            container("com.google.android.material.textfield.TextInputLayout", "android:hint=\"Label\""),
            rendersForReal = false, namespaces = listOf("app"), keywords = "outlined field form",
        ),
        PaletteItem(
            "Chip", MATERIAL,
            wrap("com.google.android.material.chip.Chip", "android:text=\"Chip\""),
            rendersForReal = false, namespaces = listOf("app"), keywords = "tag pill",
        ),
        PaletteItem(
            "FloatingActionButton", MATERIAL,
            wrap("com.google.android.material.floatingactionbutton.FloatingActionButton"),
            rendersForReal = false, namespaces = listOf("app"), keywords = "fab action",
        ),
        PaletteItem(
            "BottomNavigationView", MATERIAL,
            match("com.google.android.material.bottomnavigation.BottomNavigationView"),
            rendersForReal = false, namespaces = listOf("app"), keywords = "tabs bottom bar",
        ),
        PaletteItem(
            "MaterialToolbar", MATERIAL,
            match("com.google.android.material.appbar.MaterialToolbar", "android:layout_height=\"?attr/actionBarSize\""),
            rendersForReal = false, namespaces = listOf("app"), keywords = "app bar title",
        ),

        // ---- AndroidX ----
        PaletteItem(
            "RecyclerView", ANDROIDX,
            container("androidx.recyclerview.widget.RecyclerView"),
            rendersForReal = false, namespaces = listOf("app"), keywords = "list grid adapter",
        ),
        PaletteItem(
            "ViewPager2", ANDROIDX,
            container("androidx.viewpager2.widget.ViewPager2"),
            rendersForReal = false, keywords = "pager swipe",
        ),
        PaletteItem(
            "SwipeRefreshLayout", ANDROIDX,
            container("androidx.swiperefreshlayout.widget.SwipeRefreshLayout"),
            rendersForReal = false, keywords = "pull to refresh",
        ),
        PaletteItem(
            "CoordinatorLayout", ANDROIDX,
            container("androidx.coordinatorlayout.widget.CoordinatorLayout"),
            rendersForReal = false, namespaces = listOf("app"), keywords = "scrolling app bar",
        ),
    )

    /** Items matching [query], across label, category and keywords; everything when it is blank. */
    fun search(query: String): List<PaletteItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return items
        return items.filter {
            it.label.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    private fun wrap(tag: String, vararg attrs: String): String =
        build(tag, "wrap_content", "wrap_content", attrs, selfClosing = true)

    private fun match(tag: String, vararg attrs: String): String =
        build(tag, "match_parent", "wrap_content", attrs, selfClosing = true)

    private fun container(tag: String, vararg attrs: String): String =
        build(tag, "match_parent", "wrap_content", attrs, selfClosing = false)

    private fun build(
        tag: String,
        width: String,
        height: String,
        attrs: Array<out String>,
        selfClosing: Boolean,
    ): String = buildString {
        append("<").append(tag).append("\n")
        append("    android:layout_width=\"").append(width).append("\"\n")
        append("    android:layout_height=\"").append(height).append("\"")
        // A height given in the snippet wins over the default above, so an entry can say
        // `android:layout_height="?attr/actionBarSize"` and mean it.
        attrs.filterNot { it.startsWith("android:layout_height") }.forEach {
            append("\n    ").append(it)
        }
        attrs.firstOrNull { it.startsWith("android:layout_height") }?.let {
            // Replace rather than append: two layout_height attributes is a malformed element.
            val at = indexOf("android:layout_height=\"$height\"")
            if (at >= 0) replace(at, at + "android:layout_height=\"$height\"".length, it)
        }
        if (selfClosing) append(" />") else append(">\n</").append(tag).append(">")
    }
}
