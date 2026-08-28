package dev.jcode.ext.android.vdevice

import androidx.compose.runtime.mutableStateOf
import kotlin.math.roundToInt

/**
 * A screen the device can pretend to be.
 *
 * [widthDp] and [heightDp] are the *portrait* dimensions; [rotated] swaps them. A null size means
 * "whatever the tab is", which is what the device did before there was anything to choose and is
 * still the default — it is the only profile that shows an app at the size the person is actually
 * looking at.
 *
 * [densityDpi] is part of the profile and not a detail of it. A 360×640dp screen at 320dpi and the
 * same screen at 480dpi select different drawables and lay out different line counts, and testing
 * one while believing you tested the other is exactly the class of bug this feature exists to find.
 */
internal data class DeviceProfile(
    val label: String,
    val widthDp: Int?,
    val heightDp: Int?,
    val densityDpi: Int?,
) {
    val isNative: Boolean get() = widthDp == null || heightDp == null

    /**
     * The device's size in real pixels, given the tab's and the phone's own density.
     *
     * [available] is what the tab can give it. A native profile takes that unchanged; a fixed one
     * ignores it entirely and is scaled to fit afterwards — a preset that quietly shrank to the tab
     * would be the letterboxing this feature was chosen over.
     */
    fun pixels(available: Pair<Int, Int>, phoneDensityDpi: Int, rotated: Boolean): Pair<Int, Int> {
        val width = widthDp ?: return available
        val height = heightDp ?: return available
        val dpi = densityDpi ?: phoneDensityDpi
        val scale = dpi / 160f
        val (w, h) = if (rotated) height to width else width to height
        return (w * scale).roundToInt().coerceAtLeast(1) to (h * scale).roundToInt().coerceAtLeast(1)
    }

    /** What the dropdown shows beside the name. */
    fun subtitle(): String = when {
        isNative -> "the tab"
        else -> "${widthDp}×${heightDp}dp · ${densityDpi}dpi"
    }
}

/**
 * The device's screen options: which screen it is pretending to be, and which way up.
 *
 * **Real, not letterboxed.** Choosing a profile changes what the guest is *told*, not merely how
 * much of the tab it is painted into: [GuestWindow.applySize] rewrites its `DisplayMetrics` and its
 * `Configuration`, so resource qualifiers reselect, `smallestScreenWidthDp` moves, the
 * `screenLayout` size bucket moves with it and `onConfigurationChanged` fires. An app laid out for a
 * small phone really does reflow. The tab then scales the whole device down to fit, the way an
 * emulator window does.
 *
 * Held here rather than in the composition for the same reason the running APK is: the editor pane
 * tears a page down when another tab is selected, and a device that forgot which screen it was
 * every time somebody looked at a file would be useless.
 */
internal object VirtualScreenOptions {

    /**
     * The presets, matching the layout designer's so that a layout checked in the designer and an
     * app run on the device are checked against the same screens. The first is the default and is
     * the behaviour the device had before this existed.
     */
    val PROFILES: List<DeviceProfile> = listOf(
        DeviceProfile("Fit the tab", null, null, null),
        DeviceProfile("Phone", 411, 891, 420),
        DeviceProfile("Phone (small)", 360, 640, 320),
        DeviceProfile("Foldable", 673, 841, 420),
        DeviceProfile("Tablet", 800, 1280, 320),
    )

    val profile = mutableStateOf(PROFILES.first())

    /** Landscape. Ignored by a native profile, which is whatever shape the tab already is. */
    val rotated = mutableStateOf(false)

    /** True while the device is showing something other than the tab's own shape. */
    val isOverridden: Boolean get() = !profile.value.isNative

    fun select(next: DeviceProfile) {
        profile.value = next
        // A fixed profile that was left rotated from a previous one keeps its rotation; a native one
        // has nothing to rotate, so the flag would only be a switch that did nothing.
        if (next.isNative) rotated.value = false
    }

    fun rotate() {
        if (isOverridden) rotated.value = !rotated.value
    }

    /** The device's pixel size for the tab's [available] space and this phone's [phoneDensityDpi]. */
    fun pixels(available: Pair<Int, Int>, phoneDensityDpi: Int): Pair<Int, Int> =
        profile.value.pixels(available, phoneDensityDpi, rotated.value)

    /** The density the guest should be told it has, or null to keep the phone's. */
    fun densityDpi(): Int? = profile.value.densityDpi
}
