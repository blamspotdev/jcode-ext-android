package dev.jcode.ext.android.designer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import androidx.compose.ui.geometry.Rect
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet

/**
 * Builds real Android [View]s from a parsed layout.
 *
 * Real views, not an approximation of them: a `TextView` here is a `TextView`, measured and laid out
 * by the framework's own code. That is the only way a designer's answer to "how wide does this get"
 * is the same answer the device gives, and it costs nothing — this process is an Android app.
 *
 * Two tiers, and the boundary is honest rather than hidden:
 *
 * - **Widgets this plugin can construct** — the framework's, plus `ConstraintLayout`, which the
 *   plugin bundles precisely because JCode does not ship it and the scaffolded template's root is
 *   one. These render exactly.
 * - **Everything else** — Material components, a project's own custom views. Their classes live in
 *   the *project's* dependencies, not here, so they are drawn as a labelled placeholder that keeps
 *   the right bounds. A box saying `MaterialCardView` is honest; a rounded rectangle pretending to
 *   be one is not, because the user cannot tell which parts of what they see are real.
 */
internal class LayoutRenderer(
    private val context: Context,
    private val resources: ResourceTable,
    /** Which surface the canvas is showing. Decides the default text colour, nothing else. */
    private val dark: Boolean = false,
    /**
     * Pixels per dp for this render — the screen's density scaled by the canvas zoom.
     *
     * Zooming by lowering the density rather than by scaling the drawn result: a transform leaves
     * the views measured at full size inside a box a fraction of that, which is a clipping and
     * coordinate problem in every direction. Re-measuring at a smaller density is what "show me
     * this screen smaller" actually means — every dp, sp and margin shrinks together, the hit test
     * needs no inverse transform, and there is nothing to clip.
     */
    private val density: Float,
) : CanvasBounds {

    /**
     * What a widget with no explicit colour is drawn in.
     *
     * Needed because these views are constructed against **JCode's** theme, not the project's: a
     * bare TextView inherits the IDE's dark-theme foreground, which is white, and a white-on-white
     * canvas is indistinguishable from a designer that failed to render anything. The project's real
     * theme lives in its own resource table and is not available until it is built.
     */
    private val defaultTextColor: Int = if (dark) 0xFFECEFF4.toInt() else 0xDE000000.toInt()

    /** Every view built, by the element it came from — the hit test and the outline read this. */
    val views = LinkedHashMap<DesignElement, View>()

    /** The view the layout's root element became, and the origin every offset here is measured from. */
    var rootView: View? = null
        private set

    fun render(root: DesignElement): View {
        views.clear()
        return build(root, parentTag = null).also { rootView = it }
    }

    /**
     * [view]'s position relative to the rendered root — the canvas's own coordinate space.
     *
     * Stopping at the root is the whole point. Walking on up through the host reaches the window,
     * and the resulting coordinates are then in a different frame from the touch positions they are
     * compared against — which does not fail loudly, it just means nothing is ever under the finger.
     */
    override fun boundsOf(element: DesignElement): Rect? {
        val view = views[element] ?: return null
        val (left, top) = offsetOf(view)
        return Rect(
            left.toFloat(),
            top.toFloat(),
            (left + view.width).toFloat(),
            (top + view.height).toFloat(),
        )
    }

    /** A ViewGroup, which on the Android side is exactly what "can take children" means. */
    override fun acceptsChildren(element: DesignElement): Boolean = views[element] is ViewGroup

    fun offsetOf(view: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var current: View? = view
        while (current != null && current !== rootView) {
            x += current.left
            y += current.top
            current = current.parent as? View
        }
        return x to y
    }

    private fun build(element: DesignElement, parentTag: String?): View {
        val view = create(element)
        views[element] = view

        applyCommon(element, view)

        if (view is ViewGroup && view !is PlaceholderView) {
            element.children.forEach { child ->
                val childView = build(child, element.tag)
                view.addView(childView, layoutParams(child, element.tag))
            }
        }
        // The root's own params are supplied by whatever hosts it; children get theirs above.
        if (parentTag == null) view.layoutParams = layoutParams(element, null)
        return view
    }

    private fun create(element: DesignElement): View = when (element.tag.substringAfterLast('.')) {
        "LinearLayout" -> LinearLayout(context).apply {
            orientation = if (element.value("orientation") == "vertical") {
                LinearLayout.VERTICAL
            } else {
                LinearLayout.HORIZONTAL
            }
        }
        "FrameLayout" -> FrameLayout(context)
        "ScrollView" -> ScrollView(context)
        "ConstraintLayout" -> ConstraintLayout(context)
        "TextView" -> TextView(context)
        "Button" -> Button(context)
        "EditText" -> EditText(context)
        "ImageView" -> ImageView(context)
        "CheckBox" -> CheckBox(context)
        "Switch" -> Switch(context)
        "ProgressBar" -> ProgressBar(context)
        "View" -> View(context)
        "Space" -> View(context)
        else -> PlaceholderView(context, element.tag.substringAfterLast('.'))
    }

    /**
     * Outline every built view.
     *
     * A designer aid — an empty ViewGroup or a zero-height widget is invisible otherwise, and "I
     * cannot see it" and "it is not there" are the two things a layout tool must let you tell apart.
     */
    /**
     * [strokeDensity] is the *screen's* density, not this renderer's.
     *
     * These outlines are the designer's chrome, not part of the layout: at a fit-to-pane zoom the
     * render density can fall to 0.7, and a hairline that thin at 20% alpha is not faint — it is
     * gone. The bounds have to stay legible at every zoom, which means they are the one thing here
     * measured against the screen rather than against the device being previewed.
     */
    fun outlineAll(strokeDensity: Float = density) {
        views.values.forEach { view ->
            if (view.foreground == null) view.foreground = BoundsDrawable(strokeDensity, 0x6600A0FF)
        }
    }

    /** Outline the selected view, on top of any bounds outline it already carries. */
    fun outlineSelection(target: View, color: Int, strokeDensity: Float = density) {
        target.foreground = BoundsDrawable(strokeDensity * 2f, color)
    }

    /** True when this element is drawn as itself rather than as a placeholder. */
    fun isReal(element: DesignElement): Boolean = views[element] !is PlaceholderView

    private fun applyCommon(element: DesignElement, view: View) {
        element.attributes.forEach { attr ->
            if (attr.prefix == "tools") return@forEach
            when (attr.local) {
                "text" -> (view as? TextView)?.text = resources.string(attr.value)
                "hint" -> (view as? TextView)?.hint = resources.string(attr.value)
                "textSize" -> resources.dimension(attr.value, density)?.let {
                    (view as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_PX, it)
                }
                "textColor" -> resources.color(attr.value)?.let { (view as? TextView)?.setTextColor(it) }
                "textStyle" -> (view as? TextView)?.setTypeface(null, typefaceStyle(attr.value))
                "maxLines" -> (view as? TextView)?.maxLines = attr.value.toIntOrNull() ?: Int.MAX_VALUE
                "ellipsize" -> (view as? TextView)?.ellipsize = TextUtils.TruncateAt.END
                "gravity" -> (view as? TextView)?.gravity = gravity(attr.value)
                "background" -> resources.color(attr.value)?.let { view.setBackgroundColor(it) }
                "alpha" -> view.alpha = attr.value.toFloatOrNull() ?: 1f
                "visibility" -> view.visibility = when (attr.value) {
                    "gone" -> View.GONE
                    "invisible" -> View.INVISIBLE
                    else -> View.VISIBLE
                }
                "padding" -> px(attr.value)?.let { view.setPadding(it, it, it, it) }
                "paddingStart", "paddingLeft" -> px(attr.value)?.let {
                    view.setPadding(it, view.paddingTop, view.paddingRight, view.paddingBottom)
                }
                "paddingEnd", "paddingRight" -> px(attr.value)?.let {
                    view.setPadding(view.paddingLeft, view.paddingTop, it, view.paddingBottom)
                }
                "paddingTop" -> px(attr.value)?.let {
                    view.setPadding(view.paddingLeft, it, view.paddingRight, view.paddingBottom)
                }
                "paddingBottom" -> px(attr.value)?.let {
                    view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, it)
                }
            }
        }
        if (view is TextView && element.attr("textColor") == null) view.setTextColor(defaultTextColor)
        // A TextView with nothing to say is invisible on the canvas and impossible to select; show
        // what it is instead, the way a design tool shows an empty text frame.
        if (view is TextView && view.text.isNullOrEmpty() && view !is Button) {
            view.text = element.value("id")?.substringAfterLast('/')?.let { "($it)" } ?: ""
        }
    }

    private fun layoutParams(element: DesignElement, parentTag: String?): ViewGroup.LayoutParams {
        val w = size(element.value("layout_width"))
        val h = size(element.value("layout_height"))

        val params: ViewGroup.MarginLayoutParams = when (parentTag?.substringAfterLast('.')) {
            "LinearLayout" -> LinearLayout.LayoutParams(w, h).apply {
                element.value("layout_weight")?.toFloatOrNull()?.let { weight = it }
                element.value("layout_gravity")?.let { gravity = gravity(it) }
            }
            "ConstraintLayout" -> ConstraintLayout.LayoutParams(w, h)
            else -> FrameLayout.LayoutParams(w, h).apply {
                element.value("layout_gravity")?.let { gravity = gravity(it) }
            }
        }

        px(element.value("layout_margin"))?.let { params.setMargins(it, it, it, it) }
        px(element.value("layout_marginStart") ?: element.value("layout_marginLeft"))?.let {
            params.leftMargin = it; params.marginStart = it
        }
        px(element.value("layout_marginEnd") ?: element.value("layout_marginRight"))?.let {
            params.rightMargin = it; params.marginEnd = it
        }
        px(element.value("layout_marginTop"))?.let { params.topMargin = it }
        px(element.value("layout_marginBottom"))?.let { params.bottomMargin = it }
        return params
    }

    /**
     * Apply a ConstraintLayout's child constraints, after its children are in place.
     *
     * Through [ConstraintSet] rather than by setting `LayoutParams.startToStart` and friends
     * directly. Both look like they should work; only this one does, because ConstraintLayout
     * resolves a child's constraints from the widget graph it builds when a set is applied, and
     * params poked in by hand are read too late to take part in it. Setting them by hand renders
     * every child stacked at the top-left, which is the layout you get when nothing is constrained.
     *
     * Ids are synthetic — the project's real `R` belongs to its built APK — so `@+id/name` becomes a
     * stable id derived from the name. Sibling constraints therefore work, which is what the layout
     * is actually describing.
     */
    private fun applyConstraints(parent: DesignElement, layout: ConstraintLayout) {
        val set = ConstraintSet()
        set.clone(layout)
        parent.children.forEach { child ->
            val view = views[child] ?: return@forEach
            val id = view.id.takeIf { it != View.NO_ID } ?: return@forEach
            child.attributes.forEach { attr ->
                if (!attr.local.startsWith("layout_constraint")) return@forEach
                val target = if (attr.value == "parent") ConstraintSet.PARENT_ID else viewId(attr.value)
                when (attr.local) {
                    "layout_constraintStart_toStartOf" -> set.connect(id, ConstraintSet.START, target, ConstraintSet.START)
                    "layout_constraintStart_toEndOf" -> set.connect(id, ConstraintSet.START, target, ConstraintSet.END)
                    "layout_constraintEnd_toEndOf" -> set.connect(id, ConstraintSet.END, target, ConstraintSet.END)
                    "layout_constraintEnd_toStartOf" -> set.connect(id, ConstraintSet.END, target, ConstraintSet.START)
                    "layout_constraintLeft_toLeftOf" -> set.connect(id, ConstraintSet.LEFT, target, ConstraintSet.LEFT)
                    "layout_constraintLeft_toRightOf" -> set.connect(id, ConstraintSet.LEFT, target, ConstraintSet.RIGHT)
                    "layout_constraintRight_toRightOf" -> set.connect(id, ConstraintSet.RIGHT, target, ConstraintSet.RIGHT)
                    "layout_constraintRight_toLeftOf" -> set.connect(id, ConstraintSet.RIGHT, target, ConstraintSet.LEFT)
                    "layout_constraintTop_toTopOf" -> set.connect(id, ConstraintSet.TOP, target, ConstraintSet.TOP)
                    "layout_constraintTop_toBottomOf" -> set.connect(id, ConstraintSet.TOP, target, ConstraintSet.BOTTOM)
                    "layout_constraintBottom_toBottomOf" -> set.connect(id, ConstraintSet.BOTTOM, target, ConstraintSet.BOTTOM)
                    "layout_constraintBottom_toTopOf" -> set.connect(id, ConstraintSet.BOTTOM, target, ConstraintSet.TOP)
                    "layout_constraintHorizontal_bias" ->
                        attr.value.toFloatOrNull()?.let { set.setHorizontalBias(id, it) }
                    "layout_constraintVertical_bias" ->
                        attr.value.toFloatOrNull()?.let { set.setVerticalBias(id, it) }
                }
            }
        }
        set.applyTo(layout)
    }

    /** Assign the synthetic id so sibling constraints can name this view. */
    fun assignIds() {
        // Two passes on purpose: every view needs its id before any constraint can name it, and a
        // constraint routinely points at a sibling that appears later in the file.
        views.forEach { (element, view) ->
            view.id = element.value("id")?.let { viewId(it) } ?: View.generateViewId()
        }
        views.forEach { (element, view) ->
            if (view is ConstraintLayout) applyConstraints(element, view)
        }
    }

    private fun size(value: String?): Int = when {
        value == null -> ViewGroup.LayoutParams.WRAP_CONTENT
        value == "match_parent" || value == "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
        value == "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
        value == "0dp" -> 0
        else -> px(value) ?: ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun px(value: String?): Int? =
        value?.let { resources.dimension(it, density)?.toInt() }

    private fun typefaceStyle(value: String): Int {
        var style = Typeface.NORMAL
        if (value.contains("bold")) style = style or Typeface.BOLD
        if (value.contains("italic")) style = style or Typeface.ITALIC
        return style
    }

    private fun gravity(value: String): Int = value.split('|').fold(0) { acc, part ->
        acc or when (part.trim()) {
            "center" -> Gravity.CENTER
            "center_horizontal" -> Gravity.CENTER_HORIZONTAL
            "center_vertical" -> Gravity.CENTER_VERTICAL
            "start", "left" -> Gravity.START
            "end", "right" -> Gravity.END
            "top" -> Gravity.TOP
            "bottom" -> Gravity.BOTTOM
            else -> 0
        }
    }

    /** A hairline box, drawn over a view so its bounds are visible whatever it contains. */
    private class BoundsDrawable(width: Float, tint: Int) : android.graphics.drawable.Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width
            color = tint
        }
        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds.left + 0.5f, bounds.top + 0.5f, bounds.right - 0.5f, bounds.bottom - 0.5f, paint)
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        /** A stable id per `@id/name`, so two references to the same name agree. */
        fun viewId(ref: String): Int {
            val name = ref.substringAfterLast('/')
            // Positive and well away from the framework's own ids.
            return 0x7F000000 or (name.hashCode() and 0x00FFFFFF)
        }
    }

    /**
     * Stands in for a widget whose class is not here — a Material component, a project's own view.
     *
     * Draws its own name because the user needs to know *which* box this is, and dashed because the
     * one thing it must not do is look like a real rendering of the widget.
     */
    class PlaceholderView(context: Context, private val label: String) : ViewGroup(context) {

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * context.resources.displayMetrics.density
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            color = Color.parseColor("#7F8B93A3")
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#B38B93A3")
            textSize = 12f * context.resources.displayMetrics.density
            textAlign = Paint.Align.CENTER
        }

        init { setWillNotDraw(false) }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            // Big enough to be seen and tapped when the layout leaves it to wrap_content.
            val min = (48 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(
                resolveSize(min.coerceAtLeast(text.measureText(label).toInt() + min), widthSpec),
                resolveSize(min, heightSpec),
            )
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit

        override fun onDraw(canvas: Canvas) {
            val inset = stroke.strokeWidth
            canvas.drawRect(inset, inset, width - inset, height - inset, stroke)
            canvas.drawText(label, width / 2f, height / 2f + text.textSize / 3f, text)
        }
    }
}
