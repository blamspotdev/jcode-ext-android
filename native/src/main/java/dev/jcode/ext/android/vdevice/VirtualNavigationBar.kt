package dev.jcode.ext.android.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.jcode.ext.android.R

/**
 * The device's navigation bar: Back, Home and Recents, along the bottom of its screen.
 *
 * **Why it is a strip of the device and not three more buttons on JCode's toolbar.** Back used to be
 * exactly that, and it was in the wrong window. The tab's control bar is JCode's chrome, so
 * `screencap` of the device did not show it, `uiautomator dump` did not list it, and `input tap`
 * could not reach it — an agent driving the device could see an app that wanted a Back press and had
 * no way to press it. The same argument the phone's IME lost to [VirtualKeyboard], and it loses here
 * for the same reason: anything a person can press on the device, a driver must be able to press
 * too.
 *
 * Everything here is drawn in code rather than from drawables so that the glyphs stay crisp at
 * whatever density the screen profile is pretending to be — see [VirtualScreenOptions], where a
 * tablet at 320dpi and a phone at 420dpi are one dropdown apart.
 *
 * The ids are load-bearing, not decoration: a phone's are addressable the same way
 * (`com.android.systemui:id/back`), and without them an agent pressing Home would be reading a
 * screenshot for whichever of three identical circles is the middle one.
 */
@SuppressLint("ViewConstructor")
internal class VirtualNavigationBar(
    context: Context,
    private val onBack: () -> Unit,
    private val onHome: () -> Unit,
    private val onRecents: () -> Unit,
) : FrameLayout(context) {

    /**
     * What the foreground app has made of the bar.
     *
     * A full-screen app takes the navigation bar with it exactly as it takes the status bar — see
     * [GuestWindow.statusBarStyleOf]. Held rather than recomputed per layout pass so the container
     * can tell a real change from the hundred times a frame it asks.
     */
    var hidden: Boolean = false
        private set

    private val back = NavGlyph(context, NavGlyph.Kind.Back).apply {
        id = R.id.vdevice_nav_back
        contentDescription = "Back"
        setOnClickListener { onBack() }
    }

    private val home = NavGlyph(context, NavGlyph.Kind.Home).apply {
        id = R.id.vdevice_nav_home
        contentDescription = "Home"
        setOnClickListener { onHome() }
    }

    private val recents = NavGlyph(context, NavGlyph.Kind.Recents).apply {
        id = R.id.vdevice_nav_recents
        contentDescription = "Task view"
        setOnClickListener { onRecents() }
    }

    private val bar = LinearLayout(context).apply {
        id = R.id.vdevice_nav_bar
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(BAR_BACKGROUND)
        // AOSP's order, left to right. The device mirrors Android rather than any one manufacturer's
        // rearrangement of it, so an app developer sees the arrangement the platform ships with.
        addView(back, weighted())
        addView(home, weighted())
        addView(recents, weighted())
    }

    init {
        addView(
            bar,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(BAR_DP)).apply {
                gravity = Gravity.BOTTOM
            },
        )
    }

    /** A full-screen app takes the bar with it; anything else gets it back. */
    fun setHidden(hidden: Boolean) {
        if (this.hidden == hidden) return
        this.hidden = hidden
        bar.visibility = if (hidden) GONE else VISIBLE
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * One navigation glyph, drawn rather than inflated.
     *
     * The three shapes are the platform's: a back-pointing triangle, a home circle, a recents
     * square. Drawing them keeps them sharp at any density and keeps the pack from carrying three
     * more drawables whose only job is to be a triangle.
     */
    private class NavGlyph(context: Context, private val kind: Kind) : View(context) {

        enum class Kind { Back, Home, Recents }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FOREGROUND
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FOREGROUND
            style = Paint.Style.FILL
        }

        private val path = Path()

        init {
            isClickable = true
            isFocusable = true
            // The platform's borderless ripple, resolved from the theme rather than named as a
            // resource: `selectableItemBackgroundBorderless` is an *attribute*, and passing the attr
            // id to setBackgroundResource sets a background that is not one. Resolved defensively
            // because the container's theme is the device's, not an AppCompat activity's.
            val ripple = TypedValue()
            val found = context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                ripple,
                true,
            )
            if (found && ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        }

        /** No ripple to fall back on: dim the glyph while it is held, so a press still shows. */
        override fun setPressed(pressed: Boolean) {
            super.setPressed(pressed)
            if (background == null) alpha = if (pressed) 0.55f else 1f
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val unit = minOf(width, height) * 0.22f
            paint.strokeWidth = unit * 0.32f
            when (kind) {
                // A triangle pointing the way it takes you.
                Kind.Back -> {
                    path.reset()
                    path.moveTo(cx + unit * 0.55f, cy - unit)
                    path.lineTo(cx - unit * 0.75f, cy)
                    path.lineTo(cx + unit * 0.55f, cy + unit)
                    path.close()
                    canvas.drawPath(path, fill)
                }

                Kind.Home -> canvas.drawCircle(cx, cy, unit * 0.85f, paint)

                Kind.Recents -> {
                    val half = unit * 0.75f
                    canvas.drawRect(cx - half, cy - half, cx + half, cy + half, paint)
                }
            }
        }

        private companion object {
            const val FOREGROUND = Color.WHITE
        }
    }

    companion object {
        /**
         * The bar's height in dp.
         *
         * A phone's three-button bar is 48dp. This one is shorter because the device is a tab on a
         * phone rather than the phone, and every dp it takes is one the app being tested does not
         * have — the same trade [VirtualStatusBar.BAR_DP] makes at the top.
         */
        const val BAR_DP = 32f

        private const val BAR_BACKGROUND = Color.BLACK
    }
}
