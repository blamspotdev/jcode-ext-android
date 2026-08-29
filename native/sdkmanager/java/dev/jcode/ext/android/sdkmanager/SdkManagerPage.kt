package dev.jcode.ext.android.sdkmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ManagerFilterChip
import dev.blamspot.jcode.design.ManagerNoticeCard
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.launch

/**
 * The Android SDK Manager, as the SDK's own package manager rather than as one toolchain row.
 *
 * JCode's Toolchains list is a list of *tools*: one row, one thing, installed or not. The Android
 * SDK is not one thing — it is platforms, build-tools, NDKs, system images and command-line tools,
 * each at its own revision, and a platform can be half present. None of that fits a row, and all of
 * it is what somebody opening "Android SDK Manager" came to see. So the pack that owns the Android
 * toolchain draws it, and JCode only offers the row that opens this page.
 *
 * Two things this shows that Android Studio's does not, because they are true only here:
 *  * a platform newer than the installed `aapt2` can link against is marked unusable rather than
 *    offered — it would install perfectly and then fail every build;
 *  * a cmdline-tools update past 22.0 is shown and refused, because from 23.0 `sdkmanager` is a shim
 *    over an x86-64 binary and every call becomes a silent no-op that still exits 0.
 */
@Composable
internal fun SdkManagerPage(
    host: NativeHost,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SdkManagerCatalog.Snapshot?>(null) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(Tab.Platforms) }
    var details by remember { mutableStateOf(false) }
    /** Paths whose desired state differs from disk: true = install it, false = remove it. */
    var pending by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var progress by remember { mutableStateOf<SdkManagerApply.Progress?>(null) }
    /** Licences the SDK wants agreed to before the pending installs can go ahead. Shown, not
     *  assumed: accepting a licence on somebody's behalf is not accepting it. */
    var licences by remember { mutableStateOf<List<SdkManagerApply.Licence>>(emptyList()) }
    var checkingLicences by remember { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        SdkManagerCatalog.probe(host)
            .onSuccess { snapshot = it; failure = null }
            .onFailure { failure = it }
        pending = emptyMap()
        loading = false
    }

    LaunchedEffect(host) { reload() }

    Column(modifier = modifier.fillMaxSize().padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Header(
            busy = loading || progress != null,
            onRefresh = { if (progress == null) scope.launch { reload() } },
        )

        val snap = snapshot
        when {
            progress != null -> ApplyProgress(progress!!, Modifier.weight(1f))

            loading -> Box(Modifier.fillMaxWidth().padding(Space.lg), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.width(28.dp))
            }

            failure is SdkManagerCatalog.NoSdkInstalled -> ManagerNoticeCard(
                title = "The Android SDK is not installed",
                message = "Install it from Toolchains → SDKs → Android SDK. This page manages the " +
                    "packages inside an SDK that is already here; it cannot create one.",
            )

            failure != null -> ManagerNoticeCard(
                title = "Could not read the SDK",
                message = failure!!.message ?: "sdkmanager did not answer.",
            )

            snap != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Tab.entries.forEach { t ->
                        ManagerFilterChip(selected = tab == t, label = t.label, onClick = { tab = t })
                    }
                }
                // Keyed on the tab, so each arrives in the order that suits it — platforms newest
                // first, tools by name — and a sort chosen on one does not follow you to the other.
                var sort by remember(tab) { mutableStateOf(tab.defaultSort) }
                val rows = remember(snap, tab, details, sort) { rowsFor(snap, tab, details, sort) }
                PackageTable(
                    modifier = Modifier.weight(1f),
                    rows = rows,
                    columns = tab.columns,
                    sort = sort,
                    onSort = { column ->
                        // Clicking the column already sorted reverses it; a different one starts in
                        // whichever direction reads naturally for what it holds.
                        sort = if (sort.column == column) sort.copy(ascending = !sort.ascending)
                        else Sort(column, tab.naturalDirection(column))
                    },
                    pending = pending,
                    onToggle = { row ->
                        val unusable = row.unusable
                        if (unusable != null) {
                            onSnackbar(unusable.reason)
                        } else {
                            pending = toggled(pending, row)
                        }
                    },
                )
                Footer(
                    details = details,
                    onDetails = { details = it },
                    pending = pending,
                    onDiscard = { pending = emptyMap() },
                    busy = checkingLicences,
                    onApply = {
                        val install = pending.filterValues { it }.keys.toList()
                        val remove = pending.filterValues { !it }.keys.toList()
                        scope.launch {
                            // Nothing is installed until the terms behind it have been shown and
                            // agreed to. A removal-only apply asks nothing, because it agrees to
                            // nothing.
                            if (install.isNotEmpty()) {
                                checkingLicences = true
                                val pendingLicences = SdkManagerApply.pendingLicences(host, snap.androidHome)
                                    .getOrElse {
                                        onSnackbar(it.message ?: "Could not read the SDK licences.")
                                        emptyList()
                                    }
                                checkingLicences = false
                                if (pendingLicences.isNotEmpty()) {
                                    licences = pendingLicences
                                    return@launch
                                }
                            }
                            apply(host, snap.androidHome, install, remove, accepted = false,
                                onProgress = { progress = it }, onSnackbar = onSnackbar, onDone = { progress = null; reload() })
                        }
                    },
                )
            }
        }
    }

    if (licences.isNotEmpty()) {
        val snap = snapshot
        LicenceDialog(
            licences = licences,
            onDecline = { licences = emptyList() },
            onAccept = {
                val terms = licences
                licences = emptyList()
                val install = pending.filterValues { it }.keys.toList()
                val remove = pending.filterValues { !it }.keys.toList()
                if (snap != null && terms.isNotEmpty()) {
                    scope.launch {
                        apply(host, snap.androidHome, install, remove, accepted = true,
                            onProgress = { progress = it }, onSnackbar = onSnackbar, onDone = { progress = null; reload() })
                    }
                }
            },
        )
    }
}

/** Runs the apply and reports what actually landed, rather than what the exit code claimed. */
private suspend fun apply(
    host: NativeHost,
    androidHome: String,
    install: List<String>,
    remove: List<String>,
    accepted: Boolean,
    onProgress: (SdkManagerApply.Progress?) -> Unit,
    onSnackbar: (String) -> Unit,
    onDone: suspend () -> Unit,
) {
    SdkManagerApply.run(host, androidHome, install, remove, accepted) { onProgress(it) }
        .onFailure { onSnackbar(it.message ?: "The changes did not apply.") }
    onDone()
    onSnackbar("Re-read the SDK — the table shows what is on disk, not what sdkmanager reported.")
}

/**
 * The SDK licence, shown before anything behind it is installed.
 *
 * Android's packages come with terms, and `sdkmanager` will happily take a stream of confirmations
 * without anybody reading them. That is not consent, so the text is put on the screen and the
 * install waits behind a decision.
 */
@Composable
private fun LicenceDialog(
    licences: List<SdkManagerApply.Licence>,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Text(
                if (licences.size == 1) "Accept the Android SDK licence"
                else "Accept ${licences.size} Android SDK licences",
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "These packages are covered by terms you have not accepted yet. Nothing is " +
                        "downloaded until you agree to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                licences.forEach { licence ->
                    Text(
                        text = licence.id,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Space.sm),
                    )
                    Text(
                        text = licence.text,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { CompactFilledButton(text = "Accept", onClick = onAccept) },
        dismissButton = { CompactOutlinedButton(text = "Decline", onClick = onDecline) },
    )
}

/**
 * A tab, and the columns beside the name.
 *
 * The last column is always Status, rendered from [PackageRow.status] rather than from its cells —
 * so a row supplies one fewer cell than there are columns here.
 */
private enum class Tab(val label: String, val columns: List<String>, val defaultSort: Sort) {
    /** Grouped by API level, the way Studio's collapsed list is — which is what makes a row able to
     *  be *partially* installed. Newest first, which is what somebody looking for a platform wants. */
    Platforms("SDK Platforms", listOf("API Level", "Revision", "Status"), Sort(1, ascending = false)),
    Tools("SDK Tools", listOf("Version", "Status"), Sort(0, ascending = true)),
    ;

    /** Column 0 is the name; 1..n are [columns]. */
    val statusColumn: Int get() = columns.size
}

/** Which column the table is ordered by, and which way. */
private data class Sort(val column: Int, val ascending: Boolean)

/**
 * Where a column starts when you first click it.
 *
 * Text reads best A→Z and versions read best newest-first, so the direction follows the kind of
 * thing in the column rather than being the same everywhere and wrong half the time.
 */
private fun Tab.naturalDirection(column: Int): Boolean = column == 0 || column == statusColumn

/** One line of the table: a group of packages that a single checkbox acts on. */
private data class PackageRow(
    val key: String,
    val name: String,
    /** Already in the tab's column order, so the table renders rather than decides. */
    val cells: List<String>,
    /** Every package this row installs or removes. One for a leaf, many for a collapsed group. */
    val paths: List<String>,
    /** Which of [paths] are on disk. The count is not enough: a click on a half-installed group has
     *  to know which packages it is actually changing. */
    val installedPaths: Set<String>,
    val update: Boolean,
    val unusable: SdkManagerCatalog.Unusable?,
    val indent: Boolean = false,
) {
    val state: ToggleableState = when (installedPaths.size) {
        0 -> ToggleableState.Off
        paths.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    val status: String = when {
        unusable != null -> "Not usable here"
        update -> "Update available"
        installedPaths.isEmpty() -> "Not installed"
        installedPaths.size == paths.size -> "Installed"
        else -> "Partially installed"
    }
}

/**
 * Orders `34.0.0` above `9.0`, which a string comparison does not.
 *
 * Every version here is dotted numbers with the occasional suffix (`33.0.0-rc1`), so each part is
 * compared as a number where it is one and as text where it is not.
 */
private fun versionKey(v: String): List<Long> =
    v.split('.', '-').map { part -> part.takeWhile(Char::isDigit).toLongOrNull() ?: -1L }

private val VERSION_ORDER = compareBy<SdkManagerCatalog.Package> { versionKey(it.version).firstOrNull() ?: -1L }
    .thenBy { versionKey(it.version).getOrNull(1) ?: -1L }
    .thenBy { versionKey(it.version).getOrNull(2) ?: -1L }
    .thenBy { it.version }

/**
 * The rows for a tab.
 *
 * Collapsed, a platform row is an API level and everything that belongs to it; expanded, it is the
 * packages themselves. Tools group by family for the same reason — nobody scanning for the NDK wants
 * eleven build-tools revisions in the way.
 */
private fun rowsFor(
    snap: SdkManagerCatalog.Snapshot,
    tab: Tab,
    details: Boolean,
    sort: Sort,
): List<PackageRow> {
    fun leaf(p: SdkManagerCatalog.Package, indent: Boolean) = PackageRow(
        key = p.path,
        name = p.description.ifBlank { p.path },
        cells = when (tab) {
            Tab.Platforms -> listOf("", p.version)
            Tab.Tools -> listOf(p.version)
        },
        paths = listOf(p.path),
        installedPaths = if (p.installed) setOf(p.path) else emptySet(),
        update = p.update != null,
        unusable = SdkManagerCatalog.unusable(p, snap.aapt2Ceiling),
        indent = indent,
    )

    // Built as groups, sorted as groups, flattened last. Sorting the flat list instead would tear
    // every expanded package away from the row it belongs to and scatter them through the table.
    val groups: List<Pair<PackageRow, List<PackageRow>>> = when (tab) {
        Tab.Platforms -> snap.packages
            .mapNotNull { p -> SdkManagerCatalog.apiLevelOf(p.path)?.let { it to p } }
            .groupBy({ it.first }, { it.second })
            .toList()
            .map { (api, pkgs) ->
                val platform = pkgs.firstOrNull { it.family == "platforms" }
                val group = PackageRow(
                    key = "api-$api",
                    name = androidReleaseName(api),
                    cells = listOf(api, platform?.version.orEmpty()),
                    paths = pkgs.map { it.path },
                    installedPaths = pkgs.filter { it.installed }.map { it.path }.toSet(),
                    update = pkgs.any { it.update != null },
                    unusable = platform?.let { SdkManagerCatalog.unusable(it, snap.aapt2Ceiling) },
                )
                group to if (details) pkgs.map { leaf(it, indent = true) } else emptyList()
            }

        Tab.Tools -> snap.packages
            .filter { SdkManagerCatalog.apiLevelOf(it.path) == null }
            .groupBy { it.family }
            .toList()
            .map { (family, pkgs) ->
                val installed = pkgs.filter { it.installed }
                val newest = pkgs.maxWithOrNull(VERSION_ORDER) ?: pkgs.first()
                // What the collapsed row is *about*: the newest installed revision if any is here,
                // otherwise the newest one on offer.
                val face = installed.maxWithOrNull(VERSION_ORDER) ?: newest
                // Checking an absent family installs the newest; unchecking removes what is here.
                val paths = if (installed.isEmpty()) listOf(newest.path) else installed.map { it.path }
                val group = PackageRow(
                    key = "family-$family",
                    // A family of one is named by the package itself: `build;templates` is a real
                    // package whose family name would otherwise read as "build".
                    name = if (pkgs.size == 1) pkgs[0].description.ifBlank { family } else toolFamilyName(family),
                    cells = listOf(face.version),
                    paths = paths,
                    installedPaths = installed.map { it.path }.toSet(),
                    update = pkgs.any { it.update != null },
                    unusable = SdkManagerCatalog.unusable(face, snap.aapt2Ceiling),
                )
                group to if (details) pkgs.sortedWith(VERSION_ORDER.reversed()).map { leaf(it, indent = true) }
                else emptyList()
            }
    }

    val ordered = groups.sortedWith(comparatorFor(tab, sort.column))
    return (if (sort.ascending) ordered else ordered.asReversed())
        .flatMap { (group, children) -> listOf(group) + children }
}

/**
 * Orders groups by one column.
 *
 * Versions are compared component by component rather than as text, or `9.0` sorts above `34.0.0`.
 * Status is compared by how much it asks of you — something to update first, something this device
 * cannot use last — because alphabetical order over five fixed words means nothing to anybody.
 */
private fun comparatorFor(tab: Tab, column: Int): Comparator<Pair<PackageRow, List<PackageRow>>> {
    val row: (Pair<PackageRow, List<PackageRow>>) -> PackageRow = { it.first }
    return when (column) {
        0 -> compareBy { row(it).name.lowercase() }
        tab.statusColumn -> compareBy({ statusRank(row(it)) }, { row(it).name.lowercase() })
        else -> Comparator { a, b ->
            val ka = versionKey(row(a).cells.getOrElse(column - 1) { "" })
            val kb = versionKey(row(b).cells.getOrElse(column - 1) { "" })
            compareVersionKeys(ka, kb).let { if (it != 0) it else row(a).name.compareTo(row(b).name) }
        }
    }
}

private fun compareVersionKeys(a: List<Long>, b: List<Long>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val c = (a.getOrElse(i) { -1L }).compareTo(b.getOrElse(i) { -1L })
        if (c != 0) return c
    }
    return 0
}

/** Most-actionable first: what wants updating, then what is half done, then the rest. */
private fun statusRank(row: PackageRow): Int = when {
    row.unusable != null -> 4
    row.update -> 0
    row.installedPaths.isEmpty() -> 3
    row.installedPaths.size == row.paths.size -> 2
    else -> 1
}

/**
 * Flips a row.
 *
 * Only the packages that would actually change are recorded: asking to install one already installed
 * is not a change, and handing it to `sdkmanager` makes it download the thing again. So an entry is
 * dropped as soon as the desired state matches what is on disk, which is also what makes "Discard"
 * and the pending count mean something.
 */
private fun toggled(pending: Map<String, Boolean>, row: PackageRow): Map<String, Boolean> {
    val wantInstalled = row.state != ToggleableState.On
    val next = pending.toMutableMap()
    for (path in row.paths) {
        if (wantInstalled == (path in row.installedPaths)) next.remove(path) else next[path] = wantInstalled
    }
    return next
}

@Composable
private fun Header(busy: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Android SDK Manager", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Platforms, build tools and NDKs, from this device's own SDK.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) CircularProgressIndicator(modifier = Modifier.width(18.dp))
        else CompactOutlinedButton(text = "Refresh", onClick = onRefresh)
    }
}

@Composable
private fun PackageTable(
    rows: List<PackageRow>,
    columns: List<String>,
    pending: Map<String, Boolean>,
    onToggle: (PackageRow) -> Unit,
    sort: Sort,
    onSort: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.ms, vertical = Space.s),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                HeaderCell("Name", 0, sort, onSort, Modifier.weight(1f))
                columns.forEachIndexed { index, label ->
                    HeaderCell(label, index + 1, sort, onSort, Modifier.width(COLUMN))
                }
            }
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                val changing = row.paths.any { it in pending }
                Row(
                    // The whole row, not just the checkbox: a refused row has a *disabled* checkbox,
                    // so without this the one thing it exists to tell you — why it is refused — is
                    // the one thing you cannot reach.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(row) }
                        .padding(end = Space.ms, top = Space.hairline, bottom = Space.hairline),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    TriStateCheckbox(
                        state = if (changing) pendingState(row, pending) else row.state,
                        onClick = { onToggle(row) },
                        enabled = row.unusable == null,
                        modifier = Modifier.padding(start = if (row.indent) Space.lg else Space.xxs),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (row.unusable != null) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        if (row.indent) {
                            Text(
                                row.key,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    row.cells.forEach { Cell(it) }
                    Cell(row.status)
                }
            }
        }
    }
}

/**
 * A column heading that orders the table.
 *
 * The arrow is on the active column only. Without it a sorted table is a table somebody has to work
 * out by reading it, and clicking a heading that gives no sign it did anything reads as broken.
 */
@Composable
private fun HeaderCell(
    label: String,
    column: Int,
    sort: Sort,
    onSort: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = sort.column == column
    Row(
        modifier = modifier.clickable { onSort(column) }.padding(vertical = Space.hairline),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (active) {
            Text(
                text = if (sort.ascending) " ▲" else " ▼",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun Cell(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(COLUMN),
    )
}

/** What the checkbox shows for a row the user has changed but not yet applied. */
private fun pendingState(row: PackageRow, pending: Map<String, Boolean>): ToggleableState {
    val wanted = row.paths.count { pending[it] ?: (it in row.installedPaths) }
    return when (wanted) {
        0 -> ToggleableState.Off
        row.paths.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
}

@Composable
private fun Footer(
    details: Boolean,
    onDetails: (Boolean) -> Unit,
    pending: Map<String, Boolean>,
    onDiscard: () -> Unit,
    onApply: () -> Unit,
    busy: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        CompactOutlinedButton(
            text = if (details) "Hide package details" else "Show package details",
            onClick = { onDetails(!details) },
        )
        // Takes the slack so what is pending sits against the right edge. This was a fixed-width
        // box, which looks like it pushes and does not — inside a Row that already spaces its
        // children, it was an extra gap pretending to be a layout.
        Box(modifier = Modifier.weight(1f))
        if (pending.isNotEmpty()) {
            val installs = pending.count { it.value }
            val removals = pending.size - installs
            Text(
                text = listOfNotNull(
                    installs.takeIf { it > 0 }?.let { "$it to install" },
                    removals.takeIf { it > 0 }?.let { "$it to remove" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CompactOutlinedButton(text = "Discard", onClick = onDiscard)
            if (busy) {
                Text("Reading licences…", style = MaterialTheme.typography.labelSmall)
            } else {
                CompactFilledButton(text = "Apply", onClick = onApply)
            }
        }
    }
}

@Composable
private fun ApplyProgress(progress: SdkManagerApply.Progress, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
            Text(
                text = progress.phase + "…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            progress.percent?.let {
                Text("$it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Determinate whenever the manifest told us how big this is, because "43%" is worth far more
        // than a spinner on a download that can run for ten minutes — and indeterminate when it did
        // not, rather than a bar that invents a number.
        if (progress.percent != null) {
            LinearProgressIndicator(
                progress = { progress.percent / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Surface(
            modifier = Modifier.fillMaxWidth().weight(1f),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        ) {
            // sdkmanager's own output, because it is the only place a skipped package ever explains
            // itself — it exits 0 either way.
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(Space.sm)) {
                progress.lines.forEach {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val COLUMN = 96.dp

/**
 * What Android Studio calls an API level.
 *
 * Baked in, because `sdkmanager` does not know: it answers "Android SDK Platform 36", and the name
 * a person recognises — "Android 16.0 (Baklava)" — lives only in Studio's own table. Unknown levels
 * fall back to the API number, which is honest rather than wrong; extend the list as releases land.
 */
private fun androidReleaseName(api: String): String {
    val name = RELEASE_NAMES[api.substringBefore('.')] ?: return "Android API $api"
    return "Android $name"
}

private val RELEASE_NAMES = mapOf(
    "37" to "17.0 (\"CinnamonBun\")",
    "36" to "16.0 (\"Baklava\")",
    "35" to "15.0 (\"VanillaIceCream\")",
    "34" to "14.0 (\"UpsideDownCake\")",
    "33" to "13.0 (\"Tiramisu\")",
    "32" to "12L (\"Sv2\")",
    "31" to "12.0 (\"S\")",
    "30" to "11.0 (\"R\")",
    "29" to "10.0 (\"Q\")",
    "28" to "9.0 (\"Pie\")",
    "27" to "8.1 (\"Oreo\")",
    "26" to "8.0 (\"Oreo\")",
    "25" to "7.1.1 (\"Nougat\")",
    "24" to "7.0 (\"Nougat\")",
    "23" to "6.0 (\"Marshmallow\")",
    "22" to "5.1 (\"Lollipop\")",
    "21" to "5.0 (\"Lollipop\")",
)

/** The families `sdkmanager` paths start with, named the way Studio's SDK Tools tab names them. */
private fun toolFamilyName(family: String): String = when (family) {
    "build-tools" -> "Android SDK Build-Tools"
    "cmdline-tools" -> "Android SDK Command-line Tools"
    "platform-tools" -> "Android SDK Platform-Tools"
    "emulator" -> "Android Emulator"
    "ndk" -> "NDK (Side by side)"
    "cmake" -> "CMake"
    "patcher" -> "SDK Patch Applier"
    "extras" -> "Extras"
    "skiaparser" -> "Layout Inspector image server"
    else -> family
}
