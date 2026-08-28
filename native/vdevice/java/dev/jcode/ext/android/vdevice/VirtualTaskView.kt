package dev.jcode.ext.android.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.jcode.ext.android.R

/**
 * What the device has been running, most recent first.
 *
 * A phone's recents list is the activity manager's; this device has no activity manager to ask, so
 * it keeps its own. Package names rather than apps, because an app can be uninstalled between being
 * run and being looked for and a stale [VirtualDeviceApp] would be a card that opens nothing.
 *
 * Session-scoped by construction: everything on the device lives in JCode's cache and is emptied at
 * every start, so a recents list that outlived that would be a list of apps that are no longer
 * installed.
 */
internal object VirtualTasks {

    private const val LIMIT = 8

    private val recent = mutableListOf<String>()

    /**
     * Record [packageName] as the most recent task.
     *
     * The home screen is refused. It is an app on this device like any other and it is running more
     * of the time than anything else, so it would sit permanently at the top of the list — and a
     * card that takes you home is a card that duplicates the button next to it. A phone's recents
     * does not list its launcher either, and for the same reason.
     */
    @Synchronized
    fun ran(packageName: String) {
        if (packageName.isBlank() || packageName == DeviceIntents.LAUNCHER_PACKAGE) return
        recent.remove(packageName)
        recent.add(0, packageName)
        while (recent.size > LIMIT) recent.removeAt(recent.lastIndex)
    }

    /** Forget one — what dismissing its card means. */
    @Synchronized
    fun forget(packageName: String) {
        recent.remove(packageName)
    }

    @Synchronized
    fun clear() = recent.clear()

    /**
     * The recent apps that are still installed, most recent first.
     *
     * Filtered against what is actually on the device rather than trusted, so `adb uninstall` of a
     * recent app leaves a shorter list instead of a card that cannot be opened.
     */
    @Synchronized
    fun list(context: Context): List<LauncherApp> {
        val installed = runCatching { VirtualLauncher.load(context) }.getOrDefault(emptyList())
        val byPackage = installed.associateBy { it.app.packageName }
        return recent.mapNotNull { byPackage[it] }
    }
}

/**
 * The device's task view: what has been running, as cards, over whatever is on the screen.
 *
 * The apps behind the cards are alive. Home stops an app rather than closing it and keeps its back
 * stack, so a card here returns to the screen that app was on — which is what a card in recents has
 * always meant, and what makes this a switch rather than a fresh start. Only an app that has been
 * force-stopped, or that was running before the device last restarted, has to be started again.
 *
 * Drawn inside the device's own container like [VirtualStatusBar] and [VirtualNavigationBar], so
 * `screencap` shows it, `uiautomator dump` lists it under [R.id.vdevice_task_view], and a tap from
 * `input tap` lands on a card.
 */
@SuppressLint("ViewConstructor")
internal class VirtualTaskView(
    context: Context,
    private val onOpen: (VirtualDeviceApp) -> Unit,
    private val onDismiss: (VirtualDeviceApp) -> Unit,
) : FrameLayout(context) {

    private val cards = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
    }

    private val empty = TextView(context).apply {
        text = "No recent apps"
        setTextColor(MUTED)
        textSize = 13f
        gravity = Gravity.CENTER
        visibility = GONE
    }

    init {
        id = R.id.vdevice_task_view
        setBackgroundColor(SCRIM)
        // Tapping the scrim closes it, the way a phone's does. The cards consume their own taps, so
        // this only ever fires on the space between them.
        isClickable = true
        setOnClickListener { hide() }

        addView(
            HorizontalScrollView(context).apply {
                // Fills the viewport so the row can CENTRE itself: a WRAP-width row inside a scroll
                // view is laid out from the left edge whatever its gravity says, and one card on a
                // wide screen then sits in the corner looking like a mistake. With the row filling
                // the viewport its own gravity is what places the cards, and a row too wide to fit
                // still scrolls.
                isFillViewport = true
                clipToPadding = false
                addView(cards, LayoutParams(MATCH, MATCH))
            },
            LayoutParams(MATCH, dp(CARD_HEIGHT_DP)).apply { gravity = Gravity.CENTER_VERTICAL },
        )
        addView(empty, LayoutParams(MATCH, WRAP).apply { gravity = Gravity.CENTER })
        visibility = GONE
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    /** Rebuild from what has actually been run, then show. Cheap: there are at most eight cards. */
    fun show() {
        val recent = VirtualTasks.list(context)
        cards.removeAllViews()
        recent.forEach { cards.addView(card(it)) }
        empty.visibility = if (recent.isEmpty()) VISIBLE else GONE
        visibility = VISIBLE
        // NOT bringToFront(). The container adds this deliberately BELOW the device's status and
        // navigation bars — recents is a screen you leave by pressing Home or Recents again, so a
        // scrim over those buttons would be a screen with no way out but its own scrim. Raising
        // itself here put it back over both of them and dimmed the chrome it must not cover.
    }

    fun hide() {
        visibility = GONE
    }

    fun toggle() {
        if (isOpen) hide() else show()
    }

    private fun card(entry: LauncherApp): View {
        val app = entry.app
        val label = TextView(context).apply {
            text = app.label
            setTextColor(FOREGROUND)
            textSize = 12f
            maxLines = 1
            gravity = Gravity.CENTER
        }
        val icon = ImageView(context).apply {
            entry.icon?.let(::setImageDrawable)
        }
        val dismiss = TextView(context).apply {
            text = "✕"
            setTextColor(MUTED)
            textSize = 13f
            gravity = Gravity.CENTER
            // Padded rather than stretched. This used to be MATCH_PARENT across the card's top edge,
            // which made the whole strip above the icon a close button — a tap aimed at the app it
            // labels threw the app away instead.
            // Generous padding rather than a bigger glyph: this is the whole touch target, and a
            // 13sp character on a pane scaled to a third of its size is a very small thing to hit.
            setPadding(dp(14f), dp(6f), dp(10f), dp(10f))
            contentDescription = "Close ${app.label}"
            setOnClickListener {
                VirtualTasks.forget(app.packageName)
                onDismiss(app)
                show()
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            contentDescription = app.label
            isClickable = true
            setOnClickListener { onOpen(app) }
            addView(
                dismiss,
                LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END },
            )
            addView(
                icon,
                LinearLayout.LayoutParams(dp(40f), dp(40f)).apply { topMargin = dp(2f) },
            )
            addView(
                label,
                LinearLayout.LayoutParams(dp(76f), WRAP).apply { topMargin = dp(6f) },
            )
            // Set here rather than by the parent: the card is built before it is added, so it has no
            // parent to take params from yet.
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8f) }
        }
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val WRAP = LayoutParams.WRAP_CONTENT

        /** Dark enough to read cards against, light enough to see the app still underneath. */
        val SCRIM = Color.argb(0xE0, 0x0E, 0x0E, 0x10)
        val CARD = Color.argb(0xFF, 0x24, 0x24, 0x28)
        const val FOREGROUND = Color.WHITE
        val MUTED = Color.argb(0xFF, 0x9E, 0x9E, 0xA4)

        const val CARD_HEIGHT_DP = 132f
    }
}
