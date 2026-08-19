package dev.jcode.ext.android.designer

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/** A device the canvas can be sized to. Widths are the common Android breakpoints. */
private data class DeviceSize(val label: String, val widthDp: Int, val heightDp: Int)

private val DEVICES = listOf(
    DeviceSize("Phone", 411, 891),
    DeviceSize("Phone (small)", 360, 640),
    DeviceSize("Foldable", 673, 841),
    DeviceSize("Tablet", 800, 1280),
)

/** Widgets the palette can insert. Only ones the renderer draws for real — offering a Material
 *  button that lands as a dashed placeholder would be worse than not offering it. */
private val PALETTE = listOf(
    "TextView" to "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Text\" />",
    "Button" to "<Button\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Button\" />",
    "EditText" to "<EditText\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:hint=\"Enter text\" />",
    "ImageView" to "<ImageView\n    android:layout_width=\"48dp\"\n    android:layout_height=\"48dp\" />",
    "LinearLayout" to "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"wrap_content\"\n    android:orientation=\"vertical\" />",
    "CheckBox" to "<CheckBox\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"Check me\" />",
)

private val COMMON_ATTRS = listOf(
    "android:id", "android:layout_width", "android:layout_height",
    "android:layout_margin", "android:padding", "android:background", "android:visibility",
)
private val TEXT_ATTRS =
    listOf("android:text", "android:textSize", "android:textColor", "android:textStyle", "android:gravity")

@Composable
internal fun DesignerScreen(
    source: String,
    projectDir: File?,
    onSource: (String) -> Unit,
) {
    val document = remember(source) { LayoutDocument.parse(source) }
    val resources = remember(projectDir, source) { ResourceTable.read(projectDir) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var device by remember { mutableStateOf(DEVICES.first()) }
    var deviceMenu by remember { mutableStateOf(false) }
    var darkCanvas by remember { mutableStateOf(false) }
    var showBounds by remember { mutableStateOf(true) }
    // null means "fit the pane" — the state a designer should open in.
    var zoom by remember { mutableStateOf<Float?>(null) }

    val root = document.root
    if (root == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "This file has no layout element yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // Selection is held as a child-index path, not as the element object: every edit reparses the
    // file, so the instance the user tapped no longer exists by the time their change comes back.
    val flat = remember(document) { root.flatten() }
    val selected = flat.firstOrNull { pathOf(root, it) == selectedPath }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box {
                TextButton(onClick = { deviceMenu = true }) { Text("${device.label} · ${device.widthDp}dp") }
                DropdownMenu(expanded = deviceMenu, onDismissRequest = { deviceMenu = false }) {
                    DEVICES.forEach { d ->
                        DropdownMenuItem(
                            text = { Text("${d.label} — ${d.widthDp}×${d.heightDp}dp") },
                            onClick = { device = d; deviceMenu = false },
                        )
                    }
                }
            }
            TextButton(onClick = { darkCanvas = !darkCanvas }) { Text(if (darkCanvas) "Dark" else "Light") }
            TextButton(onClick = { showBounds = !showBounds }) {
                Text(if (showBounds) "Bounds" else "No bounds")
            }
            TextButton(onClick = { zoom = ((zoom ?: 1f) - 0.15f).coerceAtLeast(0.1f) }) { Text("−") }
            TextButton(onClick = { zoom = null }) { Text(zoom?.let { "${(it * 100).toInt()}%" } ?: "Fit") }
            TextButton(onClick = { zoom = ((zoom ?: 1f) + 0.15f).coerceAtMost(3f) }) { Text("+") }
            Text(
                text = selected?.let { "${it.tag.substringAfterLast('.')} selected" }
                    ?: "${flat.size} widgets",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Row(Modifier.fillMaxWidth().weight(1f)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)),
            ) {
                DesignCanvas(
                    root = root,
                    resources = resources,
                    device = device,
                    dark = darkCanvas,
                    showBounds = showBounds,
                    zoom = zoom,
                    selectedPath = selectedPath,
                    onSelect = { selectedPath = it },
                )
            }

            Divider(
                modifier = Modifier.fillMaxHeight().width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )

            Column(
                modifier = Modifier.width(300.dp).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selected == null) {
                    Text("Palette", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Tap a widget on the canvas to edit it, or add one to the root here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PALETTE.forEach { (label, xml) ->
                        TextButton(onClick = { onSource(document.withChild(root, xml)) }) { Text("+  $label") }
                    }
                } else {
                    PropertiesPanel(
                        document = document,
                        element = selected,
                        onSource = onSource,
                        onDelete = {
                            selectedPath = null
                            onSource(document.without(selected))
                        },
                        onAddChild = { xml -> onSource(document.withChild(selected, xml)) },
                    )
                }
            }
        }
    }
}

/**
 * The layout, inflated for real at the canvas's own scale.
 *
 * Hosted in an [AndroidView] because the thing being previewed *is* a View hierarchy — asking
 * Compose to approximate one would reintroduce exactly the guesswork this avoids.
 */
@Composable
private fun DesignCanvas(
    root: LayoutDocument.Element,
    resources: ResourceTable,
    device: DeviceSize,
    dark: Boolean,
    showBounds: Boolean,
    /** null = fit the pane; otherwise the user's zoom factor. */
    zoom: Float?,
    selectedPath: String?,
    onSelect: (String?) -> Unit,
) {
    val outline = MaterialTheme.colorScheme.primary
    val screenDensity = LocalDensity.current.density

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // "Fit" until the user zooms, which is the state a designer should open in.
        val fit = minOf(maxWidth / device.widthDp.dp, maxHeight / device.heightDp.dp, 1f)
        val effective = (zoom ?: fit).coerceIn(0.1f, 3f)

        Box(
            modifier = Modifier
                .size(width = device.widthDp.dp * effective, height = device.heightDp.dp * effective)
                .background(if (dark) Color(0xFF121212) else Color.White, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> FrameLayout(ctx) },
                update = { host ->
                    host.removeAllViews()
                    // Built smaller rather than drawn smaller — see the density note in
                    // LayoutRenderer for why zoom is a re-measure and not a transform.
                    val renderer =
                        LayoutRenderer(host.context, resources, dark, screenDensity * effective)
                    // Shown, not swallowed. A designer that renders nothing and says nothing is the
                    // worst failure available to it: the user cannot tell a broken layout from a
                    // broken designer.
                    val view = try {
                        renderer.render(root)
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
                    renderer.assignIds()
                    if (showBounds) renderer.outlineAll()
                    selectedPath
                        ?.let { path -> root.flatten().firstOrNull { pathOf(root, it) == path } }
                        ?.let { renderer.views[it] }
                        ?.let { target -> renderer.outlineSelection(target, outline.toArgb()) }

                    // One listener on the host rather than one per view: a child that is not
                    // clickable never receives the event, and making every widget clickable would
                    // change the behaviour of the very layout being previewed.
                    host.setOnTouchListener { _, event ->
                        if (event.action == android.view.MotionEvent.ACTION_UP) {
                            val hit = hitTest(renderer, root, event.x.toInt(), event.y.toInt())
                            onSelect(hit?.let { pathOf(root, it) })
                        }
                        true
                    }
                },
            )
        }
    }
}

/** Deepest element whose view contains the point — what a tap on overlapping widgets should pick. */
private fun hitTest(
    renderer: LayoutRenderer,
    root: LayoutDocument.Element,
    x: Int,
    y: Int,
): LayoutDocument.Element? {
    var best: LayoutDocument.Element? = null
    var bestDepth = -1
    fun walk(element: LayoutDocument.Element, depth: Int) {
        val view = renderer.views[element] ?: return
        val (left, top) = absoluteOffset(view)
        if (x >= left && x < left + view.width && y >= top && y < top + view.height && depth > bestDepth) {
            best = element
            bestDepth = depth
        }
        element.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)
    return best
}

/** A view's position relative to the canvas host, walked by hand — no window coordinates involved. */
internal fun absoluteOffset(view: View): Pair<Int, Int> {
    var x = 0
    var y = 0
    var current: View? = view
    while (current != null && current.parent is View) {
        x += current.left
        y += current.top
        current = current.parent as? View
    }
    return x to y
}

/**
 * A stable identity for an element: the child-index path from the root.
 *
 * Not the object, and not its id attribute: every edit reparses the file, so the instance is gone,
 * and plenty of widgets have no id. The path survives an attribute edit, which is the case that
 * matters — the user changes a colour and expects the thing to stay selected.
 */
private fun pathOf(root: LayoutDocument.Element, target: LayoutDocument.Element): String? {
    if (root === target) return ""
    root.children.forEachIndexed { index, child ->
        pathOf(child, target)?.let { return "$index.$it" }
    }
    return null
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

@Composable
private fun PropertiesPanel(
    document: LayoutDocument,
    element: LayoutDocument.Element,
    onSource: (String) -> Unit,
    onDelete: () -> Unit,
    onAddChild: (String) -> Unit,
) {
    Text(element.tag.substringAfterLast('.'), style = MaterialTheme.typography.titleSmall)

    val isTextish = listOf("TextView", "Button", "EditText", "CheckBox", "Switch")
        .any { element.tag.endsWith(it) }
    val attrs = COMMON_ATTRS + if (isTextish) TEXT_ATTRS else emptyList()

    attrs.forEach { name ->
        val current = element.attributes.firstOrNull { it.name == name }?.value.orEmpty()
        var draft by remember(element.range.first, name, current) { mutableStateOf(current) }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text(name.substringAfter(':'), style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        // Committed on a button rather than per keystroke: every commit rewrites the file and
        // reparses, and doing that on each character would fight the user's typing.
        if (draft != current) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = {
                    onSource(
                        if (draft.isBlank()) document.withoutAttribute(element, name)
                        else document.withAttribute(element, name, draft),
                    )
                }) { Text("Apply") }
                TextButton(onClick = { draft = current }) { Text("Revert") }
            }
        }
    }

    Divider(modifier = Modifier.padding(vertical = 4.dp))

    Text("Add child", style = MaterialTheme.typography.labelMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        PALETTE.forEach { (label, xml) ->
            TextButton(onClick = { onAddChild(xml) }) {
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    TextButton(onClick = onDelete) {
        Text("Delete this widget", color = MaterialTheme.colorScheme.error)
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(8.dp)) {
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
}
