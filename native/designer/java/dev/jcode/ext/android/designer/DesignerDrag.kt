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
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
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
    /** Which surface resolved this, and therefore which one draws it. */
    val surface: DropSurface,
    /** The container, in that surface's own pixels. */
    val container: Rect,
    /** The gap the widget will land in, when child order is what decides position; else null. */
    val line: Rect?,
)

/** The two places a drag can be dropped. */
internal enum class DropSurface { Canvas, Tree }

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
 * Every gesture the canvas answers to, from one owner.
 *
 * Tap selects. Hold, then move, picks a widget up. Move without holding pans. Two fingers pinch and
 * pan. Ctrl and a drag pans, for a mouse that has no second finger to say it with.
 *
 * Written out rather than stacking detectors, because a drag-after-long-press detector and a tap
 * detector would race for the same long press and whichever won would swallow the other. One loop
 * decides which of the five happened and there is nothing left to disagree about.
 *
 * It also settles a disagreement the loop cannot see. This composable sits inside JCode's shell,
 * whose navigation drawer drags open on sideways movement anywhere in the content — so a pointer
 * whose movement is left unconsumed while the loop is still working out what it is gets read as an
 * open-swipe, and the drawer slides out over the canvas mid-gesture. The movement is therefore
 * consumed from the first event that carries any, before the gesture has a name, which is the only
 * point early enough to matter: once the drawer has taken the pointer, consuming it later just
 * cancels the drawer half-open.
 */
@Composable
internal fun Modifier.canvasGestures(
    key: Any?,
    onTap: (Offset) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: (dropped: Boolean) -> Unit,
    onPan: (Offset) -> Unit,
    onZoomBy: (Float) -> Unit,
): Modifier {
    val tap by rememberUpdatedState(onTap)
    val start by rememberUpdatedState(onDragStart)
    val move by rememberUpdatedState(onDragMove)
    val end by rememberUpdatedState(onDragEnd)
    val pan by rememberUpdatedState(onPan)
    val zoomBy by rememberUpdatedState(onZoomBy)
    return pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown()
            // Consumed so the inflated layout underneath does not also react: a previewed Button
            // that ripples when the user meant to select it is showing the app's behaviour, not the
            // IDE's.
            down.consume()

            // Ctrl and drag is the mouse's second hand. A pointer has one button and no way to say
            // "move the view rather than the thing under me", and on a laptop there is no second
            // finger to say it with either.
            if (currentEvent.keyboardModifiers.isCtrlPressed) {
                panUntilUp(down.id, pan)
                return@awaitEachGesture
            }

            var up: PointerInputChange? = null
            var longPressed = false
            var multitouch = false
            var travel = Offset.Zero
            var dragging = false
            try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val event = awaitPointerEvent()
                        // A second finger arriving turns this into a pinch, whatever the first one
                        // looked like it was starting.
                        if (event.changes.size > 1) {
                            multitouch = true
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.positionChanged()) {
                            travel += change.positionChange()
                            // Consumed as it arrives, not once the gesture is understood. This
                            // pointer went down on the canvas and the canvas is keeping it; left
                            // unconsumed, the shell's navigation drawer reads the same sideways
                            // movement as its own open-swipe and slides out over the design.
                            change.consume()
                        }
                        if (travel.getDistance() > viewConfiguration.touchSlop) {
                            dragging = true
                            break
                        }
                        if (!change.pressed) {
                            up = change
                            break
                        }
                    }
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                longPressed = true
            }

            if (multitouch) {
                transformUntilUp(pan, zoomBy)
                return@awaitEachGesture
            }

            // Moved before the hold deadline, so one finger pans — the same thing two fingers
            // together and Ctrl-and-drag already do. Holding still is what picks a widget up, which
            // is the ordinary bargain on a canvas: move at once and you move the view, hold first
            // and you move the thing under you. The slop already travelled is handed over rather
            // than dropped, or every pan would begin by throwing away its first few pixels.
            if (dragging) {
                pan(travel)
                panUntilUp(down.id, pan)
                return@awaitEachGesture
            }

            if (!longPressed) {
                // Only a pointer that never left slop is a tap. Without that test a swipe across
                // the canvas selected whatever happened to be under the finger when it landed.
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

/** One pointer, moving the view rather than anything in it. */
private suspend fun AwaitPointerEventScope.panUntilUp(id: PointerId, onPan: (Offset) -> Unit) {
    drag(id) { change ->
        onPan(change.positionChange())
        change.consume()
    }
}

/**
 * Two fingers: pinch to zoom, move together to pan.
 *
 * Zoom arrives as a *ratio* per event, which is what a pinch measures and what the toolbar's
 * buttons already apply — so both routes to zooming agree on what a step means.
 */
private suspend fun AwaitPointerEventScope.transformUntilUp(
    onPan: (Offset) -> Unit,
    onZoomBy: (Float) -> Unit,
) {
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.any { it.isConsumed }) return
        val zoom = event.calculateZoom()
        val panned = event.calculatePan()
        if (zoom != 1f) onZoomBy(zoom)
        if (panned != Offset.Zero) onPan(panned)
        event.changes.forEach { if (it.positionChanged()) it.consume() }
        if (event.changes.none { it.pressed }) return
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
    var renderer: CanvasBounds? = null
    /** Where the canvas sits, so a root-coordinate drag can be asked about in canvas pixels. */
    var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    /** The element the in-flight drag picked up, so the drop test can ignore it. */
    var moving: DesignElement? = null
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
    canvas: CanvasBounds,
    root: DesignElement,
    point: Offset,
    moving: DesignElement?,
): DropTarget? {
    val excluded: Set<DesignElement> = moving?.flatten()?.toSet().orEmpty()

    var container: DesignElement? = null
    var deepest = -1

    fun walk(element: DesignElement, depth: Int) {
        if (element in excluded) return
        val box = canvas.boundsOf(element)
        if (box != null && box.contains(point) && canvas.acceptsChildren(element) && depth > deepest) {
            container = element
            deepest = depth
        }
        element.children.forEach { walk(it, depth + 1) }
    }
    walk(root, 0)

    val target = container ?: return null
    val path = elementPath(root, target) ?: return null
    val box = canvas.boundsOf(target) ?: return null

    val kids = target.children.filter { it !in excluded && canvas.boundsOf(it) != null }
    val axis = axisOf(target)
    if (axis == null || kids.isEmpty()) {
        // A FrameLayout or a ConstraintLayout positions its children by their own attributes, so
        // there is no "between" to point at — the container itself is the whole answer.
        return DropTarget(path, kids.size, DropSurface.Canvas, box, null)
    }

    var index = kids.size
    for ((at, child) in kids.withIndex()) {
        val childBox = canvas.boundsOf(child) ?: continue
        val centre = if (axis == Axis.Vertical) childBox.center.y else childBox.center.x
        val along = if (axis == Axis.Vertical) point.y else point.x
        if (along < centre) {
            index = at
            break
        }
    }

    val edge = if (index < kids.size) {
        val b = canvas.boundsOf(kids[index]) ?: box
        if (axis == Axis.Vertical) b.top else b.left
    } else {
        val b = canvas.boundsOf(kids.last()) ?: box
        if (axis == Axis.Vertical) b.bottom else b.right
    }
    val line = if (axis == Axis.Vertical) {
        Rect(box.left, edge - 2f, box.right, edge + 2f)
    } else {
        Rect(edge - 2f, box.top, edge + 2f, box.bottom)
    }
    return DropTarget(path, index, DropSurface.Canvas, box, line)
}

/**
 * The layer tree as somewhere to drop.
 *
 * The canvas can only offer what it draws, and a tree exists precisely because a canvas cannot show
 * everything: an empty container has no area to aim at, a `gone` view none at all, and in portrait
 * the canvas may not even be on screen beside the list. Dropping between two rows is the gesture
 * anyone looking at an outline reaches for, so the tree resolves drops itself rather than asking the
 * user to find the same widget on the canvas first.
 *
 * Rows report their own boxes, in root coordinates, for the same reason everything else here does —
 * it is the one frame the tree, the canvas and the palette all share.
 */
internal class TreeDropSurface {

    private class RowBox(val element: DesignElement, val path: String, val box: Rect)

    private val rows = LinkedHashMap<String, RowBox>()

    /** The tree's own top-left, so a target can be handed back in tree coordinates. */
    var origin: Offset = Offset.Zero

    /** The whole tree, for deciding whether a point is over it at all. */
    var bounds: Rect = Rect.Zero

    fun record(element: DesignElement, path: String, box: Rect) {
        rows[path] = RowBox(element, path, box)
    }

    fun forget(path: String) {
        rows.remove(path)
    }

    operator fun contains(atRoot: Offset): Boolean = bounds.contains(atRoot)

    /**
     * Where [atRoot] would drop.
     *
     * A row is three zones: the top and bottom thirds place the widget beside it, and the middle
     * places it inside — but only when the row can hold children, since "inside a TextView" is not
     * a thing and silently meaning "after it" is better than refusing the drop.
     */
    fun resolve(
        atRoot: Offset,
        root: DesignElement,
        moving: DesignElement?,
        accepts: (DesignElement) -> Boolean,
    ): DropTarget? {
        if (!contains(atRoot)) return null
        val excluded: Set<DesignElement> = moving?.flatten()?.toSet().orEmpty()
        val row = rows.values
            .filter { it.element !in excluded }
            .firstOrNull { atRoot.y >= it.box.top && atRoot.y < it.box.bottom }
            ?: return null

        val third = row.box.height / 3f
        val inside = atRoot.y > row.box.top + third &&
            atRoot.y < row.box.bottom - third &&
            accepts(row.element)

        if (inside) {
            val kids = row.element.children.count { it !in excluded }
            return DropTarget(row.path, kids, DropSurface.Tree, row.box.shift(origin), null)
        }

        val parentPath = pathOf(pathParts(row.path).dropLast(1))
        val parent = elementAt(root, parentPath) ?: return null
        val siblings = parent.children.filter { it !in excluded }
        val at = siblings.indexOf(row.element)
        if (at < 0) return null
        val after = atRoot.y >= row.box.center.y
        val index = if (after) at + 1 else at
        val edge = if (after) row.box.bottom else row.box.top
        val line = Rect(row.box.left, edge - 2f, row.box.right, edge + 2f)
        return DropTarget(
            parentPath,
            index,
            DropSurface.Tree,
            rows[parentPath]?.box?.shift(origin) ?: Rect.Zero,
            line.shift(origin),
        )
    }

    private fun Rect.shift(by: Offset) = translate(-by.x, -by.y)
}

private enum class Axis { Vertical, Horizontal }

/** The axis a container lays its children out along, or null when child order does not decide it. */
private fun axisOf(element: DesignElement): Axis? = when {
    element.tag.endsWith("HorizontalScrollView") -> Axis.Horizontal
    element.tag.endsWith("LinearLayout") || element.tag.endsWith("RadioGroup") ->
        if (element.value("orientation") == "horizontal") Axis.Horizontal else Axis.Vertical
    element.tag.endsWith("ScrollView") -> Axis.Vertical
    // Compose, Flutter and React Native each name the direction in the widget rather than in an
    // attribute, which makes this the easy half.
    element.tag in VERTICAL_TAGS -> Axis.Vertical
    element.tag in HORIZONTAL_TAGS -> Axis.Horizontal
    else -> null
}

private val VERTICAL_TAGS = setOf("Column", "LazyColumn", "ListView", "SingleChildScrollView")
private val HORIZONTAL_TAGS = setOf("Row", "LazyRow")

/**
 * Where each element ended up on the canvas, whatever drew it.
 *
 * The hit test and the drop test are about rectangles, not about Views, so they are written against
 * this: an Android layout inflated into real widgets and a Compose tree measured by the Compose
 * runtime both answer the same two questions, and neither one needs its own copy of this logic.
 */
internal interface CanvasBounds {
    /** The box this element occupies, in canvas pixels, or null when it was not drawn. */
    fun boundsOf(element: DesignElement): Rect?

    /** True when this element can take children — a drop has to land somewhere that accepts one. */
    fun acceptsChildren(element: DesignElement): Boolean
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
