package dev.jcode.ext.android.vdevice

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import dev.blamspot.jcode.design.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blamspot.jcode.core.distro.WorkspaceHostPaths
import dev.blamspot.jcode.ext.api.NativeHost
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.ManagerNoticeCard
import dev.blamspot.jcode.design.ManagerSectionCard
import dev.blamspot.jcode.design.SettingsTextFieldRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long the device's controls stay up once they are left alone. Long enough to reach a second
 * control without the bar going out from under the finger, short enough that the app under test is
 * not covered while it is being watched.
 */
private const val TOOLBAR_IDLE_COLLAPSE_MS = 4_000L

/** How many times the pack's settings are asked for before the device gives up and uses its own default. */
private const val SETTINGS_READ_ATTEMPTS = 6
private const val SETTINGS_READ_RETRY_MS = 400L

/** The collapsed handle: a fifth of the device's width, sized like a sheet grabber. */
private const val HANDLE_WIDTH_FRACTION = 0.2f
private val HANDLE_THICKNESS = 4.dp
private val HANDLE_TOUCH_HEIGHT = 24.dp

/**
 * Editor tab holding JCode's virtual device — a screen the IDE owns, that an app can be put on and
 * taken off again.
 *
 * The device is the tab, not the app: with nothing running it is a live blank screen that `adb` can
 * install to, launch onto and `screencap`, and stopping an app returns it to that rather than
 * closing anything. Whatever is running is built and composited by the `:guest` process
 * ([EmbeddedGuest]) and shown here through a `SurfaceControlViewHost` surface package.
 *
 * Embedding can fail for reasons this tab cannot fix — the window may not be hardware accelerated,
 * and the out-of-band activity creation the container depends on rests on non-SDK members — so a
 * failure is reported on the device's own screen rather than worked around. There is nowhere else to
 * run an app: a guest is the tab, and full screen means full screen *within* it.
 *
 * One-off results — an app that closed itself — go to [onSnackbar] rather than to a band along the
 * bottom: they are over once they have been read, and the device's screen is the scarce thing here. What a running guest could not do is not one-off, so it stays reachable from the
 * control bar for as long as that guest is up.
 */
@Composable
internal fun AppSandboxPage(
    onSnackbar: (String) -> Unit,
    /**
     * The workbench, for the pack's own settings. Null where the page is drawn without one — the
     * device then opens on whatever it was already showing, which is the same answer the setting's
     * own default gives.
     */
    host: NativeHost? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val session = remember { AppSandbox.session(context) }
    val status by session.status.collectAsStateWithLifecycle()

    // isHardwareAccelerated only answers once the view is attached, so it is read after a frame
    // rather than on first composition.
    var hardwareAccelerated by remember(view) { mutableStateOf(view.isHardwareAccelerated) }
    LaunchedEffect(view) {
        withFrameNanos { }
        hardwareAccelerated = view.isHardwareAccelerated
    }
    var apkPath by AppSandbox.apkPath
    val activityClass by AppSandbox.activityClass
    var running by AppSandbox.running
    var size by remember { mutableStateOf(IntSize.Zero) }
    var surfaceView by remember { mutableStateOf<AppSandboxSurfaceView?>(null) }
    var installOpen by remember { mutableStateOf(false) }
    val surface by session.surface.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // The pack's `defaultDeviceScreen` setting, read once and applied only while nobody has picked a
    // screen for this device — see VirtualScreenOptions.applyDefault. `config()` suspends, so it
    // cannot be read where the profile is used.
    LaunchedEffect(host) {
        val workbench = host ?: return@LaunchedEffect
        // Retried, because the answer is not available the moment this page composes: `config.all`
        // is resolved against the workbench's list of installed extensions, and the device tab can
        // be on screen before that list has loaded — the first read comes back empty rather than
        // wrong. Bounded, and it stops as soon as there is an answer.
        repeat(SETTINGS_READ_ATTEMPTS) { attempt ->
            val config = runCatching { workbench.config() }.getOrNull().orEmpty()
            if (config.isNotEmpty()) {
                VirtualScreenOptions.applyDefault(config[VirtualScreenOptions.DEFAULT_SETTING_KEY])
                return@LaunchedEffect
            }
            if (attempt < SETTINGS_READ_ATTEMPTS - 1) delay(SETTINGS_READ_RETRY_MS)
        }
    }

    // A change made while the device is open should reach it, not wait for the next one.
    DisposableEffect(host) {
        val handle = host?.onEvent { name, _ ->
            if (name == "config") {
                scope.launch {
                    runCatching { host.config()[VirtualScreenOptions.DEFAULT_SETTING_KEY] }
                        .getOrNull()
                        ?.let(VirtualScreenOptions::applyDefault)
                }
            }
        }
        onDispose { runCatching { handle?.close() } }
    }

    // The device's launcher lives on the surface, not in this composition — see VirtualLauncher. All
    // that is left here is reading what is installed and handing it over, and hosting the one menu
    // that is JCode's rather than the device's.
    val revision = VirtualDeviceApps.revision.intValue
    var home by remember { mutableStateOf<List<LauncherApp>?>(null) }
    var menuFor by remember { mutableStateOf<Pair<VirtualDeviceApp, Offset>?>(null) }
    var detailsFor by remember { mutableStateOf<VirtualDeviceApp?>(null) }
    var permissionsFor by remember { mutableStateOf<VirtualDeviceApp?>(null) }
    /** The home screen's task view — the device is not running anything, so this is Compose's. */
    var homeTasksOpen by remember { mutableStateOf(false) }
    LaunchedEffect(revision, running) {
        home = if (running) null else withContext(Dispatchers.IO) { VirtualLauncher.load(context) }
    }
    LaunchedEffect(home, size, surfaceView) {
        surfaceView?.showHome(home)
        if (home != null) menuFor = null
    }

    // A guest hands its screen over as a child `SurfacePackage`, and a `SurfaceView` has no way to
    // give one back: the only thing that releases it is the view detaching. That is invisible while
    // the guest is alive to take its own layer down, and very visible when it is not — a process
    // that ends outright (the Tasks panel's Stop, a crash) leaves its last frame on a layer sitting
    // over everything the container draws, so the device looked like it was still showing the app it
    // had just been stopped from. Measured. So the view is rebuilt as the screen goes.
    var generation by remember { mutableIntStateOf(0) }
    var adopted by remember { mutableStateOf(false) }
    LaunchedEffect(surface) {
        if (surface != null) {
            adopted = true
        } else if (adopted) {
            adopted = false
            generation++
        }
    }

    // The device's own pixel size is what the container is asked for — which keeps forwarded touches
    // in the guest's coordinates with no mapping at all. That used to be the tab's size and now is
    // whatever screen the device is pretending to be; see VirtualScreenOptions.
    //
    // Keyed on the profile as well as on the size, because the two are not the same key: a profile
    // change usually moves the pixel size and would be caught anyway, but a change of *density* at
    // the same size would not be, and that is a change the guest has to be told about.
    // Whether the control bar is currently lying across the top of the tab, which is the one place
    // the device menu also wants. See [HomeChrome].

    val screenProfile by VirtualScreenOptions.profile
    LaunchedEffect(size, running, apkPath, surfaceView, screenProfile) {
        if (running) {
            session.ensureStarted(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
        }
    }
    LaunchedEffect(surface, surfaceView) {
        surface?.let { surfaceView?.adopt(it) }
    }
    // An app that closes itself leaves the device, not the tab: the screen goes back to blank — the
    // same place the Stop button lands on — and why it went is said once, on the way past.
    LaunchedEffect(status) {
        (status as? SandboxStatus.Stopped)?.let {
            onSnackbar(it.reason)
            session.close()
            running = false
        }
    }

    /** Puts an app from the device's own launcher on its screen — a tap on an icon. */
    fun open(app: VirtualDeviceApp) {
        installOpen = false
        permissionsFor = null
        // The launcher runs an app as the device would: its own MAIN/LAUNCHER activity, whatever
        // the last run happened to name.
        AppSandbox.activityClass.value = null
        apkPath = app.apkPath
        running = true
    }

    // Clearing `running` is the whole teardown: the home effect below reloads what is installed and
    // repaints the surface, so there is one place that decides what an idle device shows.
    fun stop() {
        session.close()
        running = false
    }

    fun uninstall(app: VirtualDeviceApp) {
        if (app.apkPath == apkPath) stop()
        scope.launch(Dispatchers.IO) { VirtualDeviceApps.uninstall(context, app.packageName) }
        onSnackbar("Uninstalled ${app.label}.")
    }

    // Re-set rather than captured once: the surface outlives every composition that reads these.
    LaunchedEffect(surfaceView) {
        surfaceView?.onLaunchApp = { open(it) }
        surfaceView?.onAppMenu = { app, x, y -> menuFor = app to Offset(x, y) }
        // The home screen's own navigation bar. Back and Home are inert here for the reason they are
        // on a phone's launcher — there is nothing behind it and it IS home — and Recents opens the
        // same list the running device's task view shows.
        surfaceView?.onNavButton = { button ->
            if (button == NavGlyphs.Button.Recents) homeTasksOpen = !homeTasksOpen
        }
    }

    BoxWithConstraints(modifier) {
        val pane = rememberPaneLayout(maxWidth, maxHeight)
        DeviceScreen(
            pane = pane,
            session = session,
            status = status,
            running = running,
            generation = generation,
            onSurface = { surfaceView = it },
            onSurfaceGone = { gone -> if (surfaceView === gone) surfaceView = null },
            onSized = { width, height -> size = IntSize(width, height) },
            onRetry = {
                session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
            },
            onDismiss = { stop() },
            onInstall = { installOpen = true },
            modifier = Modifier.fillMaxSize(),
        )

        // Held over the icon that was long-pressed. A menu is JCode's, not the device's, so it is
        // composed here rather than drawn onto the screen a capture reads.
        // The home screen's task view. A Compose sheet rather than the running device's
        // VirtualTaskView, because that one is a view inside the guest's container and with nothing
        // running there is no container — see VirtualNavigationBar for the pair.
        if (homeTasksOpen && !running) {
            val recents = remember(revision, homeTasksOpen) { VirtualTasks.list(context) }
            HomeTaskView(
                tasks = recents,
                onOpen = { app ->
                    homeTasksOpen = false
                    open(app)
                },
                onClose = { homeTasksOpen = false },
            )
        }

        menuFor?.let { (app, at) ->
            val density = LocalDensity.current
            CompactContextMenu(
                expanded = true,
                onDismissRequest = { menuFor = null },
                offset = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) },
                listActions = buildList {
                    add(ContextAction(JCodeIcon.Run, "Open") { menuFor = null; open(app) })
                    add(ContextAction(JCodeIcon.Settings, "Manage permissions") {
                        menuFor = null
                        permissionsFor = app
                    })
                    add(ContextAction(JCodeIcon.Stop, "Force stop") {
                        menuFor = null
                        if (app.apkPath == apkPath) stop()
                        AppSandbox.forceStop(app.packageName)
                        onSnackbar("Force-stopped ${'$'}{app.label}.")
                    })
                    add(ContextAction(JCodeIcon.Help, "Details") { menuFor = null; detailsFor = app })
                    add(ContextAction(JCodeIcon.Clear, "Clear data") {
                        menuFor = null
                        scope.launch(Dispatchers.IO) { VirtualDeviceApps.clearData(context, app.packageName) }
                        onSnackbar("Cleared ${app.label}'s data.")
                    })
                    // The device's own apps have no Uninstall, the way a phone's stock camera and
                    // files have none: an app asking for a photo expects the device to have a
                    // camera, and a device you can leave in a state where it does not is a device
                    // that fails in a way nothing explains. See DeviceIntents.SYSTEM_PACKAGES.
                    if (!DeviceIntents.isSystem(app.packageName)) {
                        add(ContextAction(JCodeIcon.Delete, "Uninstall", destructive = true) {
                            menuFor = null
                            uninstall(app)
                        })
                    }
                },
            )
        }

        detailsFor?.let { app ->
            AppDetailsDialog(app = app, onDismiss = { detailsFor = null })
        }

        if (installOpen) {
            InstallSheet(
                hardwareAccelerated = hardwareAccelerated,
                apkPath = apkPath,
                onApkPathChange = {
                    apkPath = it
                    // The activity a launch named belongs to the APK it named, not to this one.
                    AppSandbox.activityClass.value = null
                },
                onInstall = { path ->
                    scope.launch {
                        withContext(Dispatchers.IO) { VirtualDeviceApps.installCopy(context, File(path)) }
                            .onSuccess {
                                installOpen = false
                                onSnackbar("Installed ${it.label} on ${VirtualIdentity.MODEL}.")
                            }
                            .onFailure { onSnackbar(it.message ?: "Could not install that APK.") }
                    }
                },
                onRunHere = {
                    installOpen = false
                    running = true
                },
                onClose = { installOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        } else if (permissionsFor != null) {
            permissionsFor?.let { app ->
                AppPermissionsSheet(
                    app = app,
                    onSnackbar = onSnackbar,
                    onClose = { permissionsFor = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (running) {
            // Every control on the bar acts on a running guest, and the home screen already names
            // the device — so with nothing running there is nothing for it to say.
            DeviceControls(
                pane = pane,
                caveat = (status as? SandboxStatus.Running)?.warning,
                // The device's own keyboard, not the phone's. It is also the way out of the one
                // bound the container has on noticing focus: a guest that moves it without any
                // input is only caught on the next event — see EmbeddedGuest.followFocus.
                onKeyboard = { scope.launch { session.ime("toggle") } },
                onInstall = { installOpen = true },
                onHardware = { SimulatedHardware.requestOpen() },
                onRestart = {
                    session.restart(apkPath, activityClass, size.width, size.height, surfaceView?.hostToken())
                },
                onStop = { stop() },
            )
        }
    }
}

/**
 * Where the device sits in its tab, and how much room is left beside it.
 *
 * A fixed screen profile makes the device a **pane**: a portrait phone in a landscape tab leaves
 * two wide empty columns, and the IDE's own chrome — the control bar and the home-screen buttons —
 * used to be laid out against the whole tab and so came down on top of the device. With a gutter
 * there is somewhere better for it to be, which is what [hasGutter] decides.
 */
private data class PaneLayout(
    /** The device's own size, in its own pixels. */
    val deviceSize: Pair<Int, Int>,
    /** What the pane is drawn at, so a device larger than the tab still fits. */
    val scale: Float,
    /** Free space on ONE side of the pane, in dp. The pane is centred, so both sides have this. */
    val sideGutterDp: Float,
) {
    /**
     * Whether the chrome should move out beside the device instead of lying over it.
     *
     * The threshold is a column wide enough for the control bar's icons and the home screen's
     * buttons to read as a column rather than as something clipped.
     */
    val hasGutter: Boolean get() = sideGutterDp >= GUTTER_MIN_DP
}

/** The gutter column's icon size, and the width its divider is pinned to. */
private const val GUTTER_ICON_DP = 34f

/** Narrower than this and a gutter is a margin, not somewhere to put things. */
private const val GUTTER_MIN_DP = 148f

@Composable
private fun rememberPaneLayout(maxWidth: Dp, maxHeight: Dp): PaneLayout {
    val density = LocalDensity.current
    // The *phone's* dpi, which is what a profile with no density of its own inherits. Compose's
    // Density carries a scale factor and a font scale, not a dpi bucket, so this comes from the
    // display metrics rather than from `density`.
    val phoneDensityDpi = LocalContext.current.resources.displayMetrics.densityDpi
    val available = with(density) { maxWidth.roundToPx() to maxHeight.roundToPx() }
    val profile by VirtualScreenOptions.profile
    val rotated by VirtualScreenOptions.rotated

    return remember(profile, rotated, available, phoneDensityDpi) {
        // The device's real pixel size. A native profile is the tab's, which is what the device
        // always used to be; a fixed one ignores the tab entirely and is scaled to fit.
        val deviceSize = VirtualScreenOptions.pixels(available, phoneDensityDpi)
        // Fit, never magnify: a 360dp phone on a tablet-sized tab is shown at 1:1 rather than blown
        // up into a soft rectangle, the same as an emulator window.
        val scale = if (deviceSize.first <= 0 || deviceSize.second <= 0) {
            1f
        } else {
            minOf(
                available.first.toFloat() / deviceSize.first,
                available.second.toFloat() / deviceSize.second,
                1f,
            )
        }
        val paneWidth = deviceSize.first * scale
        PaneLayout(
            deviceSize = deviceSize,
            scale = scale,
            sideGutterDp = ((available.first - paneWidth) / 2f / density.density).coerceAtLeast(0f),
        )
    }
}

/**
 * The device's screen. The `SurfaceView` is here whether or not an app is: it is what gives the
 * device its resolution and the host token a guest is embedded under, so it is created with the tab
 * and outlives every app put on it.
 */
@Composable
private fun DeviceScreen(
    pane: PaneLayout,
    session: AppSandboxSession,
    status: SandboxStatus,
    running: Boolean,
    /** Bumped when a guest's screen goes, to rebuild the view holding it — see the call site. */
    generation: Int,
    onSurface: (AppSandboxSurfaceView) -> Unit,
    onSurfaceGone: (AppSandboxSurfaceView) -> Unit,
    onSized: (Int, Int) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    /** False while the control bar is covering the corner the device menu lives in. */
    modifier: Modifier = Modifier,
) {
    // The surround is the EDITOR's background, not the device's wallpaper. It used to be the
    // wallpaper colour so the surface's edges never disagreed with it while being created — which
    // was right while the device filled the tab and is wrong now that it is a pane: the empty
    // columns beside a portrait device are part of the editor, and painting them in the device's
    // colour made the tab look like a device with its screen off. The wallpaper is kept directly
    // behind the surface instead, where the flash it was guarding against actually happens.
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val deviceSize = pane.deviceSize
        val scale = pane.scale
        val paneWidth = with(density) { (deviceSize.first * scale).toDp() }
        val paneHeight = with(density) { (deviceSize.second * scale).toDp() }

        // Exactly the pane, behind the surface: what the wallpaper colour was always for.
        Box(
            modifier = Modifier
                .size(paneWidth, paneHeight)
                .background(Color(VirtualWallpaper.BACKGROUND)),
        )

        key(generation) {
            // The view that is going announces *itself*, because a replacement is composed before
            // its predecessor is disposed: a plain `onSurface(null)` on the way out would arrive
            // after the new view had registered and leave the tab holding no screen at all — the
            // launcher then had nothing to paint on and the device came back empty. Measured, the
            // first time a rebuild happened here.
            val created = remember { mutableStateOf<AppSandboxSurfaceView?>(null) }
            AndroidView(
                factory = { context ->
                    AppSandboxSurfaceView(context, session) { width, height ->
                        onSized(width, height)
                        VirtualScreen.sized(width, height)
                    }.also {
                        created.value = it
                        onSurface(it)
                    }
                },
                // The scale is the VIEW's, not a graphicsLayer's, and that is not a style choice.
                // Compose's layer transform is applied when the guest's surface is composited but
                // NOT to the MotionEvent handed down to the view inside it: touches arrived
                // translated to the pane's top-left and never divided by the scale, so on a
                // half-size pane every tap landed roughly twice as far down the screen as the finger
                // — pressing one button and getting the one above it. Android's own View transform
                // is inverted by `ViewGroup.dispatchTransformedTouchEvent` on the way in, so setting
                // it here makes the drawing and the touches agree by construction.
                update = { view ->
                    view.scaleX = scale
                    view.scaleY = scale
                },
                // Laid out at the device's OWN size and then scaled, rather than filling the tab.
                // That is what makes the resize real: the view's size is what reaches
                // `onSizeChanged`, and from there the guest's DisplayMetrics — so the app measures
                // against the screen it was told it has, and the scale is only how large that screen
                // is drawn. Touches are unaffected: the framework maps them back through the view's
                // transform, so what arrives is already in device pixels.
                modifier = Modifier
                    // requiredSize, not size: `size` sets a *preferred* size that the parent's
                    // incoming max constraints still clamp, so a 1600x2560 tablet came out
                    // 1600x597 — the tab's own height — and the guest was told it was on a screen
                    // 299dp tall. requiredSize ignores the parent's constraints, which is the whole
                    // point here: the device is its own size and the scale below is what makes it
                    // fit.
                    .requiredSize(
                        width = with(density) { deviceSize.first.toDp() },
                        height = with(density) { deviceSize.second.toDp() },
                    ),
            )
            DisposableEffect(Unit) { onDispose { created.value?.let(onSurfaceGone) } }
        }

        // Nobody is looking at the device unless this is composed *and* JCode is in the foreground.
        // Both halves matter: switching editor tabs takes the composition away, and pressing Home
        // does not. Without either the guest ran at full tilt behind whatever the person had moved
        // on to — see GuestRuntime.pauseEmbedded.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, session) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> session.setVisible(true)
                    Lifecycle.Event.ON_STOP -> session.setVisible(false)
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            session.setVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
            onDispose {
                lifecycle.removeObserver(observer)
                session.setVisible(false)
            }
        }

        // Outside the `when`, and no longer conditional on nothing running. The device's resting
        // state used to be a blank screen with no guest; it is the launcher app now, so a menu shown
        // only while nothing ran would never be shown at all — and Install an app is only here.
        HomeChrome(
            onInstall = onInstall,
            onHardware = { SimulatedHardware.requestOpen() },
            modifier = Modifier.fillMaxSize(),
        )

        when {
            // The home screen itself is on the surface, drawn by VirtualLauncher — only the chrome
            // that does not belong to the device is composed over it.
            //
            // `running` is half the condition, and it is the half that was missing: Idle is also the
            // state of a device that is *not* starting anything, so a device resting on its home
            // screen said "Starting the app…" across it — for ever, and over a home screen that was
            // working perfectly. Somebody reading that reasonably concludes the device is hung.
            running && (status is SandboxStatus.Starting || status is SandboxStatus.Idle) ->
                ScreenMessage("Starting the app…")

            status is SandboxStatus.Failed -> ScreenFallback(
                message = status.message,
                onRetry = onRetry,
                onDismiss = onDismiss,
            )

            else -> Unit
        }
    }
}

/**
 * The only part of the home screen that is *not* the device: JCode's own affordances for putting an
 * app on it, and for the bench the device's hardware is set from.
 *
 * Everything the device itself shows — wallpaper, its name, the app icons, the "No app installed"
 * placeholder — is drawn onto the surface by [VirtualLauncher], so `adb shell screencap` answers
 * with it. These buttons are deliberately outside that: they are the IDE reaching onto the device,
 * the same as the control bar, and a capture must not show them as though they were part of the app
 * grid.
 */
@Composable
private fun HomeChrome(
    onInstall: () -> Unit,
    onHardware: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Buttons at the bottom, not a menu in the corner.
        //
        // As a corner affordance these were one tap away from being seen at all, and they shared the
        // status bar strip with the device's own wifi and signal icons -- which is why the strip had
        // to be given up whenever the control bar came over the top of it. The bottom centre is the
        // one place on a phone-shaped screen that no bar of ours and none of the device's own reach:
        // the control bar is at the top, the device's navigation bar is below this, and the
        // workbench's chrome pill is at the trailing corner.
        //
        // Shaped like the control bar because it *is* the same thing -- the IDE reaching onto the
        // device -- and two pieces of one toolset should not look like two toolsets. Floating rather
        // than full width so it costs the device's screen only what it occupies, which a bar spanning
        // the width would not.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // Above the device's own navigation bar, never on it: Back, Home and Recents are the
                // device's, and a button of ours sitting on them is a button the guest cannot be
                // tapped through.
                .padding(bottom = VirtualNavigationBar.BAR_DP.dp + Space.sm),
            shape = RoundedCornerShape(percent = 50),
            // Flat `surface`, like the control bar, and for the same reason: M3 tonal elevation
            // blends `primary` in, and JCode's primary is blue -- so the device's own chrome came out
            // blue-tinted while every panel around it stayed neutral grey.
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Space.xxs, vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                ChromeAction(Icons.Rounded.Add, "Install an app", onInstall)
                // Reachable with nothing running, because a route or an attitude is usually set up
                // *before* the app that is meant to react to it is opened.
                ChromeAction(Icons.Rounded.Tune, "Hardware", onHardware)
            }
        }
    }
}

/** One of [HomeChrome]'s buttons: labelled, because unlike the control bar's there are only two. */
@Composable
private fun ChromeAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = Space.sm, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.md),
        )
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * What the home screen's Recents button opens: the apps this device has actually run.
 *
 * Deliberately the same list the running device's task view shows — [VirtualTasks] is the one
 * record — so pressing Recents means the same thing whether or not an app is on the screen. Drawn in
 * Compose rather than onto the surface because it is transient chrome the launcher's own capture
 * should not bake in.
 */
@Composable
private fun HomeTaskView(
    tasks: List<LauncherApp>,
    onOpen: (VirtualDeviceApp) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.82f))
            .clickable(onClickLabel = "Close the task view", onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        if (tasks.isEmpty()) {
            ScreenMessage("No recent apps")
            return@Box
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(Space.md),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            tasks.forEach { entry ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.md))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onOpen(entry.app) }
                        .padding(Space.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    entry.icon?.let { icon ->
                        // An ImageView rather than a Compose painter: the icon is an APK's own
                        // Drawable, and Compose has no first-party way to paint one without
                        // Accompanist, which this pack does not bundle.
                        AndroidView(
                            factory = { context -> android.widget.ImageView(context) },
                            update = { view -> view.setImageDrawable(icon) },
                            modifier = Modifier.size(44.dp),
                        )
                    }
                    Text(
                        text = entry.app.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 96.dp).padding(top = Space.xs),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

/** Never a black tab with no explanation: whatever went wrong, the app can still be started the old
 *  way from here. */
@Composable
private fun ScreenFallback(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.padding(Space.xxl).widthIn(max = 420.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Text(
                text = "Could not run the app on this device",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CompactOutlinedButton(text = "Try again", onClick = onRetry, modifier = Modifier.weight(1f))
                CompactOutlinedButton(text = "Clear", onClick = onDismiss, modifier = Modifier.weight(1f))
            }
        }
    }
}

/**
 * The device's controls, floating over its screen instead of taking a band out of it.
 *
 * An embedded guest lays itself out against the phone's screen rather than the tab, so tall content
 * already runs past the bottom edge; every row of tab handed back to it is a row of the app that can
 * actually be seen. The bar therefore collapses itself once it has been left alone, and comes back
 * through the same pill the workbench's hidden chrome uses.
 *
 * [caveat] is what the container could not give the guest that is up — a button here when there is
 * something to say, and no button at all when there is not.
 */
@Composable
private fun BoxScope.DeviceControls(
    pane: PaneLayout,
    /** Whether the bar is covering the top of the tab right now — see [HomeChrome]. */
    caveat: String?,
    onKeyboard: () -> Unit,
    onInstall: () -> Unit,
    onHardware: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    // With a gutter the bar has somewhere of its own to be, so it never collapses: the idle timer
    // and the pill exist to get the bar off the *device's screen*, and beside the device it is not
    // on it. That also takes the timer out of the way of its own menus.
    var caveatOpen by remember { mutableStateOf(false) }
    if (pane.hasGutter) {
        // Beside the device, not over it, so nothing of the tab's own corner is covered.
        GutterControls(
            pane = pane,
            caveat = caveat,
            onKeyboard = onKeyboard,
            onCaveat = { caveatOpen = true },
            onHardware = onHardware,
            onRestart = onRestart,
            onStop = onStop,
        )
        caveat?.takeIf { caveatOpen }?.let { message ->
            GuestCaveatDialog(message = message, onDismiss = { caveatOpen = false })
        }
        return
    }

    var expanded by remember { mutableStateOf(true) }
    // The screen-options menu lives inside the bar's own composition, so collapsing the bar takes the
    // menu with it. Without this the bar timed out from under an open menu after four seconds and
    // every selection after that landed on the guest instead — the menu was gone before it was read.
    var screenMenuOpen by remember { mutableStateOf(false) }
    // The dialog is a window of its own, so nothing presses the bar while it is up — and a guest that
    // stops takes its caveat with it, which must also let the timer go again.
    val openCaveat = caveat?.takeIf { caveatOpen }

    AnimatedVisibility(
        visible = expanded,
        modifier = Modifier.align(Alignment.TopCenter),
        enter = expandVertically(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)),
    ) {
        // Remembered with the bar rather than with the page, so a press that the collapse animation
        // cut short cannot strand the timer once the pill brings the bar back.
        var pressed by remember { mutableStateOf(false) }
        LaunchedEffect(pressed, openCaveat, screenMenuOpen) {
            if (!pressed && openCaveat == null && !screenMenuOpen) {
                delay(TOOLBAR_IDLE_COLLAPSE_MS)
                expanded = false
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                // Watched on the initial pass, never consumed: the buttons still get their taps, a
                // finger still down holds the bar open, and the guest below is spared both.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            pressed = awaitPointerEvent(PointerEventPass.Initial)
                                .changes.any { it.pressed }
                        }
                    }
                },
            // No `tonalElevation`. Material 3 raises a surface by blending `primary` into it, and
            // JCode's primary is blue — so the device's bar came out blue-tinted while every panel
            // in the IDE around it, which sets a flat `surface`, stayed neutral grey. The divider
            // below is what separates this from the device's screen; it does not need a tint to do
            // that, and a tint is what made this look like somebody else's chrome.
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column {
                DeviceToolbar(
                    caveat = caveat,
                    onScreenMenu = { screenMenuOpen = it },
                    onKeyboard = onKeyboard,
                    onCaveat = { caveatOpen = true },
                    onInstall = onInstall,
                    onHardware = onHardware,
                    onRestart = onRestart,
                    onStop = onStop,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
        }
    }

    if (!expanded) {
        DeviceControlsHandle(
            onClick = { expanded = true },
            // Top-centre: the workbench's own chrome pill owns TopEnd in the same Box, and in
            // distraction-free mode the two would sit on top of each other.
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }

    // Outside the collapsing bar: the message takes longer to read than the bar stays up.
    openCaveat?.let { message ->
        GuestCaveatDialog(message = message, onDismiss = { caveatOpen = false })
    }
}

/**
 * The control bar when the device is a pane: a column in the gutter beside it.
 *
 * Vertical because that is the shape of the space a portrait device leaves, and permanent because
 * the reasons the horizontal bar hides itself do not apply here — it is not covering the device's
 * screen, so there is nothing to get out of the way of. An emulator's controls sit beside the device
 * for the same reason.
 */
@Composable
private fun BoxScope.GutterControls(
    pane: PaneLayout,
    caveat: String?,
    onKeyboard: () -> Unit,
    onCaveat: () -> Unit,
    onHardware: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .width(pane.sideGutterDp.dp)
            .padding(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xxs),
        // Hugging the device's right edge, for the reason HomeChrome hugs its left one.
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.lg),
            // Flat, for the reason the horizontal bar is — the rounded shape against the editor's
            // background is what makes this read as a panel.
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(Space.xxs),
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScreenOptionsAction(onOpenChange = {})
                ToolbarAction(Icons.Rounded.Keyboard, "Keyboard", onKeyboard)
                ToolbarAction(Icons.Rounded.Tune, "Device hardware", onHardware)
                if (caveat != null) {
                    ToolbarAction(
                        icon = Icons.Rounded.Warning,
                        label = "What this guest could not do",
                        onClick = onCaveat,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                // Width pinned to the buttons'. HorizontalDivider fills its parent by default, and
                // in a Column whose constraints are the whole gutter that made the panel as wide as
                // the empty space beside the device — a 400dp slab with six small icons adrift in
                // it. Nothing else here has an opinion about width, so the divider was setting it.
                HorizontalDivider(
                    modifier = Modifier
                        .width(GUTTER_ICON_DP.dp)
                        .padding(vertical = Space.xxs),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                ToolbarAction(Icons.Rounded.RestartAlt, "Restart app", onRestart)
                ToolbarAction(
                    Icons.Rounded.Stop,
                    "Stop",
                    onStop,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * What the container could not give the running guest, word for word. It is a paragraph about one
 * app's fidelity rather than something to act on, so it waits behind a tap instead of standing in a
 * band of the device's screen — and nothing is trimmed to fit once it is opened.
 */
@Composable
private fun GuestCaveatDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What this guest could not do") },
        text = {
            Text(
                text = message,
                modifier = Modifier.verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { CompactFilledButton(text = "Close", onClick = onDismiss) },
    )
}

/**
 * The collapsed controls: a grabber line, not a button. It sits over whatever the guest is drawing,
 * so it stays deliberately small — the touch target is taller than the line it shows, which is why
 * the clickable box and the bar have separate sizes.
 */
@Composable
private fun DeviceControlsHandle(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(HANDLE_WIDTH_FRACTION)
            .height(HANDLE_TOUCH_HEIGHT)
            .clickable(onClickLabel = "Show the device controls", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HANDLE_THICKNESS)
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
        )
    }
}

@Composable
private fun DeviceToolbar(
    caveat: String?,
    onScreenMenu: (Boolean) -> Unit,
    onKeyboard: () -> Unit,
    onCaveat: () -> Unit,
    onInstall: () -> Unit,
    onHardware: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xs, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
    ) {
        // No Back here any more: it is a button on the device's own navigation bar, where
        // `screencap` shows it, `uiautomator dump` lists it and `input tap` can reach it — see
        // VirtualNavigationBar for why the toolbar was the wrong window for it.
        ScreenOptionsAction(onOpenChange = onScreenMenu)
        ToolbarAction(Icons.Rounded.Keyboard, "Keyboard", onKeyboard)
        // Here as well as on the device's own bottom chrome: this bar is where every device
        // control is, and a person who opened it should not have to close it again to find two
        // of them somewhere else.
        ToolbarAction(Icons.Rounded.Add, "Install an app", onInstall)
        // The bench opens beside the device rather than over it, so the app being moved stays on
        // screen while it is being moved.
        ToolbarAction(Icons.Rounded.Tune, "Device hardware", onHardware)
        // Only lit when this guest actually lost something: a warning that is always on is a warning
        // nobody reads. Carries the same colour ManagerNoticeCard gives the launcher's version of it.
        if (caveat != null) {
            ToolbarAction(
                icon = Icons.Rounded.Warning,
                label = "What this guest could not do",
                onClick = onCaveat,
                tint = MaterialTheme.colorScheme.error,
            )
        }
        Box(modifier = Modifier.weight(1f))
        ToolbarAction(Icons.Rounded.RestartAlt, "Restart app", onRestart)
        ToolbarAction(Icons.Rounded.Stop, "Stop", onStop, tint = MaterialTheme.colorScheme.error)
    }
}

/**
 * The device's screen options: which screen it is pretending to be, and which way up.
 *
 * The same presets the layout designer offers, deliberately — a layout checked in the designer and
 * an app run on the device should be checked against the same screens. What differs is that this is
 * not a preview: picking one rewrites the guest's `DisplayMetrics` and `Configuration`, so its
 * resource qualifiers reselect and `onConfigurationChanged` fires. See [VirtualScreenOptions].
 */
@Composable
private fun ScreenOptionsAction(onOpenChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Told to the bar rather than kept private: the bar's idle collapse would otherwise dismiss this
    // menu out from under whoever opened it.
    LaunchedEffect(open) { onOpenChange(open) }
    DisposableEffect(Unit) { onDispose { onOpenChange(false) } }
    val profile by VirtualScreenOptions.profile
    val rotated by VirtualScreenOptions.rotated

    Box {
        ToolbarAction(
            icon = Icons.Rounded.PhoneAndroid,
            label = "Screen: ${profile.label}",
            onClick = { open = true },
            // Lit while the device is not its own shape, so it is obvious at a glance that what is
            // on screen is a 360dp phone rather than the tab.
            tint = if (profile.isNative) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            VirtualScreenOptions.PROFILES.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (option.isNative) option.label
                            else "${option.label} — ${option.subtitle()}",
                        )
                    },
                    leadingIcon = {
                        if (option == profile) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(IconSize.md))
                        }
                    },
                    onClick = {
                        VirtualScreenOptions.select(option)
                        open = false
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (rotated) "Portrait" else "Landscape") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.ScreenRotation,
                        contentDescription = null,
                        modifier = Modifier.size(IconSize.md),
                    )
                },
                // A profile that is the tab has no shape of its own to rotate; rotating the tab is
                // rotating the phone.
                enabled = !profile.isNative,
                onClick = {
                    VirtualScreenOptions.rotate()
                    open = false
                },
            )
        }
    }
}

/** The device-menu affordance, sized to [VirtualStatusBar.BAR_DP] rather than to a toolbar. */

@Composable
private fun ToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(IconSize.lg))
    }
}

/**
 * Puts an APK on the device from a path — the tab's half of `adb install`, plus the one-off run that
 * skips installing entirely. Over the screen, not instead of it: the device stays up.
 */
@Composable
private fun InstallSheet(
    hardwareAccelerated: Boolean,
    apkPath: String,
    onApkPathChange: (String) -> Unit,
    onInstall: (String) -> Unit,
    onRunHere: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problem = remember(apkPath) { apkProblem(apkPath) }
    val readable = apkPath.isNotBlank() && problem == null
    // Projects live on app-private ext4, not the shared /storage tree an older build used, so the
    // example path is resolved rather than written out.
    val projectsRoot = remember { WorkspaceHostPaths.projectsRoot }
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text("Install an app", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "Put a freshly built APK on ${VirtualIdentity.MODEL} — no install on " +
                            "this phone, no ADB. It runs in JCode's own process under a virtual " +
                            "device identity, and everything it stores is cleared when JCode starts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            if (!hardwareAccelerated) {
                ManagerNoticeCard(
                    title = "Hardware acceleration is off",
                    message = "The device is composited onto a surface, which needs the GPU. Turn " +
                        "Settings → Performance → Rendering → Hardware acceleration back on and " +
                        "restart JCode; until then the device cannot draw.",
                )
            }

            ManagerSectionCard(
                title = "App",
                description = "The APK to put on the device. Installing it leaves an icon on the home " +
                    "screen and lists it under `adb shell pm list packages`; running it straight from " +
                    "here does neither. A virtual-device run config fills this in with whatever it just " +
                    "built, and `adb install` reaches the same device.",
            ) {
                SettingsTextFieldRow(
                    label = "APK path",
                    value = apkPath,
                    onValueChange = onApkPathChange,
                    placeholder = "$projectsRoot/…/app-debug.apk",
                    monospace = true,
                )
                problem?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                CompactFilledButton(
                    text = "Install on ${VirtualIdentity.MODEL}",
                    enabled = readable,
                    onClick = { onInstall(apkPath.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                )
                CompactOutlinedButton(
                    text = "Run once, without installing",
                    enabled = readable,
                    onClick = onRunHere,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ManagerNoticeCard(
                title = "What a guest gives up",
                message = "The app runs without an activity of its own, so it cannot raise the soft " +
                    "keyboard itself — use the keyboard button. Its own Lifecycle is driven directly, " +
                    "but callbacks it registers on the activity with registerActivityLifecycleCallbacks " +
                    "miss the pre/post start, resume and stop steps, because Android 13 puts that list " +
                    "out of reach.",
            )
        }
    }
}

/** Why this path cannot be run, in the user's terms — null when it can. */
private fun apkProblem(path: String): String? {
    val trimmed = path.trim()
    if (trimmed.isEmpty()) return null
    val file = File(trimmed)
    return when {
        file.isDirectory -> "That is a folder — point this at the .apk file inside it."
        !file.exists() -> "Nothing is at that path. A debug build leaves its APK under " +
            "app/build/outputs/apk/debug/ inside the project."
        !file.canRead() -> "JCode cannot read that file."
        else -> null
    }
}

/**
 * What the device knows about an installed app, read back out of its APK.
 *
 * A modal rather than another screen on the device: this is JCode talking about the app, not the
 * app talking, and putting it on the device's own screen would put it in `screencap` where it would
 * read as something the guest drew.
 *
 * Everything here comes from the archive rather than from a running guest, so it answers the same
 * whether the app has ever been opened.
 */
@Composable
private fun AppDetailsDialog(
    app: VirtualDeviceApp,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Measuring what an app has stored means walking its tree, so the dialog opens with everything
    // the archive can answer straight away and fills this in when it knows.
    var stored by remember(app.apkPath) { mutableStateOf<Long?>(null) }
    LaunchedEffect(app.apkPath) {
        stored = withContext(Dispatchers.IO) { VirtualDeviceApps.dataSize(context, app.packageName) }
    }
    val facts = remember(app.apkPath, stored) { appFacts(context, app, stored) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { CompactFilledButton(text = "Close", onClick = onDismiss) },
        title = { Text(app.label) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                facts.forEach { (name, value) ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

/**
 * Reads the archive once and flattens it into label/value pairs the dialog just prints. [stored] is
 * null while the app's data is still being measured.
 */
private fun appFacts(
    context: android.content.Context,
    app: VirtualDeviceApp,
    stored: Long?,
): List<Pair<String, String>> {
    val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
        PackageManager.GET_PERMISSIONS
    val info = runCatching { context.packageManager.getPackageArchiveInfo(app.apkPath, flags) }
        .getOrNull()
    val apk = File(app.apkPath)
    return buildList {
        add("Package" to app.packageName)
        add("Version" to (app.versionName ?: "unknown"))
        info?.applicationInfo?.let { application ->
            add("Target SDK" to application.targetSdkVersion.toString())
            add("Minimum SDK" to application.minSdkVersion.toString())
        }
        add(
            "Components" to listOf(
                "${info?.activities?.size ?: app.activities.size} activities",
                "${info?.services?.size ?: 0} services",
                "${info?.receivers?.size ?: 0} receivers",
                "${info?.providers?.size ?: 0} providers",
            ).joinToString(", "),
        )
        info?.requestedPermissions?.takeIf { it.isNotEmpty() }?.let { permissions ->
            // The guest inherits JCode's permissions wholesale, so this is what the app *asked* for
            // rather than what it has — worth saying, and worth not implying otherwise.
            add(
                "Requests (not granted separately)" to
                    permissions.joinToString(separator = "\n") {
                        it.removePrefix("android.permission.")
                    },
            )
        }
        add(
            "Runs in background" to
                if (VirtualDevicePolicy.backgroundAllowed(context, app.packageName)) "Allowed"
                else "Stops when you leave it",
        )
        add("APK" to "${apk.name} (${humanSize(apk.length())})")
        // Everything the app has written into its private tree — what "Clear data" would remove,
        // and what the next restart takes with the rest of the device.
        add(
            "Data" to when {
                stored == null -> "Measuring…"
                stored == 0L -> "Nothing stored yet"
                else -> humanSize(stored)
            },
        )
    }
}
