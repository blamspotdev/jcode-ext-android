package dev.jcode.ext.android.designer

import android.view.ViewGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** What a drag is carrying. */
internal sealed interface DragPayload {
    val label: String

    /** A palette widget on its way into the file for the first time. */
    data class New(val item: PaletteItem) : DragPayload {
        override val label: String get() = item.label
    }

    /**
     * A widget already in the file, on its way somewhere else.
     *
     * Carried as a path rather than as the element, because the drop reparses the document and the
     * element object the drag started from will not exist by the time it lands.
     */
    data class Move(val path: String, override val label: String) : DragPayload
}

internal data class DragState(val payload: DragPayload, val position: Offset)

/**
 * Where a drop would land: which container, at which child index, and what to draw to say so.
 *
 * [index] counts the container's children as they will be *after* the drag — a widget being moved
 * is already excluded from it. That is what makes dragging a widget one place down inside its own
 * parent land where the user pointed rather than one short of it.
 */
internal data class DropTarget(
    val containerPath: String,
    val index: Int,
    /** The container, in canvas pixels. */
    val container: Rect,
    /** The gap the widget will land in, when child order is what decides position; else null. */
    val line: Rect?,
)

/**
 * Long-press to pick up, drag to move, lift to drop.
 *
 * Long-press rather than an immediate drag because every one of these rows lives inside something
 * that scrolls, and an immediate drag would mean the palette could no longer be scrolled through —
 * which is what it is mostly used for.
 *
 * Positions are converted to root coordinates here, at the source, because that is the only frame
 * the palette, the layer tree and the canvas can all agree on: they sit in different columns, and
 * in portrait the palette is not even beside the canvas.
 */
@Composable
internal fun Modifier.dragSource(
    payload: DragPayload,
    onStart: (DragPayload, Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onEnd: (dropped: Boolean) -> Unit,
): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // The gesture outlives the composition that started it: `pointerInput` keeps the first lambda it
    // was given, and these callbacks read state that changes *during* the drag. Without this the
    // drop would be applied against where the finger was when it went down.
    val start by rememberUpdatedState(onStart)
    val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onEnd)
    return this
        .onGloballyPositioned { coords = it }
        .pointerInput(payload) {
            detectDragGesturesAfterLongPress(
                onDragStart = { start(payload, coords.toRoot(it)) },
                onDrag = { change, _ -> move(coords.toRoot(change.position)) },
                onDragEnd = { end(true) },
                onDragCancel = { end(false) },
            )
        }
}

private fun LayoutCoordinates?.toRoot(local: Offset): Offset =
    if (this != null && isAttached) localToRoot(local) else local

/**
 * Tap to select, long-press to drag — from one gesture owner.
 *
 * Written out rather than stacking a drag-after-long-press detector on top of a tap detector,
 * because the two would race for the same long press and whichever won would swallow the other.
 * One loop decides which gesture happened and there is nothing left to disagree about.
 */
@Composable
internal fun Modifier.canvasGestures(
    key: Any?,
    onTap: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (dropped: Boolean) -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDragMove)
    val end by rememberUpdatedState(onDragEnd)
    return pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown()
        // Consumed so the inflated layout underneath does not also react: a previewed Button that
        // ripples when the user meant to select it is showing the app's behaviour, not the IDE's.
        down.consume()

        var up: PointerInputChange? = null
        var longPressed = false
        try {
            up = withTimeout(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() }
        } catch (_: PointerEventTimeoutCancellationException) {
            longPressed = true
        }

        if (!longPressed) {
            if (up != null) tap(down.position)
            return@awaitEachGesture
        }

        start(down.position)
        val completed = drag(down.id) { change ->
            move(change.position)
            change.consume()
        }
        end(completed)
    }
    }
}

/**
 * A plain box for what the canvas built, deliberately not Compose state.
 *
 * The renderer is produced inside the `AndroidView` update block, and writing state from there
 * re-triggers the composition that runs it — an inflate loop. The gestures only need the renderer
 * when a finger is already down, which is long after any composition, so a field is both sufficient
 * and the only safe option.
 */
internal class RendererRef {
    var renderer: LayoutRenderer? = null
    /** The element the in-flight drag picked up, so the drop test can ignore it. */
    var moving: LayoutDocument.Element? = null
    /**
     * What the current views were built from, so they are rebuilt only when that changes.
     *
     * An `AndroidView` update block re-runs on every recomposition whose captures it cannot prove
     * unchanged, and a drag recomposes on every pointer move. Without this the layout is re-inflated
     * mid-drag, and the fresh views have no measured size yet — so the drop test hits nothing and
     * the drop silently does nothing at all.
     */
    var signature: List<Any?>? = null
}

/**
 * The container a point would drop into, and where among its children.
 *
 * The deepest container wins, the same rule the hit test uses: a drop on a widget means "beside
 * this one", not "at the back of whatever encloses it".
 */
internal fun dropTargetAt(
    renderer: LayoutRenderer,
    root: LayoutDocument.Element,
    point: Offset,
    moving: LayoutDocument.Element?,
): DropTarget? {
    val x = point.x
    val y = point.y
    val excluded: Set<LayoutDocument.Element> = moving?.flatten()?.toSet().orEmpty()

    var container: LayoutDocument.Element? = null
    var deepest = -1

    fun walk(element: LayoutDocument.Element, depth: Int) {
        if (element in excluded) return
        val view = renderer.views[element] ?: return
        val (left, top) = renderer.offsetOf(view)
        val inside = x >= left && x < left + view.width && y >= top && y < top + view.height
        if (inside && view is ViewGroup && depth > deepest) {
            container = element
            deepest = depth
        }
        element.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)

    val target = container ?: return null
    val path = elementPath(root, target) ?: return null
    val view = renderer.views[target] ?: return null
    val (left, top) = renderer.offsetOf(view)
    val box =
        Rect(left.toFloat(), top.toFloat(), (left + view.width).toFloat(), (top + view.height).toFloat())

    val kids = target.children.filter { it !in excluded && renderer.views[it] != null }
    val axis = axisOf(target)
    if (axis == null || kids.isEmpty()) {
        // A FrameLayout or a ConstraintLayout positions its children by their own attributes, so
        // there is no "between" to point at — the container itself is the whole answer.
        return DropTarget(path, kids.size, box, null)
    }

    var index = kids.size
    for ((at, child) in kids.withIndex()) {
        val childView = renderer.views[child] ?: continue
        val (childLeft, childTop) = renderer.offsetOf(childView)
        val centre =
            if (axis == Axis.Vertical) childTop + childView.height / 2f
            else childLeft + childView.width / 2f
        val along = if (axis == Axis.Vertical) y else x
        if (along < centre) {
            index = at
            break
        }
    }

    val edge = if (index < kids.size) {
        val v = renderer.views.getValue(kids[index])
        val (l, t) = renderer.offsetOf(v)
        if (axis == Axis.Vertical) t.toFloat() else l.toFloat()
    } else {
        val v = renderer.views.getValue(kids.last())
        val (l, t) = renderer.offsetOf(v)
        if (axis == Axis.Vertical) (t + v.height).toFloat() else (l + v.width).toFloat()
    }
    val line = if (axis == Axis.Vertical) {
        Rect(box.left, edge - 2f, box.right, edge + 2f)
    } else {
        Rect(edge - 2f, box.top, edge + 2f, box.bottom)
    }
    return DropTarget(path, index, box, line)
}

private enum class Axis { Vertical, Horizontal }

/** The axis a container lays its children out along, or null when child order does not decide it. */
private fun axisOf(element: LayoutDocument.Element): Axis? = when {
    element.tag.endsWith("HorizontalScrollView") -> Axis.Horizontal
    element.tag.endsWith("LinearLayout") || element.tag.endsWith("RadioGroup") ->
        if (element.value("orientation") == "horizontal") Axis.Horizontal else Axis.Vertical
    element.tag.endsWith("ScrollView") -> Axis.Vertical
    else -> null
}

// ---- paths, across an edit that removes an element ----

internal fun pathParts(path: String): List<Int> =
    path.trim('.').split('.').filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }

internal fun pathOf(parts: List<Int>): String =
    if (parts.isEmpty()) "" else parts.joinToString(separator = ".", postfix = ".")

/**
 * [target] as it will read once [removed] is gone, or null when the move makes no sense.
 *
 * A move is a removal followed by an insertion, and the removal shifts every later sibling up one.
 * Without this the widget lands next to the wrong neighbour — and dropping something inside itself
 * would delete it, which is why that case returns null rather than an adjusted path.
 */
internal fun pathAfterRemoval(target: String, removed: String): String? {
    val t = pathParts(target)
    val r = pathParts(removed)
    if (r.isEmpty()) return null
    if (t.size >= r.size && t.take(r.size) == r) return null
    val depth = r.size - 1
    val out = t.toMutableList()
    if (out.size > depth && out.take(depth) == r.take(depth) && out[depth] > r[depth]) {
        out[depth] = out[depth] - 1
    }
    return pathOf(out)
}

/**
 * Strip [indent] from every line that carries it.
 *
 * Text lifted straight out of the file keeps the indentation of where it used to be, and the insert
 * adds the indentation of where it is going. Without this a widget gains four spaces every time it
 * is dragged.
 */
internal fun dedent(xml: String, indent: String): String =
    if (indent.isEmpty()) {
        xml
    } else {
        xml.lines().joinToString("\n") { if (it.startsWith(indent)) it.removePrefix(indent) else it }
    }
