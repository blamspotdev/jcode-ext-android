package dev.jcode.ext.android.designer

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** A device the canvas can be sized to. Widths are the common Android breakpoints. */
internal data class DeviceSize(val label: String, val widthDp: Int, val heightDp: Int)

internal val DEVICES = listOf(
    DeviceSize("Phone", 411, 891),
    DeviceSize("Phone (small)", 360, 640),
    DeviceSize("Foldable", 673, 841),
    DeviceSize("Tablet", 800, 1280),
)

/** Which side panel the narrow layout is showing. */
private enum class PanelTab(val label: String) { Layers("Layers"), Palette("Palette"), Properties("Properties") }

@Composable
internal fun DesignerScreen(
    source: String,
    file: File,
    projectDir: File?,
    onSource: (String) -> Unit,
) {
    val format = remember(file, source) { DesignFormat.of(file, source) }
    val document = remember(source, format) {
        when (format) {
            DesignFormat.Compose -> ComposeDocument.parse(source)
            DesignFormat.Flutter -> DartDocument.parse(source)
            DesignFormat.ReactNative -> JsxDocument.parse(source)
            else -> LayoutDocument.parse(source)
        }
    }
    val resources = remember(projectDir, source) { ResourceTable.read(projectDir) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf(DEVICES.first()) }
    var dark by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(true) }
    // null means "fit the pane" — the state a designer should open in.
    var zoom by remember { mutableStateOf<Float?>(null) }
    var chrome by remember { mutableStateOf(ScreenChrome()) }
    var tab by remember { mutableStateOf(PanelTab.Palette) }
    var drag by remember { mutableStateOf<DragState?>(null) }
    var hover by remember { mutableStateOf<DropTarget?>(null) }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val root = document.root
    if (root == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                when (format) {
                    DesignFormat.Compose -> "No composable UI in this file yet."
                    DesignFormat.Flutter -> "No widget tree in this file yet."
                    DesignFormat.ReactNative -> "No markup returned from this file yet."
                    null -> "The designer has nothing to show for this kind of file."
                    else -> "This file has no layout element yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val flat = remember(document) { root.flatten() }
    val selected = flat.firstOrNull { elementPath(root, it) == selectedPath }

    // Inserting goes through the palette item so whatever it needs declared comes with it — see
    // DesignDocument.withPrerequisites for why a Material widget can otherwise break the build.
    fun insert(item: PaletteItem, into: DesignElement, at: Int = -1) {
        val ready = document.reparse(document.withPrerequisites(item))
        val target = ready.root?.let { elementAt(it, elementPath(root, into).orEmpty()) } ?: return
        onSource(if (at < 0) ready.withChild(target, item.xml) else ready.withChildAt(target, at, item.xml))
        tab = PanelTab.Properties
    }

    /** Lift an element out of the file and put it back down somewhere else, in one edit. */
    fun move(from: String, to: DropTarget) {
        val moving = elementAt(root, from) ?: return
        val xml = dedent(
            document.text.substring(moving.range.first, (moving.range.last + 1).coerceAtMost(document.text.length)),
            moving.indent,
        )
        val removed = document.reparse(document.without(moving))
        val containerPath = pathAfterRemoval(to.containerPath, from) ?: return
        val container = removed.root?.let { elementAt(it, containerPath) } ?: return
        onSource(removed.withChildAt(container, to.index, xml))
        selectedPath = null
    }

    fun applyDrop(target: DropTarget?) {
        val payload = drag?.payload
        drag = null
        hover = null
        if (target == null || payload == null) return
        val container = elementAt(root, target.containerPath) ?: return
        when (payload) {
            is DragPayload.New -> insert(payload.item, container, target.index)
            is DragPayload.Move -> move(payload.path, target)
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootCoords = it }) {
    Column(Modifier.fillMaxSize()) {
        DesignerToolbar(
            device = device,
            onDevice = { device = it },
            dark = dark,
            onDark = { dark = it },
            bounds = bounds,
            onBounds = { bounds = it },
            zoom = zoom,
            onZoom = { zoom = it },
            // While a drag is in flight the status line says where it would land. A drop that
            // quietly does nothing is the worst outcome here, and this is what tells the user in
            // advance that they are not over a container.
            // Named, not implied. A picture the designer cannot vouch for has to say so, or the
            // user reasonably takes it for what the framework would actually draw.
            approximate = !document.format.rendersNatively,
            status = when {
                drag != null -> hover?.let { target ->
                    val into = elementAt(root, target.containerPath)?.tag?.substringAfterLast('.')
                    "into $into at ${target.index}"
                } ?: "no drop target"
                selected != null -> "${selected.tag.substringAfterLast('.')} selected"
                else -> "${flat.size} widgets"
            },
        )
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        val canvas: @Composable (Modifier) -> Unit = { m ->
            Box(
                modifier = m.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            ) {
                DesignCanvas(
                    root = root,
                    format = document.format,
                    resources = resources,
                    device = device,
                    dark = dark,
                    showBounds = bounds,
                    zoom = zoom,
                    chrome = chrome,
                    selectedPath = selectedPath,
                    onSelect = { selectedPath = it },
                    drag = drag,
                    hover = hover,
                    onHover = { hover = it },
                    onPickUp = { path, label, at ->
                        drag = DragState(DragPayload.Move(path, label), at)
                    },
                    onDragTo = { at -> drag = drag?.copy(position = at) },
                    onDrop = { dropped -> applyDrop(if (dropped) hover else null) },
                )
            }
        }

        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Wide enough for three columns, or not. The threshold is where a 220dp tree, a 280dp
            // inspector and a canvas worth looking at stop fitting side by side; below it the panels
            // become tabs under the canvas rather than being squeezed into slivers.
            val sideBySide = maxWidth >= 720.dp

            if (sideBySide) {
                Row(Modifier.fillMaxSize()) {
                    LayerTree(
                        root = root,
                        selectedPath = selectedPath,
                        onSelect = { selectedPath = it },
                        onDragStart = { payload, at -> drag = DragState(payload, at) },
                        onDragMove = { at -> drag = drag?.copy(position = at) },
                        onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                        modifier = Modifier.width(200.dp).fillMaxHeight(),
                    )
                    VerticalRule()
                    canvas(Modifier.weight(1f).fillMaxHeight())
                    VerticalRule()
                    Column(
                        Modifier.width(290.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ScreenChromePanel(chrome, { chrome = it })
                        Divider()
                        if (selected == null) {
                            PalettePanel(
                                format = document.format,
                                onInsert = { insert(it, root) },
                        onDragStart = { payload, at -> drag = DragState(payload, at) },
                        onDragMove = { at -> drag = drag?.copy(position = at) },
                        onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            PropertiesPanel(
                                document = document,
                                element = selected,
                                onSource = onSource,
                                onDelete = { selectedPath = null; onSource(document.without(selected)) },
                                onInsertChild = { insert(it, selected) },
                                onDragStart = { payload, at -> drag = DragState(payload, at) },
                                onDragMove = { at -> drag = drag?.copy(position = at) },
                                onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                            )
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    canvas(Modifier.fillMaxWidth().weight(1f))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    TabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.fillMaxWidth()) {
                        PanelTab.entries.forEach { t ->
                            Tab(
                                selected = tab == t,
                                onClick = { tab = t },
                                text = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(230.dp)) {
                        when (tab) {
                            PanelTab.Layers -> LayerTree(
                                root = root,
                                selectedPath = selectedPath,
                                onSelect = { selectedPath = it },
                                onDragStart = { payload, at -> drag = DragState(payload, at) },
                                onDragMove = { at -> drag = drag?.copy(position = at) },
                                onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            PanelTab.Palette -> PalettePanel(
                                format = document.format,
                                onInsert = { insert(it, selected ?: root) },
                                onDragStart = { payload, at -> drag = DragState(payload, at) },
                                onDragMove = { at -> drag = drag?.copy(position = at) },
                                onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                                modifier = Modifier.fillMaxSize(),
                                scrollable = true,
                            )
                            PanelTab.Properties -> Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                ScreenChromePanel(chrome, { chrome = it })
                                Divider()
                                if (selected == null) {
                                    Text(
                                        "Tap a widget on the canvas, or pick one in Layers.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    PropertiesPanel(
                                        document = document,
                                        element = selected,
                                        onSource = onSource,
                                        onDelete = { selectedPath = null; onSource(document.without(selected)) },
                                        onInsertChild = { insert(it, selected) },
                                        onDragStart = { payload, at -> drag = DragState(payload, at) },
                                        onDragMove = { at -> drag = drag?.copy(position = at) },
                                        onDragEnd = { dropped -> applyDrop(if (dropped) hover else null) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // The chip follows the finger across every panel, which is the only feedback that says a
        // long press turned into a drag rather than into nothing.
        drag?.let { active ->
            val origin = rootCoords?.positionInRoot() ?: Offset.Zero
            val local = active.position - origin
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 6.dp,
                modifier = Modifier.offset { IntOffset(local.x.toInt() + 24, local.y.toInt() - 56) },
            ) {
                Text(
                    text = active.payload.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun VerticalRule() {
    Divider(
        modifier = Modifier.fillMaxHeight().width(1.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/**
 * The layout, inflated for real at the canvas's own scale, inside the screen's chrome.
 *
 * Hosted in an [AndroidView] because the thing being previewed *is* a View hierarchy — asking
 * Compose to approximate one would reintroduce exactly the guesswork this avoids.
 */
@Composable
private fun DesignCanvas(
    root: DesignElement,
    format: DesignFormat,
    resources: ResourceTable,
    device: DeviceSize,
    dark: Boolean,
    showBounds: Boolean,
    /** null = fit the pane; otherwise the user's zoom factor. */
    zoom: Float?,
    chrome: ScreenChrome,
    selectedPath: String?,
    onSelect: (String?) -> Unit,
    drag: DragState?,
    hover: DropTarget?,
    onHover: (DropTarget?) -> Unit,
    onPickUp: (path: String, label: String, at: Offset) -> Unit,
    onDragTo: (Offset) -> Unit,
    onDrop: (dropped: Boolean) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.primary
    val screenDensity = LocalDensity.current.density
    // Hoisted out of the AndroidView because the gestures need the views it built — see RendererRef
    // for why it is a field rather than state.
    val ref = remember { RendererRef() }
    var canvasCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun toCanvas(inRoot: Offset): Offset =
        canvasCoords?.takeIf { it.isAttached }?.let { inRoot - it.positionInRoot() } ?: inRoot

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(10.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        val fit = minOf(maxWidth / device.widthDp.dp, maxHeight / device.heightDp.dp, 1f)
        val effective = (zoom ?: fit).coerceIn(0.1f, 3f)
        val barColour = if (dark) Color(0xFF1E1E1E) else Color(0xFFE8E8E8)

        Column(
            modifier = Modifier
                .size(width = device.widthDp.dp * effective, height = device.heightDp.dp * effective)
                .background(if (dark) Color(0xFF121212) else Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            if (chrome.statusBar) SystemBar(24.dp * effective, barColour, "")
            if (chrome.appBar) SystemBar(56.dp * effective, MaterialTheme.colorScheme.primary, "App bar")

            Box(
                modifier = Modifier.fillMaxWidth().weight(1f)
                    .onGloballyPositioned { canvasCoords = it },
            ) {
            if (format != DesignFormat.AndroidXml) {
                // Compose is drawn by the real Compose runtime the plugin is already living in —
                // see ComposeCanvas for why that is the whole trick. Flutter and React Native are
                // drawn by the same code through a name mapping, which is an approximation and is
                // labelled as one in the toolbar; there is no Dart or JS runtime here to do better.
                val bounds = remember(root) { ComposeBounds().also { ref.renderer = it } }
                val selectedElement = selectedPath
                    ?.let { path -> root.flatten().firstOrNull { elementPath(root, it) == path } }
                CompositionLocalProvider(LocalDensity provides Density(screenDensity * effective)) {
                    DesignTheme(dark) {
                        Surface(
                            modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
                                val at = coords.positionInRoot()
                                bounds.origin = at
                                // The composable function itself, so a drop on empty canvas has
                                // somewhere to land: its body is the container of last resort.
                                bounds.record(
                                    root,
                                    Rect(
                                        at.x,
                                        at.y,
                                        at.x + coords.size.width,
                                        at.y + coords.size.height,
                                    ),
                                    acceptsChildren = true,
                                )
                            },
                            color = if (dark) Color(0xFF121212) else Color.White,
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                root.children.forEach {
                                    ComposeNode(it, bounds, showBounds, selectedElement)
                                }
                            }
                        }
                    }
                }
            } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { host ->
                    val signature = listOf(root, resources, dark, effective, showBounds, selectedPath)
                    if (ref.signature == signature) return@AndroidView
                    ref.signature = signature
                    host.removeAllViews()
                    // Built smaller rather than drawn smaller — see the density note in
                    // LayoutRenderer for why zoom is a re-measure and not a transform.
                    val built =
                        LayoutRenderer(host.context, resources, dark, screenDensity * effective)
                    // Shown, not swallowed. A designer that renders nothing and says nothing is the
                    // worst failure available to it: the user cannot tell a broken layout from a
                    // broken designer.
                    val view = try {
                        built.render(root)
                    } catch (e: Throwable) {
                        host.addView(
                            android.widget.TextView(host.context).apply {
                                text = "Could not render this layout — " +
                                    e.javaClass.simpleName + ": " + e.message
                                setTextColor(android.graphics.Color.parseColor("#D06262"))
                                setPadding(24, 24, 24, 24)
                            },
                        )
                        return@AndroidView
                    }
                    host.addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                    // After attaching, not before: ConstraintSet.applyTo writes the children's
                    // params and then asks for a layout pass, and a detached view has nobody to ask.
                    built.assignIds()
                    if (showBounds) built.outlineAll(screenDensity)
                    selectedPath
                        ?.let { path -> root.flatten().firstOrNull { elementPath(root, it) == path } }
                        ?.let { built.views[it] }
                        ?.let { target -> built.outlineSelection(target, outline.toArgb(), screenDensity) }

                    ref.renderer = built
                },
            )
            }

            // Gestures live in Compose, over the inflated views rather than inside them: a child
            // that is not clickable never receives a touch, and making every widget clickable to fix
            // that would change the behaviour of the very layout being previewed.
            Box(
                Modifier.matchParentSize().canvasGestures(
                    key = root,
                    onTap = { at ->
                        val built = ref.renderer ?: return@canvasGestures
                        onSelect(hitTest(built, root, at)?.let { elementPath(root, it) })
                    },
                    onDragStart = { at ->
                        val built = ref.renderer ?: return@canvasGestures
                        val hit = hitTest(built, root, at)
                        val path = hit?.let { elementPath(root, it) }
                        // The root has nowhere to go, so a drag on empty canvas is not a drag.
                        if (hit != null && !path.isNullOrEmpty()) {
                            ref.moving = hit
                            onPickUp(path, hit.tag.substringAfterLast('.'), toRoot(canvasCoords, at))
                        }
                    },
                    onDragMove = { at ->
                        if (ref.moving == null) return@canvasGestures
                        onDragTo(toRoot(canvasCoords, at))
                    },
                    onDragEnd = { dropped ->
                        if (ref.moving != null) {
                            ref.moving = null
                            onDrop(dropped)
                        }
                    },
                ),
            )

            // Every drag is resolved here, from the position alone, whatever started it. The palette
            // and the layer tree have no idea where the canvas is — see DesignerDrag for why root
            // coordinates are the contract between them — and a second copy of this for canvas drags
            // would be a second chance to disagree with this one.
            val at = drag?.position
            val payload = drag?.payload
            LaunchedEffect(at, payload) {
                val built = ref.renderer
                if (at != null && built != null) {
                    val moving = (payload as? DragPayload.Move)?.let { elementAt(root, it.path) }
                    onHover(dropTargetAt(built, root, toCanvas(at), moving))
                }
            }

            if (drag != null) {
                val fill = outline.copy(alpha = 0.12f)
                Canvas(Modifier.matchParentSize()) {
                    hover?.let { target ->
                        drawRect(
                            color = fill,
                            topLeft = target.container.topLeft,
                            size = target.container.size,
                        )
                        drawRect(
                            color = outline,
                            topLeft = target.container.topLeft,
                            size = target.container.size,
                            style = Stroke(width = screenDensity),
                        )
                        target.line?.let { line ->
                            drawRect(color = outline, topLeft = line.topLeft, size = line.size)
                        }
                    }
                }
            }
            }

            if (chrome.navBar) SystemBar(48.dp * effective, barColour, "")
        }
    }
}

/** A simulated system or app bar. Drawn because it takes height the layout does not get. */
@Composable
private fun SystemBar(height: androidx.compose.ui.unit.Dp, colour: Color, label: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height).background(colour),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (label.isNotEmpty() && height > 20.dp) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Deepest element whose view contains the point — what a tap on overlapping widgets should pick. */
private fun hitTest(canvas: CanvasBounds, root: DesignElement, at: Offset): DesignElement? {
    var best: DesignElement? = null
    var bestDepth = -1
    fun walk(element: DesignElement, depth: Int) {
        // A node with no box still has children with boxes — a Compose `Row` that only groups, or
        // an element the canvas skipped. Descending anyway costs nothing; stopping would make its
        // whole subtree unselectable.
        val box = canvas.boundsOf(element)
        if (box != null && box.contains(at) && depth > bestDepth) {
            best = element
            bestDepth = depth
        }
        element.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return best
}

/**
 * A stable identity for an element: the child-index path from the root.
 *
 * Not the object, and not its id attribute: every edit reparses the file, so the instance is gone,
 * and plenty of widgets have no id. The path survives an attribute edit, which is the case that
 * matters — the user changes a colour and expects the thing to stay selected.
 */
internal fun elementPath(root: DesignElement, target: DesignElement): String? {
    if (root === target) return ""
    root.children.forEachIndexed { index, child ->
        elementPath(child, target)?.let { return "$index.$it" }
    }
    return null
}

/** The element a [elementPath] names, in a freshly parsed tree. */
internal fun elementAt(root: DesignElement, path: String): DesignElement? {
    if (path.isEmpty()) return root
    var current = root
    path.trim('.').split('.').filter { it.isNotEmpty() }.forEach { part ->
        val index = part.toIntOrNull() ?: return null
        current = current.children.getOrNull(index) ?: return null
    }
    return current
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

@Composable
private fun PropertiesPanel(
    document: DesignDocument,
    element: DesignElement,
    onSource: (String) -> Unit,
    onDelete: () -> Unit,
    onInsertChild: (PaletteItem) -> Unit,
    onDragStart: (DragPayload, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (Boolean) -> Unit,
) {
    Text(element.tag.substringAfterLast('.'), style = MaterialTheme.typography.titleSmall)

    document.propertiesFor(element).forEach { name ->
        val current = element.attributes.firstOrNull { it.name == name }?.value.orEmpty()
        var draft by remember(element.range.first, name, current) { mutableStateOf(current) }
        // Committed on a button rather than per keystroke: every commit rewrites the file and
        // reparses, and doing that on each character would fight the user's typing.
        InspectorField(
            label = name.substringAfter(':'),
            value = draft,
            onValueChange = { draft = it },
            dirty = draft != current,
            onCommit = {
                onSource(
                    if (draft.isBlank()) document.withoutAttribute(element, name)
                    else document.withAttribute(element, name, draft),
                )
            },
            onRevert = { draft = current },
        )
    }

    Divider(modifier = Modifier.padding(vertical = 4.dp))

    Text("Add inside this widget", style = MaterialTheme.typography.labelMedium)
    PalettePanel(
        format = document.format,
        onInsert = onInsertChild,
        onDragStart = onDragStart,
        onDragMove = onDragMove,
        onDragEnd = onDragEnd,
        modifier = Modifier.fillMaxWidth().height(220.dp),
        scrollable = true,
    )

    TextButton(onClick = onDelete) {
        Text("Delete this widget", color = MaterialTheme.colorScheme.error)
    }

    Column(Modifier.padding(top = 4.dp)) {
        Text("XML", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(
            text = document.text.substring(
                element.range.first,
                (element.range.last + 1).coerceAtMost(document.text.length),
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun toRoot(coords: LayoutCoordinates?, local: Offset): Offset =
    coords?.takeIf { it.isAttached }?.localToRoot(local) ?: local
