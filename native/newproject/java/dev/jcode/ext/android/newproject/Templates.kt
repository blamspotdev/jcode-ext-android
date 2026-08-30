package dev.jcode.ext.android.newproject

/**
 * What a template asks for before it can be scaffolded.
 *
 * The same three questions for every Android template, so they live here rather than being declared
 * per entry: a name, the application id, and how far back it runs. Compile SDK is deliberately not
 * among them — it is whatever this device's `aapt2` can actually read, which the SDK install works
 * out and records, and a higher one scaffolds perfectly and then fails every build inside resource
 * linking.
 */
internal data class Config(
    val name: String = "",
    val applicationId: String = "com.example.app",
    val minSdk: String = "24",
) {
    /**
     * A directory name, and the Gradle project name.
     *
     * JCode's own rule, character for character (`WorkspaceManager.sanitizedFolderName`): it lower-
     * cases what it registers, so a folder scaffolded as `MyApp` is opened as `myapp` and the wizard
     * appears to have renamed the project on the way in.
     */
    val folder: String get() = name.trim().lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')

    val isValid: Boolean
        get() = folder.isNotEmpty() && applicationId.matches(APPLICATION_ID)

    companion object {
        /** Two or more dot-separated segments, each starting with a letter — what AGP will accept. */
        val APPLICATION_ID = Regex("""[a-zA-Z][A-Za-z0-9_]*(\.[a-zA-Z][A-Za-z0-9_]*)+""")
        val MIN_SDKS = listOf("21", "23", "24", "26", "28", "29", "30", "31", "33", "34")
    }
}

/** The rail down the left of the gallery. One per form factor, as Android Studio groups them. */
internal enum class Category(val label: String) {
    PhoneAndTablet("Phone and Tablet"),
    WearOs("Wear OS"),
    Television("Television"),
    ;
}

/**
 * A gallery entry.
 *
 * [art] names the drawing rather than a file: this module ships no bitmaps, so each preview is a
 * vector built in code — a phone frame with the shape of the screen that template produces. An image
 * per entry would be the obvious thing and is the wrong one here, because it would be a resource
 * table and a set of PNGs to keep in step with what the recipes actually scaffold.
 *
 * [script] is the scaffold, relative to the pack's `templates/` directory, and is what makes an
 * entry real: an entry with no script is one this pack cannot build yet, and the gallery says so
 * rather than offering it and failing at the end.
 */
internal data class Template(
    val id: String,
    val category: Category,
    val name: String,
    val description: String,
    val art: Art,
    val script: String? = null,
)

/** The shape a preview draws. */
internal enum class Art { Empty, ComposeActivity, ViewsActivity, Navigation, BottomNavigation, Wear, Tv }

internal object Templates {

    val all: List<Template> = listOf(
        Template(
            id = "empty-compose",
            category = Category.PhoneAndTablet,
            name = "Empty Activity",
            description = "A Compose app with a single activity, Navigation, a ViewModel and a test — " +
                "scaffolded from the Android SDK's own project template.",
            art = Art.ComposeActivity,
            script = "android-app/scaffold-from-sdk-template.sh",
        ),
        Template(
            id = "empty-views",
            category = Category.PhoneAndTablet,
            name = "Empty Views Activity",
            description = "One activity with an XML layout, for a project that is not using Compose.",
            art = Art.ViewsActivity,
        ),
        Template(
            id = "navigation",
            category = Category.PhoneAndTablet,
            name = "Navigation UI",
            description = "A drawer, a toolbar and three destinations wired to a navigation graph.",
            art = Art.Navigation,
        ),
        Template(
            id = "bottom-navigation",
            category = Category.PhoneAndTablet,
            name = "Bottom Navigation",
            description = "Three top-level destinations behind a bottom navigation bar.",
            art = Art.BottomNavigation,
        ),
        Template(
            id = "no-activity",
            category = Category.PhoneAndTablet,
            name = "No Activity",
            description = "A Gradle project with an application module and nothing in it.",
            art = Art.Empty,
        ),
        Template(
            id = "wear-empty",
            category = Category.WearOs,
            name = "Empty Wear App",
            description = "A watch-shaped Compose app.",
            art = Art.Wear,
        ),
        Template(
            id = "tv-empty",
            category = Category.Television,
            name = "Empty TV Activity",
            description = "A leanback-shaped app for a television.",
            art = Art.Tv,
        ),
    )

    fun inCategory(category: Category): List<Template> = all.filter { it.category == category }

    /** Categories with at least one entry this pack can actually scaffold, first. */
    val categories: List<Category> = Category.entries
}
