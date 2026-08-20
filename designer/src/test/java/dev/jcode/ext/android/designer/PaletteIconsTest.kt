package dev.jcode.ext.android.designer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.Widgets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The palette's icons, held to their invariant from both ends.
 *
 * A glyph lookup fails quietly: a missed entry still renders, just with the generic widget icon, and
 * on a row that already has a label nobody notices which of thirty rows lost its meaning. That is
 * how `MaterialButton` and `FloatingActionButton` shipped with no icon of their own — their tags are
 * fully-qualified and the map was keyed on short names.
 *
 * So it is asserted rather than looked at. Both directions matter: an entry with no glyph is the bug
 * above, and a key nothing can reach is a glyph chosen for a widget the palette does not offer,
 * which will quietly rot as the palette changes around it.
 */
class PaletteIconsTest {

    @Test
    fun everyEntryHasAGlyphOfItsOwn() {
        val generic = Palette.items
            .filter { paletteIcon(it) == Icons.Rounded.Widgets }
            .map { "${it.format}/${it.label} (tag=${it.tag})" }
        assertEquals(emptyList(), generic, "palette entries falling through to the generic icon")
    }

    @Test
    fun everyKeyIsReachable() {
        val labels = Palette.items.map { it.label }.toSet()
        assertEquals(emptySet(), BY_LABEL.keys - labels, "BY_LABEL keys matching no palette entry")

        // A label lookup wins, so a tag is only reachable through entries the label map does not
        // already answer for — `Divider` is a `View` in XML and never reaches the tag map.
        val tags = Palette.items
            .filterNot { it.label in BY_LABEL }
            .map { it.tag.substringAfterLast('.') }
            .toSet()
        assertEquals(emptySet(), BY_TAG.keys - tags, "BY_TAG keys matching no palette entry")
    }

    @Test
    fun aFullyQualifiedWidgetResolvesByItsSimpleName() {
        val fab = Palette.items.first { it.label == "FloatingActionButton" }
        assertEquals(
            "com.google.android.material.floatingactionbutton.FloatingActionButton",
            fab.tag,
            "the entry this test guards stopped being fully-qualified",
        )
        assertEquals(Icons.Rounded.AddCircle, paletteIcon(fab))
    }

    @Test
    fun textDisplayAndTextEntryLookDifferent() {
        fun icon(label: String) = paletteIcon(Palette.items.first { it.label == label })
        Palette.items.filter { it.category == Palette.TEXT }
            .groupBy { it.format }
            .forEach { (format, _) -> assertTextPairDiffers(format) }
        assertEquals(icon("TextView"), icon("Text"), "a label is a label in every language")
    }

    /**
     * One direction, one picture, in every language the palette speaks.
     *
     * Three entries mean "stacks its children downwards" — XML says `LinearLayout (vertical)`,
     * Compose and Flutter both say `Column` — and they share two glyphs with their horizontal
     * counterparts. Changing which way round those two go is a judgement call that can be revisited;
     * changing it in one language and not the others is only ever a mistake, and it is invisible
     * because no two of the three appear on screen together.
     */
    @Test
    fun oneDirectionIsOnePicture() {
        fun glyphsFor(labels: Set<String>) =
            Palette.items.filter { it.label in labels }.map { paletteIcon(it) }.distinct()

        val vertical = glyphsFor(setOf("LinearLayout (vertical)", "Column"))
        val horizontal = glyphsFor(setOf("LinearLayout (horizontal)", "Row"))
        assertEquals(1, vertical.size, "vertical containers are drawn more than one way")
        assertEquals(1, horizontal.size, "horizontal containers are drawn more than one way")

        // The glyph shows the container's own axis, so vertical is the upright bars.
        assertEquals(Icons.Rounded.ViewColumn, vertical.single(), "vertical should be the upright bars")
        assertEquals(Icons.Rounded.ViewAgenda, horizontal.single(), "horizontal should be the stacked bars")
    }

    private fun assertTextPairDiffers(format: DesignFormat) {
        val text = Palette.items.first { it.format == format && it.category == Palette.TEXT }
        val entry = Palette.items.firstOrNull {
            it.format == format && it.category == Palette.TEXT &&
                it.label in setOf("EditText", "TextField", "TextInput", "OutlinedTextField")
        } ?: return
        assert(paletteIcon(text) != paletteIcon(entry)) {
            "$format draws ${text.label} and ${entry.label} with the same glyph"
        }
    }
}
