package dev.jcode.ext.android.designer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LinearScale
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Password
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
import androidx.compose.material.icons.rounded.Tune
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
 * Matched on the label first: "LinearLayout (vertical)" and "LinearLayout (horizontal)" are one tag
 * and two very different pictures, and the direction is the whole reason a beginner picks one.
 */
internal fun paletteIcon(item: PaletteItem): ImageVector = BY_LABEL[item.label]
    ?: BY_TAG[item.tag]
    ?: Icons.Rounded.Widgets

/** Entries whose *label* carries the meaning, because the tag alone is ambiguous. */
private val BY_LABEL: Map<String, ImageVector> = mapOf(
    "Heading" to Icons.Rounded.Title,
    "Password field" to Icons.Rounded.Password,
    "LinearLayout (vertical)" to Icons.Rounded.ViewAgenda,
    "LinearLayout (horizontal)" to Icons.Rounded.ViewColumn,
    "ProgressBar (bar)" to Icons.Rounded.LinearScale,
    "Spacer" to Icons.Rounded.SpaceBar,
    "Divider" to Icons.Rounded.HorizontalRule,
)

/** Everything else, by the element it creates. Shared glyphs are deliberate — see the note above. */
private val BY_TAG: Map<String, ImageVector> = mapOf(
    // Text
    "TextView" to Icons.Rounded.TextFields,
    "Text" to Icons.Rounded.TextFields,
    "EditText" to Icons.Rounded.TextFields,
    "TextField" to Icons.Rounded.TextFields,
    "OutlinedTextField" to Icons.Rounded.TextFields,
    "TextInput" to Icons.Rounded.TextFields,

    // Things you press
    "Button" to Icons.Rounded.SmartButton,
    "MaterialButton" to Icons.Rounded.SmartButton,
    "ElevatedButton" to Icons.Rounded.SmartButton,
    "OutlinedButton" to Icons.Rounded.SmartButton,
    "TextButton" to Icons.Rounded.SmartButton,
    "ImageButton" to Icons.Rounded.TouchApp,
    "Pressable" to Icons.Rounded.TouchApp,
    "TouchableOpacity" to Icons.Rounded.TouchApp,
    "CheckBox" to Icons.Rounded.CheckBox,
    "RadioButton" to Icons.Rounded.RadioButtonChecked,
    "Switch" to Icons.Rounded.ToggleOn,
    "FloatingActionButton" to Icons.Rounded.AddCircle,

    // Things that arrange other things
    "Column" to Icons.Rounded.ViewAgenda,
    "Row" to Icons.Rounded.ViewColumn,
    "Box" to Icons.Rounded.Layers,
    "FrameLayout" to Icons.Rounded.Layers,
    "Stack" to Icons.Rounded.Layers,
    "Container" to Icons.Rounded.CropSquare,
    "Padding" to Icons.Rounded.CropSquare,
    "Center" to Icons.Rounded.CropSquare,
    "Expanded" to Icons.Rounded.CropSquare,
    "View" to Icons.Rounded.ViewAgenda,
    "SafeAreaView" to Icons.Rounded.ViewAgenda,
    "Surface" to Icons.Rounded.CropSquare,
    "androidx.constraintlayout.widget.ConstraintLayout" to Icons.Rounded.Dashboard,
    "androidx.coordinatorlayout.widget.CoordinatorLayout" to Icons.Rounded.Dashboard,
    "Scaffold" to Icons.Rounded.WebAsset,
    "SizedBox" to Icons.Rounded.SpaceBar,
    "Space" to Icons.Rounded.SpaceBar,

    // Things that scroll
    "ScrollView" to Icons.Rounded.SwapVert,
    "SingleChildScrollView" to Icons.Rounded.SwapVert,
    "LazyColumn" to Icons.Rounded.List,
    "ListView" to Icons.Rounded.List,
    "FlatList" to Icons.Rounded.List,
    "androidx.recyclerview.widget.RecyclerView" to Icons.Rounded.List,
    "LazyRow" to Icons.Rounded.ViewCarousel,
    "androidx.viewpager2.widget.ViewPager2" to Icons.Rounded.ViewCarousel,
    "androidx.swiperefreshlayout.widget.SwipeRefreshLayout" to Icons.Rounded.Refresh,

    // Everything else
    "ImageView" to Icons.Rounded.Image,
    "Image" to Icons.Rounded.Image,
    "ProgressBar" to Icons.Rounded.Refresh,
    "SeekBar" to Icons.Rounded.Tune,
    "Icon" to Icons.Rounded.Star,
    "Divider" to Icons.Rounded.HorizontalRule,
    "HorizontalDivider" to Icons.Rounded.HorizontalRule,
    "Card" to Icons.Rounded.CreditCard,
    "com.google.android.material.card.MaterialCardView" to Icons.Rounded.CreditCard,
    "com.google.android.material.chip.Chip" to Icons.Rounded.Label,
    "com.google.android.material.textfield.TextInputLayout" to Icons.Rounded.TextFields,
    "com.google.android.material.bottomnavigation.BottomNavigationView" to Icons.Rounded.Menu,
    "com.google.android.material.appbar.MaterialToolbar" to Icons.Rounded.WebAsset,
)
