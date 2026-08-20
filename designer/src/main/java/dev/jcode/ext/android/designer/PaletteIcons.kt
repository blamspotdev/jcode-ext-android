package dev.jcode.ext.android.designer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dock
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Padding
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SmartButton
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * A glyph for each palette entry.
 *
 * The toolbar's own note argues that icons beat words for a fixed set of modes and that an
 * open-ended list of *names* is better off as text. That reasoning quietly assumed the reader
 * already knows what a `LazyColumn` is. For someone who does not — which is most of the point of
 * having a palette rather than expecting people to type the tag — the glyph is the only thing on
 * the row carrying any meaning at all, and the name is the part that means nothing yet.
 *
 * The risk that argument identified is real and is the constraint here: thirty variations of a
 * rectangle would be worse than no icons, because they would look sortable and not be. So these are
 * chosen for what the widget *does* — a list scrolls, a spacer is a gap, a switch toggles — and
 * where two widgets do the same thing to their children they are allowed to share a glyph rather
 * than being told apart by a detail nobody can see at 18dp.
 *
 * That licence has a boundary, and it is the one this file got wrong first time round: it covers
 * widgets that do the *same* job, not widgets that merely sit near each other in a list. A
 * `TextView` displays text and an `EditText` accepts it — the single distinction in the Text
 * category a beginner most needs — so they do not share a picture. Nor does a spinner share one
 * with pull-to-refresh, nor a progress bar with a slider.
 *
 * Two lookups, in order:
 *
 * **By label**, for entries where the tag alone is ambiguous: "LinearLayout (vertical)" and
 * "LinearLayout (horizontal)" are one tag and two very different pictures, and the direction is the
 * whole reason a beginner picks one.
 *
 * A directional glyph shows the container's **own axis**, not the arrangement of its children —
 * vertical gets the upright bars, horizontal gets the stacked ones. Depicting the children instead
 * is defensible and was tried first: a vertical layout does stack its children as rows. But it puts
 * a horizontal picture next to the word "vertical", and nobody reads past that to the reasoning.
 * The same rule governs `Column` and `Row`, which share these two glyphs, and it lines the names up
 * as a side effect — `Column` is drawn by the icon called `ViewColumn`. `PaletteIconsTest` pins the
 * pairing, since half a swap is worse than either convention.
 *
 * **By simple name**, so `com.google.android.material.button.MaterialButton` finds `MaterialButton`.
 * The map used to be keyed on whatever string the entry happened to produce, so a widget declared
 * by its fully-qualified name fell through to the generic glyph and said nothing at all —
 * `MaterialButton` and `FloatingActionButton` both did. Keying on the last segment cannot express
 * that mistake. `PaletteIconsTest` holds the invariant from both ends: every entry resolves to
 * something, and every key is reachable.
 */
internal fun paletteIcon(item: PaletteItem): ImageVector = BY_LABEL[item.label]
    ?: BY_TAG[item.tag.substringAfterLast('.')]
    ?: Icons.Rounded.Widgets

/** Entries whose *label* carries the meaning, because the tag alone is ambiguous. */
internal val BY_LABEL: Map<String, ImageVector> = mapOf(
    "Heading" to Icons.Rounded.Title,
    "Password field" to Icons.Rounded.Password,
    "LinearLayout (vertical)" to Icons.Rounded.ViewColumn,
    "LinearLayout (horizontal)" to Icons.Rounded.ViewAgenda,
    // A determinate bar reports how far along it is; the indeterminate one cannot.
    "ProgressBar (bar)" to Icons.Rounded.Percent,
    "Spacer" to Icons.Rounded.SpaceBar,
    "Divider" to Icons.Rounded.HorizontalRule,
)

/**
 * Everything else, by the simple name of the element it creates.
 *
 * Shared glyphs are deliberate where the widgets share a job — every kind of button is a button.
 */
internal val BY_TAG: Map<String, ImageVector> = mapOf(
    // Text you read.
    "TextView" to Icons.Rounded.TextFields,
    "Text" to Icons.Rounded.TextFields,

    // Text you write. Separate from the above on purpose: a pencil says you may type here.
    "EditText" to Icons.Rounded.Edit,
    "TextField" to Icons.Rounded.Edit,
    "OutlinedTextField" to Icons.Rounded.Edit,
    "TextInput" to Icons.Rounded.Edit,
    "TextInputLayout" to Icons.Rounded.Edit,

    // Things you press.
    "Button" to Icons.Rounded.SmartButton,
    "MaterialButton" to Icons.Rounded.SmartButton,
    "ElevatedButton" to Icons.Rounded.SmartButton,
    "OutlinedButton" to Icons.Rounded.SmartButton,
    "TextButton" to Icons.Rounded.SmartButton,
    "ImageButton" to Icons.Rounded.TouchApp,
    "Pressable" to Icons.Rounded.TouchApp,
    "CheckBox" to Icons.Rounded.CheckBox,
    "RadioButton" to Icons.Rounded.RadioButtonChecked,
    "Switch" to Icons.Rounded.ToggleOn,
    "FloatingActionButton" to Icons.Rounded.AddCircle,

    // Things that arrange other things. See the note above on which way round the two go.
    "Column" to Icons.Rounded.ViewColumn,
    "Row" to Icons.Rounded.ViewAgenda,
    "Box" to Icons.Rounded.Layers,
    "FrameLayout" to Icons.Rounded.Layers,
    "Container" to Icons.Rounded.CropSquare,
    "View" to Icons.Rounded.CropSquare,
    "Surface" to Icons.Rounded.CropSquare,
    "Padding" to Icons.Rounded.Padding,
    "Center" to Icons.Rounded.CenterFocusStrong,
    "Expanded" to Icons.Rounded.OpenInFull,
    "SafeAreaView" to Icons.Rounded.CropFree,
    "ConstraintLayout" to Icons.Rounded.Dashboard,
    "CoordinatorLayout" to Icons.Rounded.Dashboard,
    "SizedBox" to Icons.Rounded.SpaceBar,

    // Things that scroll.
    "ScrollView" to Icons.Rounded.SwapVert,
    "LazyColumn" to Icons.Rounded.List,
    "ListView" to Icons.Rounded.List,
    "FlatList" to Icons.Rounded.List,
    "RecyclerView" to Icons.Rounded.List,
    "ViewPager2" to Icons.Rounded.ViewCarousel,
    "SwipeRefreshLayout" to Icons.Rounded.Refresh,

    // Everything else.
    "ImageView" to Icons.Rounded.Image,
    "Image" to Icons.Rounded.Image,
    // Waiting, not reloading — Refresh belongs to the widget above that really does refresh.
    "ProgressBar" to Icons.Rounded.HourglassEmpty,
    "SeekBar" to Icons.Rounded.LinearScale,
    "Icon" to Icons.Rounded.Star,
    "HorizontalDivider" to Icons.Rounded.HorizontalRule,
    "Card" to Icons.Rounded.CreditCard,
    "MaterialCardView" to Icons.Rounded.CreditCard,
    "Chip" to Icons.Rounded.Label,
    "BottomNavigationView" to Icons.Rounded.Dock,
    "MaterialToolbar" to Icons.Rounded.WebAsset,
)
