package dev.jcode.ext.android.designer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.GridOff
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The designer's toolbar.
 *
 * Icons rather than words, and only here. These are a fixed set of modes hit repeatedly, so a glyph
 * is faster to find than a label and costs a third of the width — which is the whole point on a
 * screen that also has to hold a canvas and two panels. The palette stays text for the opposite
 * reason: it is an open-ended list of widget *names*, and a wall of near-identical box glyphs would
 * cost recognition rather than gain it.
 */
@Composable
internal fun DesignerToolbar(
    device: DeviceSize,
    onDevice: (DeviceSize) -> Unit,
    dark: Boolean,
    onDark: (Boolean) -> Unit,
    bounds: Boolean,
    onBounds: (Boolean) -> Unit,
    zoom: Float?,
    onZoom: (Float?) -> Unit,
    /** True when the canvas is a likeness rather than a rendering, and must say so. */
    approximate: Boolean,
    status: String,
    canUndo: Boolean,
    onUndo: () -> Unit,
    canRedo: Boolean,
    onRedo: () -> Unit,
) {
    var deviceMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            ToolIcon(Icons.Rounded.PhoneAndroid, "Device: ${device.label}") { deviceMenu = true }
            DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                DEVICES.forEach { d ->
                    DropdownMenuItem(
                        text = { Text("${d.label} — ${d.widthDp}×${d.heightDp}dp") },
                        onClick = { onDevice(d); deviceMenu = false },
                    )
                }
            }
        }
        ToolIcon(
            if (dark) Icons.Rounded.DarkMode else Icons.Rounded.LightMode,
            if (dark) "Dark surface" else "Light surface",
        ) { onDark(!dark) }
        ToolIcon(
            if (bounds) Icons.Rounded.GridOn else Icons.Rounded.GridOff,
            if (bounds) "Hide bounds" else "Show bounds",
        ) { onBounds(!bounds) }

        ToolIcon(Icons.Rounded.Remove, "Zoom out") { onZoom(((zoom ?: 1f) - 0.15f).coerceAtLeast(0.1f)) }
        Text(
            text = zoom?.let { "${(it * 100).toInt()}%" } ?: "Fit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable { onZoom(null) }.padding(horizontal = 2.dp),
        )
        ToolIcon(Icons.Rounded.Add, "Zoom in") { onZoom(((zoom ?: 1f) + 0.15f).coerceAtMost(3f)) }
        ToolIcon(Icons.Rounded.FitScreen, "Fit to pane") { onZoom(null) }

        if (approximate) {
            Text(
                text = "Approximate",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )

        // At the end, past the status, because these act on the file rather than on the view: every
        // other control in this bar changes how the layout is *shown*, and these two change it.
        ToolIcon(Icons.Rounded.Undo, "Undo", enabled = canUndo, onClick = onUndo)
        ToolIcon(Icons.Rounded.Redo, "Redo", enabled = canRedo, onClick = onRedo)
    }
}

@Composable
private fun ToolIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
                .copy(alpha = if (enabled) 1f else 0.35f),
        )
    }
}

/**
 * One panel tab, drawn the way JCode's right drawer draws its own.
 *
 * Flat and butted against its neighbours rather than a Material `TabRow` with an underline: this
 * panel sits in the same window as that drawer, often a few hundred pixels from it, and two ways of
 * spelling "pick one of these" that close together read as two different kinds of control.
 */
@Composable
internal fun PanelTabItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
            .fillMaxHeight()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * The element tree.
 *
 * A layout is a tree and a canvas only shows its leaves — an empty container, a `gone` view or a
 * child hidden behind a sibling cannot be tapped, and until now could not be selected at all. This
 * is the only way to reach them.
 */
@Composable
internal fun LayerTree(
    root: DesignElement,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    onDragStart: (DragPayload, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    surface: TreeDropSurface,
    hover: DropTarget?,
    modifier: Modifier = Modifier,
) {
    val outline = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier.onGloballyPositioned {
            // Bounds are clipped — the right answer for "is the finger over the tree". The origin is
            // not, because it is what row boxes are measured back from and they are not clipped.
            surface.bounds = it.boundsInRoot()
            surface.origin = it.positionInRoot()
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(6.dp)) {
            Text(
                "Layers",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
            TreeRows(root, root, 0, selectedPath, onSelect, onDragStart, onDragMove, onDragEnd, surface)
        }
        // The tree draws its own target: a line between two rows for a move beside them, the row
        // itself filled for a move into them.
        if (hover != null && hover.surface == DropSurface.Tree) {
            Canvas(Modifier.matchParentSize()) {
                val line = hover.line
                if (line != null) {
                    drawRect(outline, line.topLeft, line.size)
                } else {
                    drawRect(outline.copy(alpha = 0.18f), hover.container.topLeft, hover.container.size)
                    drawRect(outline, hover.container.topLeft, hover.container.size, style = Stroke(2f))
                }
            }
        }
    }
}

@Composable
private fun TreeRows(
    root: DesignElement,
    element: DesignElement,
    depth: Int,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    onDragStart: (DragPayload, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    surface: TreeDropSurface,
) {
    val path = elementPath(root, element) ?: return
    val selected = path == selectedPath
    val id = element.value("id")?.substringAfterLast('/')

    DisposableEffect(path) { onDispose { surface.forget(path) } }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                // Unclipped, so a row half-scrolled out of view still reports its real height and
                // its thirds still divide where the user can see them dividing.
                val at = coords.positionInRoot()
                surface.record(
                    element,
                    path,
                    Rect(at.x, at.y, at.x + coords.size.width, at.y + coords.size.height),
                )
            }
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(4.dp),
            )
            .clickable { onSelect(path) }
            // The root cannot be moved: there is nowhere above it to move it to.
            .then(
                if (path.isEmpty()) Modifier
                else Modifier.dragSource(
                    payload = DragPayload.Move(path, element.tag.substringAfterLast('.')),
                    onStart = onDragStart,
                    onMove = onDragMove,
                    onEnd = onDragEnd,
                ),
            )
            .padding(start = (depth * 12 + 4).dp, top = 3.dp, bottom = 3.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = element.tag.substringAfterLast('.'),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (id != null) {
            Text(
                text = id,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    element.children.forEach {
        TreeRows(root, it, depth + 1, selectedPath, onSelect, onDragStart, onDragMove, onDragEnd, surface)
    }
}

/**
 * The palette, by category and searchable.
 *
 * Rows say when a widget will draw as an outline rather than as itself. Offering only what can be
 * drawn would leave a palette that is a poor description of Android — cards and FABs are reached for
 * constantly — and silently dropping an outline onto the canvas would be worse than saying so.
 */
@Composable
internal fun PalettePanel(
    format: DesignFormat,
    onInsert: (PaletteItem) -> Unit,
    onDragStart: (DragPayload, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether this panel scrolls itself.
     *
     * False when it sits inside a scrolling inspector: two nested vertical scrollers give the inner
     * one an unbounded height, which Compose refuses outright rather than laying out — it is a crash,
     * not a glitch.
     */
    scrollable: Boolean = false,
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, format) { Palette.search(query, format) }

    Column(
        modifier = modifier
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(8.dp),
    ) {
        InspectorSearchField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search widgets",
        )

        Palette.categories(format).forEach { category ->
            val inCategory = results.filter { it.category == category }
            if (inCategory.isEmpty()) return@forEach
            Text(
                text = category.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
            )
            inCategory.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        // Tap to drop it into the current container, long-press to drag it exactly
                        // where you want it. Both, because on a phone a precise drag across two
                        // panels is work and most of the time "inside the selected widget" is right.
                        .clickable { onInsert(item) }
                        .dragSource(
                            payload = DragPayload.New(item),
                            onStart = onDragStart,
                            onMove = onDragMove,
                            onEnd = onDragEnd,
                        )
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!item.rendersForReal) {
                        Text(
                            text = "outline",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the canvas puts around the layout: status bar, app bar, navigation bar.
 *
 * These are not decoration. Each one takes real height away from the layout, and a screen designed
 * against the full 891dp that then loses 24dp of status bar and 48dp of navigation is a screen whose
 * bottom-aligned content is off-screen on a real device. Toggling them here measures the layout
 * against what it will actually get.
 */
internal data class ScreenChrome(
    val statusBar: Boolean = true,
    val appBar: Boolean = false,
    val navBar: Boolean = true,
) {
    /** dp the layout loses to the bars above and below it. */
    val topInsetDp: Int get() = (if (statusBar) 24 else 0) + (if (appBar) 56 else 0)
    val bottomInsetDp: Int get() = if (navBar) 48 else 0
}

@Composable
internal fun ScreenChromePanel(
    chrome: ScreenChrome,
    onChrome: (ScreenChrome) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            "Screen",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        InspectorToggle("Status bar", chrome.statusBar, { onChrome(chrome.copy(statusBar = it)) })
        InspectorToggle("App bar", chrome.appBar, { onChrome(chrome.copy(appBar = it)) })
        InspectorToggle("Navigation bar", chrome.navBar, { onChrome(chrome.copy(navBar = it)) })
        Text(
            text = if (chrome.topInsetDp == 0 && chrome.bottomInsetDp == 0) {
                "Full screen — the layout gets the whole display."
            } else {
                "Layout gets ${chrome.topInsetDp}dp less at the top, ${chrome.bottomInsetDp}dp at the bottom."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}


