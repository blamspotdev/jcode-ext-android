package dev.jcode.ext.android.sdkmanager

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.Dp
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
    /** Group rows the user has opened. Per row rather than one "show package details" switch: a
     *  platform carries sources, system images and add-ons, and expanding all of them at once to
     *  read one of them buries the table it was meant to explain. */
    var expanded by remember { mutableStateOf(setOf<String>()) }
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
            // Only while there is already a table to refresh. The first read has nothing to keep on
            // screen, so it says so in the body and the corner stays a button; showing both was two
            // spinners for one wait.
            refreshing = loading && snapshot != null,
            // Nothing to refresh part-way through an apply, and the body is a progress screen for
            // the duration — a control that cannot be used is better absent than inert.
            canRefresh = progress == null,
            onRefresh = { scope.launch { reload() } },
        )

        val snap = snapshot
        when {
            progress != null -> ApplyProgress(progress!!, Modifier.weight(1f))

            failure is SdkManagerCatalog.NoSdkInstalled -> ManagerNoticeCard(
                title = "The Android SDK is not installed",
                message = "Install it from Toolchains → SDKs → Android SDK. This page manages the " +
                    "packages inside an SDK that is already here; it cannot create one.",
            )

            failure != null -> ManagerNoticeCard(
                title = "Could not read the SDK",
                message = failure!!.message ?: "sdkmanager did not answer.",
            )

            // Before `loading`, so a refresh leaves the table where it was instead of blanking it
            // and jumping back to the top. Only the very first read falls past this.
            snap != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Tab.entries.forEach { t ->
                        ManagerFilterChip(selected = tab == t, label = t.label, onClick = { tab = t })
                    }
                }
                // Keyed on the tab, so each arrives in the order that suits it — platforms newest
                // first, tools by name — and a sort chosen on one does not follow you to the other.
                var sort by remember(tab) { mutableStateOf(tab.defaultSort) }
                val rows = remember(snap, tab, expanded, sort) { rowsFor(snap, tab, expanded, sort) }
                PackageTable(
                    modifier = Modifier.weight(1f),
                    rows = rows,
                    columns = tab.columns,
                    onExpand = { row ->
                        expanded = if (row.key in expanded) expanded - row.key else expanded + row.key
                    },
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

            // The first read only: every later one keeps the table above and spins in the corner.
            loading -> Box(Modifier.fillMaxWidth().padding(Space.lg), Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
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
 * install waits behind a decision — an explicit one: Accept stays dead until the terms have been
 * scrolled to their end, so nobody agrees by reflex on the way to a download. Terms short enough to
 * fit without scrolling are already read to the end, and enable it straight away.
 */
@Composable
private fun LicenceDialog(
    licences: List<SdkManagerApply.Licence>,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val terms = rememberScrollState()
    val readToEnd by remember { derivedStateOf { terms.value >= terms.maxValue } }
    AlertDialog(
        onDismissRequest = onDecline,
        title = {
            Text(
                if (licences.size == 1) "Accept the Android SDK licence"
                else "Accept ${licences.size} Android SDK licences",
            )
        },
        text = {
            Column {
                Text(
                    "These packages are covered by terms you have not accepted yet. Nothing is " +
                        "downloaded until you agree to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Framed top and bottom, so the terms read as a box that scrolls rather than as text
                // running into the paragraph above it once it has moved.
                HorizontalDivider(modifier = Modifier.padding(vertical = Space.sm))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(terms),
                ) {
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
                if (!readToEnd) {
                    HorizontalDivider(modifier = Modifier.padding(top = Space.sm))
                    Text(
                        text = if (licences.size == 1) "Scroll to the end of the licence to accept it."
                        else "Scroll to the end of the licences to accept them.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Space.sm),
                    )
                }
            }
        },
        confirmButton = {
            CompactFilledButton(text = "Accept", onClick = onAccept, enabled = readToEnd)
        },
        dismissButton = { CompactOutlinedButton(text = "Decline", onClick = onDecline) },
    )
}

/**
 * A tab, and the columns beside the name.
 *
 * The last column is always Status, rendered from [PackageRow.status] rather than from its cells —
 * so a row supplies one fewer cell than there are columns here.
 */
private enum class Tab(val label: String, val columns: List<Col>, val defaultSort: Sort) {
    /** Grouped by API level, the way Studio's collapsed list is — which is what makes a row able to
     *  be *partially* installed. Newest first, which is what somebody looking for a platform wants. */
    Platforms(
        "SDK Platforms",
        listOf(Col("API Level", 76.dp), Col("Revision", 64.dp), Col("Status", 104.dp)),
        Sort(1, ascending = false),
    ),
    Tools("SDK Tools", listOf(Col("Version", 84.dp), Col("Status", 104.dp)), Sort(0, ascending = true)),
    ;

    /** Column 0 is the name; 1..n are [columns]. */
    val statusColumn: Int get() = columns.size
}

/** Which column the table is ordered by, and which way. */
private data class Sort(val column: Int, val ascending: Boolean)

/**
 * A column beside the name, and how much room it takes.
 *
 * Sized per column rather than one width for all of them. Three 96dp columns plus a checkbox and a
 * disclosure leave about 19dp for the name on a phone, which renders every row as `Androi…` — the
 * one column that says what the row *is* squeezed out by two that hold `1` and `37.2`.
 */
private data class Col(val label: String, val width: Dp)

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
    /** Whether this row has packages of its own worth showing. A family of one has nothing to open
     *  into -- its single child would repeat the row it hangs under. */
    val expandable: Boolean = false,
    val expanded: Boolean = false,
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
 * Collapsed, a platform row is an API level and everything that belongs to it; opened, it is the
 * packages themselves underneath. Tools group by family for the same reason — nobody scanning for
 * the NDK wants eleven build-tools revisions in the way. [open] holds the groups somebody has
 * unfolded, so opening one to read it leaves the rest of the table as it was.
 */
private fun rowsFor(
    snap: SdkManagerCatalog.Snapshot,
    tab: Tab,
    open: Set<String>,
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
                val key = "api-$api"
                val group = PackageRow(
                    key = key,
                    name = androidReleaseName(api),
                    cells = listOf(api, platform?.version.orEmpty()),
                    paths = pkgs.map { it.path },
                    installedPaths = pkgs.filter { it.installed }.map { it.path }.toSet(),
                    update = pkgs.any { it.update != null },
                    unusable = platform?.let { SdkManagerCatalog.unusable(it, snap.aapt2Ceiling) },
                    expandable = pkgs.size > 1,
                    expanded = key in open,
                )
                group to if (group.expandable && group.expanded) pkgs.map { leaf(it, indent = true) }
                else emptyList()
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
                val key = "family-$family"
                val group = PackageRow(
                    key = key,
                    // A family of one is named by the package itself: `build;templates` is a real
                    // package whose family name would otherwise read as "build".
                    name = if (pkgs.size == 1) pkgs[0].description.ifBlank { family } else toolFamilyName(family),
                    cells = listOf(face.version),
                    paths = paths,
                    installedPaths = installed.map { it.path }.toSet(),
                    update = pkgs.any { it.update != null },
                    unusable = SdkManagerCatalog.unusable(face, snap.aapt2Ceiling),
                    expandable = pkgs.size > 1,
                    expanded = key in open,
                )
                group to if (group.expandable && group.expanded)
                    pkgs.sortedWith(VERSION_ORDER.reversed()).map { leaf(it, indent = true) }
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
 * Flipped from what the checkbox is SHOWING, not from what is on disk. Reading `row.state` here made
 * the control one-way: on-disk state does not change until Apply runs, so a second tap computed the
 * same intent as the first and armed it again. Ticking something to install and thinking better of
 * it re-ticked it; unticking an installed package and changing your mind re-queued the removal.
 * Nothing could be taken back except Discard, which takes back everything.
 *
 * [pendingState] is that displayed state -- what is pending, over what is installed -- and with no
 * pending entries it equals [PackageRow.state], so a first tap behaves exactly as it did.
 *
 * Only the packages that would actually change are recorded: asking to install one already installed
 * is not a change, and handing it to `sdkmanager` makes it download the thing again. So an entry is
 * dropped as soon as the desired state matches what is on disk, which is also what makes "Discard"
 * and the pending count mean something -- and is what lets a tap back to the original state clear
 * the row rather than record a no-op.
 */
private fun toggled(pending: Map<String, Boolean>, row: PackageRow): Map<String, Boolean> {
    val wantInstalled = pendingState(row, pending) != ToggleableState.On
    val next = pending.toMutableMap()
    for (path in row.paths) {
        if (wantInstalled == (path in row.installedPaths)) next.remove(path) else next[path] = wantInstalled
    }
    return next
}

@Composable
private fun Header(refreshing: Boolean, canRefresh: Boolean, onRefresh: () -> Unit) {
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
        // An icon, not a labelled button: it sits beside a title that already says what the page
        // is, and the word "Refresh" was the widest thing in the header on a phone.
        if (!canRefresh) Unit
        // `size`, not `width`: constraining one axis leaves the other at the indicator's default,
        // which drew a tall squashed arc and rode up above the title because the row centres on the
        // height it was given.
        else if (refreshing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        else IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = "Refresh",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PackageTable(
    rows: List<PackageRow>,
    columns: List<Col>,
    pending: Map<String, Boolean>,
    onToggle: (PackageRow) -> Unit,
    onExpand: (PackageRow) -> Unit,
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
                // Both leading columns, or the heading does not sit over what it heads: the rows
                // put a checkbox between the disclosure and the name, and without a spacer for it
                // "Name" sat over the checkboxes with the names starting a checkbox-width to its
                // right.
                Box(modifier = Modifier.width(DISCLOSURE))
                Box(modifier = Modifier.padding(start = Space.xxs).width(CHECKBOX))
                HeaderCell("Name", 0, sort, onSort, Modifier.weight(1f))
                columns.forEachIndexed { index, column ->
                    HeaderCell(column.label, index + 1, sort, onSort, Modifier.width(column.width))
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
                    // Its own hit target, ahead of the checkbox. Opening a group and choosing a
                    // group are different intentions, and a row that did both on one tap would make
                    // reading what is inside impossible without also selecting it.
                    Box(
                        modifier = Modifier.width(DISCLOSURE),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (row.expandable) {
                            Icon(
                                imageVector = if (row.expanded) Icons.Rounded.ExpandMore
                                else Icons.Rounded.ChevronRight,
                                contentDescription = if (row.expanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onExpand(row) },
                            )
                        }
                    }
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
                            // Two lines for a package inside a group. Their names differ at the
                            // end -- "Android TV ARM 64 v8a System Image" against "Android TV Intel
                            // x86 Atom_64" -- so one truncated line renders three different
                            // downloads as three identical rows.
                            maxLines = if (row.indent) 2 else 1,
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
                    row.cells.forEachIndexed { i, cell -> Cell(cell, columns[i].width) }
                    Cell(row.status, columns.last().width)
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
        // No indication. The default paints a filled, rounded box the size of the label, which on
        // this screen is the shape the tab chips directly above already use for "selected" — so the
        // last header touched sat there looking like a chosen filter, and on a pointer it stays lit
        // for as long as the cursor rests there. The sort is already reported where it belongs: the
        // arrow moves to this column and its label goes semibold, immediately, on the same tap.
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onSort(column) }
            .padding(vertical = Space.hairline),
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
            // Weighted, so the ARROW is measured first and the label takes what is left. Unweighted
            // it was measured first, took the column whole, and left the marker nowhere to go: on
            // "Revision", the narrowest column, the label going semibold on selection was enough to
            // push the arrow out of the line and drop the pair below its neighbours' baseline. The
            // column that shows the sort is the one that must not break when it does.
            modifier = Modifier.weight(1f, fill = false),
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
private fun Cell(text: String, width: Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width),
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

/** The disclosure column, held open on every row so names start at one x whether or not the
 *  row has anything to open. */
private val DISCLOSURE = 20.dp

/**
 * The checkbox column, mirrored in the header so "Name" sits over the names.
 *
 * 40 rather than the 48 of a Material touch target: the checkbox is laid out inside its own minimum
 * size and does not fill it, so 48 pushed the heading eight dp past the text it heads. Measured
 * against the rendered table rather than derived, because the widget's own padding is what decides.
 */
private val CHECKBOX = 40.dp

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
