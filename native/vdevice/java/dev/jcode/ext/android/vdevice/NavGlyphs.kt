package dev.jcode.ext.android.vdevice

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * The three navigation glyphs — back, home, recents — drawn rather than shipped as drawables.
 *
 * One implementation because the device shows this bar in two quite different ways and they must not
 * drift: [VirtualNavigationBar] is a real `View` over a running guest, and [VirtualLauncher] paints
 * the home screen straight onto the device's surface. Drawn rather than inflated so the shapes stay
 * sharp at whatever density the screen profile is pretending to be, and so the pack carries no
 * drawable whose only job is to be a triangle.
 *
 * The shapes are the platform's: a back-pointing triangle, a home circle, a recents square.
 */
internal object NavGlyphs {

    /** The bar's height in dp, shared by both presentations so the home screen and an app agree. */
    const val BAR_DP = 32f

    val BACKGROUND = Color.BLACK
    const val FOREGROUND = Color.WHITE

    /** Which button a point in the bar falls on. */
    enum class Button { Back, Home, Recents }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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

    /**
     * Draw one glyph centred on [cx], [cy].
     *
     * [unit] is the glyph's nominal radius; everything else is a ratio of it, so the three stay in
     * proportion at any size.
     */
    @Synchronized
    fun draw(canvas: Canvas, button: Button, cx: Float, cy: Float, unit: Float, alpha: Int = 255) {
        stroke.alpha = alpha
        fill.alpha = alpha
        stroke.strokeWidth = unit * 0.32f
        when (button) {
            Button.Back -> {
                path.reset()
                path.moveTo(cx + unit * 0.55f, cy - unit)
                path.lineTo(cx - unit * 0.75f, cy)
                path.lineTo(cx + unit * 0.55f, cy + unit)
                path.close()
                canvas.drawPath(path, fill)
            }

            Button.Home -> canvas.drawCircle(cx, cy, unit * 0.85f, stroke)

            Button.Recents -> {
                val half = unit * 0.75f
                canvas.drawRect(cx - half, cy - half, cx + half, cy + half, stroke)
            }
        }
    }

    /**
     * Paint the whole bar across the bottom of a [width]×[height] screen.
     *
     * Used by the home screen, which has no view hierarchy to hang a bar on — it is a canvas the
     * launcher paints, and a device whose navigation appeared only once an app was running would be
     * a device that looked like two different devices.
     */
    fun drawBar(canvas: Canvas, width: Int, height: Int, density: Float, alpha: Int = 255) {
        val bar = barHeight(density)
        if (bar <= 0f || width <= 0 || height <= 0) return
        val top = height - bar
        val background = Paint().apply {
            color = BACKGROUND
            this.alpha = alpha
        }
        canvas.drawRect(0f, top, width.toFloat(), height.toFloat(), background)
        val cy = top + bar / 2f
        val unit = bar * 0.22f
        Button.entries.forEachIndexed { index, button ->
            draw(canvas, button, centreX(width, index), cy, unit, alpha)
        }
    }

    /** Which button [x], [y] is on, or null for a point outside the bar. */
    fun hit(width: Int, height: Int, density: Float, x: Float, y: Float): Button? {
        val bar = barHeight(density)
        if (bar <= 0f || y < height - bar || y > height) return null
        val third = width / 3f
        if (third <= 0f) return null
        return Button.entries.getOrNull((x / third).toInt().coerceIn(0, Button.entries.lastIndex))
    }

    fun barHeight(density: Float): Float = BAR_DP * density

    /** AOSP's order, left to right: the device mirrors Android rather than a vendor's rearrangement. */
    private fun centreX(width: Int, index: Int): Float = width * (index * 2 + 1) / 6f
}
