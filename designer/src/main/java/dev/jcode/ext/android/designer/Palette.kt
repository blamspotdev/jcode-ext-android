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
 * **Every entry carries what it needs declared.** Dropping a `MaterialCardView` into a layout whose
 * root declares no `xmlns:app` produces a file that does not compile, and the designer would have
 * broken the build to add a widget. See [DesignDocument.withPrerequisites].
 */
internal data class PaletteItem(
    val label: String,
    val category: String,
    /** The source this drops into the file — XML, a Compose call, a Dart widget, JSX. */
    val xml: String,
    /** False when the renderer will draw this as a labelled placeholder rather than as itself. */
    val rendersForReal: Boolean = true,
    /**
     * What the file must already declare for this snippet to compile — an `xmlns:` prefix, an
     * import. Handed to [DesignDocument.withPrerequisites], which knows how its language spells it.
     */
    val prerequisites: List<String> = emptyList(),
    /** Extra words the search should match — how someone might look for it. */
    val keywords: String = "",
    /** The language this belongs to. A palette only ever offers the open file's own format. */
    val format: DesignFormat = DesignFormat.AndroidXml,
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
            prerequisites = listOf("app"),
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
            rendersForReal = false, prerequisites = listOf("app"), keywords = "filled tonal",
        ),
        PaletteItem(
            "MaterialCardView", MATERIAL,
            container("com.google.android.material.card.MaterialCardView", "app:cardCornerRadius=\"12dp\""),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "card surface",
        ),
        PaletteItem(
            "TextInputLayout", MATERIAL,
            container("com.google.android.material.textfield.TextInputLayout", "android:hint=\"Label\""),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "outlined field form",
        ),
        PaletteItem(
            "Chip", MATERIAL,
            wrap("com.google.android.material.chip.Chip", "android:text=\"Chip\""),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "tag pill",
        ),
        PaletteItem(
            "FloatingActionButton", MATERIAL,
            wrap("com.google.android.material.floatingactionbutton.FloatingActionButton"),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "fab action",
        ),
        PaletteItem(
            "BottomNavigationView", MATERIAL,
            match("com.google.android.material.bottomnavigation.BottomNavigationView"),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "tabs bottom bar",
        ),
        PaletteItem(
            "MaterialToolbar", MATERIAL,
            match("com.google.android.material.appbar.MaterialToolbar", "android:layout_height=\"?attr/actionBarSize\""),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "app bar title",
        ),

        // ---- AndroidX ----
        PaletteItem(
            "RecyclerView", ANDROIDX,
            container("androidx.recyclerview.widget.RecyclerView"),
            rendersForReal = false, prerequisites = listOf("app"), keywords = "list grid adapter",
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
            rendersForReal = false, prerequisites = listOf("app"), keywords = "scrolling app bar",
        ),

        // ---- Jetpack Compose ----
        //
        // Rendered by the real Compose runtime rather than approximated — see ComposeCanvas. The
        // imports come with each entry because a composable dropped into a file that cannot name it
        // is a file that does not compile.
        compose(
            "Text", TEXT, """Text("Text")""",
            imports = listOf(M3 + ".Text"),
            keywords = "label caption",
        ),
        compose(
            "Heading", TEXT,
            """Text(\n    text = "Heading",\n    fontSize = 24.sp,\n    fontWeight = FontWeight.Bold,\n)""",
            imports = listOf(M3 + ".Text", "androidx.compose.ui.text.font.FontWeight", "androidx.compose.ui.unit.sp"),
            keywords = "title h1 bold",
        ),
        compose(
            "OutlinedTextField", TEXT,
            """OutlinedTextField(\n    value = "",\n    onValueChange = { },\n    label = { Text("Label") },\n)""",
            imports = listOf(M3 + ".OutlinedTextField", M3 + ".Text"),
            keywords = "input field form",
        ),

        compose(
            "Button", BUTTONS, """Button(onClick = { }) {\n    Text("Button")\n}""",
            imports = listOf(M3 + ".Button", M3 + ".Text"),
            keywords = "action tap",
        ),
        compose(
            "OutlinedButton", BUTTONS, """OutlinedButton(onClick = { }) {\n    Text("Button")\n}""",
            imports = listOf(M3 + ".OutlinedButton", M3 + ".Text"),
            keywords = "action secondary",
        ),
        compose(
            "TextButton", BUTTONS, """TextButton(onClick = { }) {\n    Text("Button")\n}""",
            imports = listOf(M3 + ".TextButton", M3 + ".Text"),
            keywords = "action flat link",
        ),

        compose(
            "Column", LAYOUTS, """Column(modifier = Modifier.fillMaxWidth()) {\n}""",
            imports = listOf(LAYOUT + ".Column", LAYOUT + ".fillMaxWidth", "androidx.compose.ui.Modifier"),
            keywords = "vertical stack",
        ),
        compose(
            "Row", LAYOUTS, """Row(modifier = Modifier.fillMaxWidth()) {\n}""",
            imports = listOf(LAYOUT + ".Row", LAYOUT + ".fillMaxWidth", "androidx.compose.ui.Modifier"),
            keywords = "horizontal side by side",
        ),
        compose(
            "Box", LAYOUTS, """Box(modifier = Modifier.fillMaxWidth()) {\n}""",
            imports = listOf(LAYOUT + ".Box", LAYOUT + ".fillMaxWidth", "androidx.compose.ui.Modifier"),
            keywords = "stack overlay",
        ),
        compose(
            "LazyColumn", LAYOUTS, """LazyColumn(modifier = Modifier.fillMaxSize()) {\n}""",
            imports = listOf(
                "androidx.compose.foundation.lazy.LazyColumn", LAYOUT + ".fillMaxSize",
                "androidx.compose.ui.Modifier",
            ),
            keywords = "list scrolling recycler",
        ),
        compose(
            "Spacer", LAYOUTS, """Spacer(modifier = Modifier.height(16.dp))""",
            imports = listOf(
                LAYOUT + ".Spacer", LAYOUT + ".height", "androidx.compose.ui.Modifier",
                "androidx.compose.ui.unit.dp",
            ),
            keywords = "gap padding",
        ),

        compose(
            "Card", MATERIAL, """Card(modifier = Modifier.fillMaxWidth()) {\n}""",
            imports = listOf(M3 + ".Card", LAYOUT + ".fillMaxWidth", "androidx.compose.ui.Modifier"),
            keywords = "surface elevated",
        ),
        compose(
            "Surface", MATERIAL, """Surface(modifier = Modifier.fillMaxWidth()) {\n}""",
            imports = listOf(M3 + ".Surface", LAYOUT + ".fillMaxWidth", "androidx.compose.ui.Modifier"),
            keywords = "background container",
        ),
        compose(
            "HorizontalDivider", MATERIAL, """HorizontalDivider()""",
            imports = listOf(M3 + ".HorizontalDivider"),
            keywords = "rule separator line",
        ),
        compose(
            "Icon", MATERIAL,
            """Icon(\n    imageVector = Icons.Default.Star,\n    contentDescription = null,\n)""",
            imports = listOf(M3 + ".Icon", "androidx.compose.material.icons.Icons", "androidx.compose.material.icons.filled.Star"),
            rendersForReal = false,
            keywords = "symbol glyph",
        ),

        // ---- Flutter ----
        //
        // Not rendered, only approximated — there is no Dart runtime in this process. Every entry
        // is marked as such so the palette says which of its rows the canvas can vouch for.
        flutter("Text", TEXT, "Text('Text')", keywords = "label caption"),
        flutter(
            "Heading", TEXT,
            "Text(\n  'Heading',\n  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),\n)",
            keywords = "title h1 bold",
        ),
        flutter(
            "TextField", TEXT,
            "TextField(\n  decoration: InputDecoration(labelText: 'Label'),\n)",
            keywords = "input form",
        ),
        flutter(
            "ElevatedButton", BUTTONS,
            "ElevatedButton(\n  onPressed: () {},\n  child: Text('Button'),\n)",
            keywords = "action tap",
        ),
        flutter(
            "TextButton", BUTTONS,
            "TextButton(\n  onPressed: () {},\n  child: Text('Button'),\n)",
            keywords = "action flat",
        ),
        flutter("Column", LAYOUTS, "Column(\n  children: [],\n)", keywords = "vertical stack"),
        flutter("Row", LAYOUTS, "Row(\n  children: [],\n)", keywords = "horizontal"),
        flutter("Container", LAYOUTS, "Container(\n  padding: EdgeInsets.all(16),\n)", keywords = "box padding"),
        flutter("Padding", LAYOUTS, "Padding(\n  padding: EdgeInsets.all(8),\n)", keywords = "inset spacing"),
        flutter("Center", LAYOUTS, "Center()", keywords = "middle align"),
        flutter("SizedBox", LAYOUTS, "SizedBox(height: 16)", keywords = "gap spacer"),
        flutter("Expanded", LAYOUTS, "Expanded()", keywords = "fill weight flex"),
        flutter("ListView", LAYOUTS, "ListView(\n  children: [],\n)", keywords = "scrolling list"),
        flutter("Card", MATERIAL, "Card(\n  child: Padding(\n    padding: EdgeInsets.all(16),\n  ),\n)", keywords = "surface"),
        flutter("Divider", MATERIAL, "Divider()", keywords = "rule separator"),
        flutter("Icon", MATERIAL, "Icon(Icons.star)", keywords = "symbol glyph"),

        // ---- React Native ----
        //
        // Prerequisites read `Name:module`; the import is merged into an existing one rather than
        // a second line being added beside it. See JsxDocument.withPrerequisites.
        native("Text", TEXT, "<Text>Text</Text>", listOf("Text"), keywords = "label caption"),
        native(
            "Heading", TEXT, "<Text style={{ fontSize: 24, fontWeight: 'bold' }}>Heading</Text>",
            listOf("Text"), keywords = "title h1 bold",
        ),
        native(
            "TextInput", TEXT, "<TextInput placeholder=\"Enter text\" />",
            listOf("TextInput"), keywords = "input form field",
        ),
        native(
            "Button", BUTTONS, "<Button title=\"Button\" onPress={() => {}} />",
            listOf("Button"), keywords = "action tap",
        ),
        native(
            "Pressable", BUTTONS, "<Pressable onPress={() => {}}>\n  <Text>Press me</Text>\n</Pressable>",
            listOf("Pressable", "Text"), keywords = "touchable tap",
        ),
        native("View", LAYOUTS, "<View />", listOf("View"), keywords = "container box"),
        native(
            "ScrollView", LAYOUTS, "<ScrollView />", listOf("ScrollView"),
            keywords = "scrolling overflow",
        ),
        native(
            "SafeAreaView", LAYOUTS, "<SafeAreaView />", listOf("SafeAreaView"),
            keywords = "insets notch",
        ),
        native(
            "FlatList", LAYOUTS,
            "<FlatList\n  data={[]}\n  renderItem={({ item }) => <Text>{item}</Text>}\n/>",
            listOf("FlatList", "Text"), keywords = "list rows",
        ),
        native(
            "Image", WIDGETS, "<Image source={{ uri: '' }} style={{ width: 48, height: 48 }} />",
            listOf("Image"), keywords = "picture photo",
        ),
    )

    /** Every category that has an entry in [format], in display order. */
    fun categories(format: DesignFormat): List<String> =
        categories.filter { c -> items.any { it.format == format && it.category == c } }

    /** Items in [format] matching [query]; everything in that format when the query is blank. */
    fun search(query: String, format: DesignFormat): List<PaletteItem> {
        val inFormat = items.filter { it.format == format }
        val q = query.trim().lowercase()
        if (q.isEmpty()) return inFormat
        return inFormat.filter {
            it.label.lowercase().contains(q) ||
                it.category.lowercase().contains(q) ||
                it.keywords.contains(q)
        }
    }

    private fun compose(
        label: String,
        category: String,
        code: String,
        imports: List<String>,
        rendersForReal: Boolean = true,
        keywords: String = "",
    ) = PaletteItem(
        label = label,
        category = category,
        xml = code,
        rendersForReal = rendersForReal,
        prerequisites = imports,
        keywords = keywords,
        format = DesignFormat.Compose,
    )

    private fun flutter(
        label: String,
        category: String,
        code: String,
        keywords: String = "",
    ) = PaletteItem(
        label = label,
        category = category,
        xml = code,
        rendersForReal = false,
        prerequisites = listOf("package:flutter/material.dart"),
        keywords = keywords,
        format = DesignFormat.Flutter,
    )

    private fun native(
        label: String,
        category: String,
        code: String,
        imports: List<String>,
        keywords: String = "",
    ) = PaletteItem(
        label = label,
        category = category,
        xml = code,
        rendersForReal = false,
        prerequisites = imports.map { "$it:react-native" },
        keywords = keywords,
        format = DesignFormat.ReactNative,
    )

    private const val M3 = "androidx.compose.material3"
    private const val LAYOUT = "androidx.compose.foundation.layout"

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
