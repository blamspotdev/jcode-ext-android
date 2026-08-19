package dev.jcode.ext.android.designer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Where the Compose canvas put each node.
 *
 * Filled in during layout by the nodes themselves rather than read back afterwards, because Compose
 * has no view tree to walk — the only moment a composable knows where it ended up is when it is told.
 */
internal class ComposeBounds : CanvasBounds {

    private val boxes = LinkedHashMap<DesignElement, Rect>()
    private val containers = LinkedHashSet<DesignElement>()

    /**
     * The canvas itself, in the same coordinates the nodes report.
     *
     * Everything is recorded raw and shifted at query time rather than at record time, because a
     * parent is positioned *after* its children: subtracting an origin that has not been set yet
     * would put every box on the first layout pass in the wrong place.
     */
    var origin: Offset = Offset.Zero

    fun record(element: DesignElement, box: Rect, acceptsChildren: Boolean) {
        boxes[element] = box
        if (acceptsChildren) containers += element else containers -= element
    }

    override fun boundsOf(element: DesignElement): Rect? =
        boxes[element]?.translate(-origin.x, -origin.y)

    override fun acceptsChildren(element: DesignElement): Boolean = element in containers
}

/**
 * The composable tree, drawn as itself.
 *
 * This is the part of Compose support that costs nothing: the plugin already runs inside JCode's own
 * Compose runtime, so a `Column` in the user's file is drawn by the real `Column`. There is no
 * approximation here and no second implementation of Compose's layout rules to drift out of date —
 * only a mapping from a name to the function of that name.
 *
 * A node this does not recognise is drawn as a labelled outline at its natural size. That is the
 * same bargain the XML side makes for Material widgets, and for the same reason: a palette and a
 * canvas that only admit what can be drawn perfectly describe a much smaller language than the one
 * the user is writing in.
 */
@Composable
internal fun ComposeNode(
    element: DesignElement,
    bounds: ComposeBounds,
    showBounds: Boolean,
    selected: DesignElement?,
) {
    val container = element.tag in CONTAINERS || element.children.isNotEmpty()
    val outline = MaterialTheme.colorScheme.primary
    val modifier = Modifier
        .composeModifier(element.value("modifier"))
        .onGloballyPositioned { coords -> bounds.record(element, coords.box(), container) }
        .then(
            when {
                element === selected -> Modifier.border(2.dp, outline)
                showBounds -> Modifier.border(1.dp, outline.copy(alpha = 0.4f))
                else -> Modifier
            },
        )

    val children: @Composable () -> Unit = {
        element.children.forEach { ComposeNode(it, bounds, showBounds, selected) }
    }

    when (element.tag) {
        "Column", "LazyColumn" -> Column(
            modifier = modifier,
            verticalArrangement = verticalArrangement(element.value("verticalArrangement")),
            horizontalAlignment = horizontalAlignment(element.value("horizontalAlignment")),
        ) { children() }

        "Row", "LazyRow" -> Row(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement(element.value("horizontalArrangement")),
            verticalAlignment = verticalAlignment(element.value("verticalAlignment")),
        ) { children() }

        "Box" -> Box(modifier = modifier, contentAlignment = boxAlignment(element.value("contentAlignment"))) {
            children()
        }

        "Surface" -> Surface(modifier = modifier) { Column { children() } }

        "Card" -> Card(modifier = modifier) {
            Column(Modifier.padding(16.dp)) { children() }
        }

        "Scaffold" -> Column(modifier = modifier.fillMaxSize()) { children() }

        "Spacer" -> Spacer(modifier = modifier)

        "Text" -> Text(
            text = literal(element.value("text")) ?: "Text",
            modifier = modifier,
            fontSize = element.value("fontSize")?.let { sp(it) } ?: MaterialTheme.typography.bodyLarge.fontSize,
            color = element.value("color")?.let { colour(it) } ?: Color.Unspecified,
            fontWeight = fontWeight(element.value("fontWeight")),
            textAlign = textAlign(element.value("textAlign")),
        )

        "Button" -> Button(onClick = {}, modifier = modifier) { children.orLabel(element, "Button") }
        "OutlinedButton" -> OutlinedButton(onClick = {}, modifier = modifier) { children.orLabel(element, "Button") }
        "TextButton" -> TextButton(onClick = {}, modifier = modifier) { children.orLabel(element, "Button") }

        "Divider", "HorizontalDivider" -> HorizontalDivider(modifier = modifier.fillMaxWidth())

        // Read-only: this is a picture of the field, and typing into a preview would be typing into
        // nothing — the value it shows belongs to the user's state, which is not running here.
        "OutlinedTextField" -> OutlinedTextField(
            value = literal(element.value("value")).orEmpty(),
            onValueChange = {},
            modifier = modifier,
            readOnly = true,
            label = element.value("label")?.let { { Text(labelText(it)) } },
        )

        "TextField" -> TextField(
            value = literal(element.value("value")).orEmpty(),
            onValueChange = {},
            modifier = modifier,
            readOnly = true,
            label = element.value("label")?.let { { Text(labelText(it)) } },
        )

        else -> Placeholder(element.tag, modifier)
    }
}

/** A button written as `Button(onClick = …) { Text("Go") }` has a child; one written bare does not. */
@Composable
private fun (@Composable () -> Unit).orLabel(element: DesignElement, fallback: String) {
    if (element.children.isEmpty()) Text(literal(element.value("text")) ?: fallback) else this()
}

@Composable
private fun Placeholder(tag: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(tag, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The colours a previewed screen is drawn against, which is the app's theme, not the IDE's. */
@Composable
internal fun DesignTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

private fun LayoutCoordinates.box(): Rect {
    val at = positionInRoot()
    return Rect(at.x, at.y, at.x + size.width, at.y + size.height)
}

// ---- reading the expressions the user wrote ----

/**
 * The `Modifier.…` chain, as much of it as is understood.
 *
 * Interpreted call by call and unknown calls skipped, so `Modifier.padding(8.dp).shimmer()` still
 * gets its padding. The alternative — refusing the whole chain over one unknown link — would mean a
 * single custom modifier anywhere in a file flattens the entire preview.
 */
private fun Modifier.composeModifier(expression: String?): Modifier {
    if (expression == null) return this
    var out = this
    CALL.findAll(expression).forEach { match ->
        val name = match.groupValues[1]
        val args = match.groupValues[2]
        out = when (name) {
            "fillMaxSize" -> out.fillMaxSize()
            "fillMaxWidth" -> out.fillMaxWidth()
            "fillMaxHeight" -> out.fillMaxHeight()
            "padding" -> out.padding(padding(args))
            "size" -> dp(args)?.let { out.size(it.dp) } ?: out
            "width" -> dp(args)?.let { out.width(it.dp) } ?: out
            "height" -> dp(args)?.let { out.height(it.dp) } ?: out
            "background" -> colour(args)?.let { out.background(it) } ?: out
            else -> out
        }
    }
    return out
}

private fun padding(args: String): PaddingValues {
    val named = ARG.findAll(args).associate { it.groupValues[1] to it.groupValues[2] }
    if (named.isEmpty()) {
        val all = dp(args) ?: 0f
        return PaddingValues(all.dp)
    }
    return PaddingValues(
        start = (dp(named["start"] ?: named["horizontal"]) ?: 0f).dp,
        top = (dp(named["top"] ?: named["vertical"]) ?: 0f).dp,
        end = (dp(named["end"] ?: named["horizontal"]) ?: 0f).dp,
        bottom = (dp(named["bottom"] ?: named["vertical"]) ?: 0f).dp,
    )
}

private fun dp(expression: String?): Float? =
    expression?.let { NUMBER.find(it)?.value?.toFloatOrNull() }

private fun sp(expression: String) = (NUMBER.find(expression)?.value?.toFloatOrNull() ?: 14f).sp

/** `"Hello"` becomes Hello; anything that is not a string literal is shown as the expression it is. */
private fun literal(expression: String?): String? {
    val e = expression?.trim() ?: return null
    if (e.length >= 2 && e.startsWith("\"") && e.endsWith("\"")) return e.substring(1, e.length - 1)
    if (e.startsWith("\"\"\"") && e.endsWith("\"\"\"") && e.length >= 6) return e.substring(3, e.length - 3)
    return e
}

/** `label = { Text("Name") }` is a lambda; the label is the string inside it. */
private fun labelText(expression: String): String =
    LABEL.find(expression)?.groupValues?.get(1) ?: literal(expression).orEmpty()

private fun colour(expression: String?): Color? {
    val e = expression?.trim() ?: return null
    HEX.find(e)?.let { return Color(it.value.removePrefix("0x").toLong(16)) }
    return NAMED_COLOURS[e.substringAfterLast('.')]
}

private fun verticalArrangement(expression: String?) = when (expression?.substringAfterLast('.')) {
    "Center" -> Arrangement.Center
    "Bottom" -> Arrangement.Bottom
    "SpaceBetween" -> Arrangement.SpaceBetween
    "SpaceAround" -> Arrangement.SpaceAround
    "SpaceEvenly" -> Arrangement.SpaceEvenly
    else -> spacedOr(expression, Arrangement.Top)
}

private fun horizontalArrangement(expression: String?) = when (expression?.substringAfterLast('.')) {
    "Center" -> Arrangement.Center
    "End" -> Arrangement.End
    "SpaceBetween" -> Arrangement.SpaceBetween
    "SpaceAround" -> Arrangement.SpaceAround
    "SpaceEvenly" -> Arrangement.SpaceEvenly
    else -> spacedOr(expression, Arrangement.Start)
}

/** `Arrangement.spacedBy(8.dp)` is common enough that dropping it visibly changes the layout. */
private fun spacedOr(expression: String?, fallback: Arrangement.Vertical): Arrangement.Vertical =
    if (expression?.contains("spacedBy") == true) Arrangement.spacedBy((dp(expression) ?: 0f).dp) else fallback

private fun spacedOr(expression: String?, fallback: Arrangement.Horizontal): Arrangement.Horizontal =
    if (expression?.contains("spacedBy") == true) Arrangement.spacedBy((dp(expression) ?: 0f).dp) else fallback

private fun horizontalAlignment(expression: String?) = when (expression?.substringAfterLast('.')) {
    "CenterHorizontally" -> Alignment.CenterHorizontally
    "End" -> Alignment.End
    else -> Alignment.Start
}

private fun verticalAlignment(expression: String?) = when (expression?.substringAfterLast('.')) {
    "CenterVertically" -> Alignment.CenterVertically
    "Bottom" -> Alignment.Bottom
    else -> Alignment.Top
}

private fun boxAlignment(expression: String?) = when (expression?.substringAfterLast('.')) {
    "Center" -> Alignment.Center
    "TopEnd" -> Alignment.TopEnd
    "BottomStart" -> Alignment.BottomStart
    "BottomEnd" -> Alignment.BottomEnd
    "CenterStart" -> Alignment.CenterStart
    "CenterEnd" -> Alignment.CenterEnd
    "TopCenter" -> Alignment.TopCenter
    "BottomCenter" -> Alignment.BottomCenter
    else -> Alignment.TopStart
}

private fun fontWeight(expression: String?) = when (expression?.substringAfterLast('.')) {
    "Bold" -> FontWeight.Bold
    "SemiBold" -> FontWeight.SemiBold
    "Medium" -> FontWeight.Medium
    "Light" -> FontWeight.Light
    else -> null
}

private fun textAlign(expression: String?) = when (expression?.substringAfterLast('.')) {
    "Center" -> TextAlign.Center
    "End", "Right" -> TextAlign.End
    "Start", "Left" -> TextAlign.Start
    else -> null
}

private val CONTAINERS = setOf(
    "Column", "Row", "Box", "Card", "Surface", "Scaffold", "LazyColumn", "LazyRow",
    "Button", "OutlinedButton", "TextButton",
)
private val LABEL = Regex("\"([^\"]*)\"")
private val CALL = Regex("""\.([A-Za-z]\w*)\(([^()]*(?:\([^()]*\)[^()]*)*)\)""")
private val ARG = Regex("""(\w+)\s*=\s*([^,)]+)""")
private val NUMBER = Regex("""-?\d+(\.\d+)?""")
private val HEX = Regex("""0x[0-9A-Fa-f]{6,8}""")
private val NAMED_COLOURS = mapOf(
    "Red" to Color.Red, "Green" to Color.Green, "Blue" to Color.Blue, "Black" to Color.Black,
    "White" to Color.White, "Gray" to Color.Gray, "LightGray" to Color.LightGray,
    "DarkGray" to Color.DarkGray, "Yellow" to Color.Yellow, "Cyan" to Color.Cyan,
    "Magenta" to Color.Magenta, "Transparent" to Color.Transparent,
)
