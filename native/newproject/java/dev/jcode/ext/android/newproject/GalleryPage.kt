package dev.jcode.ext.android.newproject

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ControlSize
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.ManagerFilterChip
import dev.blamspot.jcode.design.ManagerNoticeCard
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.SettingsDropdownRow
import dev.blamspot.jcode.design.SettingsTextFieldRow
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.ext.api.NativeHost
import kotlinx.coroutines.launch

/** Which of the wizard's screens is showing. */
private enum class Step { Gallery, Configure, Working }

/**
 * New Android Project: pick a template by looking at it, then configure it.
 *
 * Two screens rather than one long form, because they answer different questions — *what kind of
 * app* and *what it is called* — and putting them together is what makes a New Project dialog a wall
 * of fields you scroll past. The gallery is where the choice is obvious; configuration is where it
 * is specific.
 */
@Composable
internal fun GalleryPage(host: NativeHost, initialName: String = "", modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // A dialog rather than a page. Creating a project is a detour from whatever you were doing, and
    // it ends by opening something else — an editor tab that has to be closed afterwards is a tab
    // for a thing that no longer exists. The workbench still routes the action to a view; this
    // closes that view behind itself when the dialog goes.
    var open by remember { mutableStateOf(true) }
    var step by remember { mutableStateOf(Step.Gallery) }
    var category by remember { mutableStateOf(Category.PhoneAndTablet) }
    var selected by remember { mutableStateOf<Template?>(null) }
    var config by remember { mutableStateOf(Config()) }
    var working by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }

    if (!open) return

    fun dismiss() {
        // Ask first, hide second: the request rides the page's own coroutine scope, and flipping the
        // flag before sending takes this composable out of the tree with it. The view is closed by
        // the same id it was opened with — name and all, since that is part of the tab's identity.
        host.closeView(if (initialName.isBlank()) VIEW_ID else "$VIEW_ID:$initialName")
        open = false
    }

    fun create() {
        val template = selected ?: return
        failure = null
        step = Step.Working
        scope.launch {
            when (val r = Scaffold.run(host, template, config) { working = it }) {
                is Scaffold.Result.Created -> {
                    host.snackbar("Created ${config.folder}.")
                    // Opened rather than merely created: the point of the wizard is to arrive in the
                    // project, not to be told it exists somewhere. `openFolder` is the route for a
                    // folder already sitting under the workspace mount, which is where the scaffold
                    // put it; `addFolder` adopts one out of /sources staging and would refuse this.
                    // The wizard closes itself: opening a project keeps page tabs, this one among
                    // them, and a tab for a dialog that has served its purpose is a tab for a thing
                    // that no longer exists.
                    host.openFolder(r.projectDir)
                    dismiss()
                }
                is Scaffold.Result.Failed -> {
                    failure = r.message to r.log
                    step = Step.Configure
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (step != Step.Working) dismiss() },
        title = {
            Text(
                when (step) {
                    Step.Gallery -> "New Android Project"
                    Step.Configure -> selected?.name ?: "Configure"
                    Step.Working -> "Creating…"
                },
            )
        },
        text = {
        when (step) {
            Step.Gallery -> Gallery(
                category = category,
                onCategory = { category = it },
                onPick = { template ->
                    selected = template
                    config = Config(
                        name = initialName,
                        // A package name from the project's own name, the way Android Studio does
                        // it, rather than making everybody edit com.example.app by hand.
                        applicationId = "com.example." + (
                            initialName.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "app" }
                            ),
                    )
                    failure = null
                    step = Step.Configure
                },
            )

            Step.Configure -> Configure(
                template = selected,
                config = config,
                onConfig = { config = it },
                failure = failure,
            )

            Step.Working -> Working(working)
        }
        },
        confirmButton = {
            when (step) {
                Step.Configure -> CompactFilledButton(
                    text = "Create",
                    enabled = config.isValid,
                    onClick = { create() },
                )
                // Nothing to confirm while picking: tapping a card is the choice. And nothing to
                // press while it works — the dialog is not dismissable then either, because the
                // scaffold is mid-flight and there would be nothing to cancel it with.
                else -> Unit
            }
        },
        dismissButton = {
            when (step) {
                Step.Gallery -> CompactOutlinedButton(text = "Cancel", onClick = { dismiss() })
                Step.Configure -> CompactOutlinedButton(
                    text = "Back",
                    onClick = { step = Step.Gallery; failure = null },
                )
                Step.Working -> Unit
            }
        },
    )
}

/** The view the workbench opens for this pack's contributed action; closed when the dialog goes. */
private const val VIEW_ID = "newAndroidProject"

@Composable
private fun Gallery(category: Category, onCategory: (Category) -> Unit, onPick: (Template) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Space.sm)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Templates.categories.forEach { c ->
                ManagerFilterChip(selected = c == category, label = c.label, onClick = { onCategory(c) })
            }
        }
        val entries = Templates.inCategory(category)
        // Two columns on a phone-width drawer, which is what this page mostly opens in. A grid rather
        // than a list because the picture is the point: a list of the same cards one per row turns
        // the gallery back into the radio buttons it replaces.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            // Bounded: a grid in a dialog has no height of its own to fill, and an unbounded one
            // measures to nothing.
            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            items(entries, key = { it.id }) { template ->
                TemplateCard(template = template, onClick = { onPick(template) })
            }
        }
    }
}

@Composable
private fun TemplateCard(template: Template, onClick: () -> Unit) {
    val ready = template.script != null
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(Radius.lg),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = StrokeWidth.thin,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(Radius.lg),
            )
            .clickable(enabled = ready, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            TemplatePreview(template.art)
            Text(
                template.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (ready) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (ready) template.description else "Not available in this pack yet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun Configure(
    template: Template?,
    config: Config,
    onConfig: (Config) -> Unit,
    failure: Pair<String, String>?,
) {
    if (template == null) return
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            template.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingsTextFieldRow(
            label = "Name",
            value = config.name,
            onValueChange = { onConfig(config.copy(name = it)) },
            placeholder = "MyApplication",
        )
        SettingsTextFieldRow(
            label = "Package name",
            value = config.applicationId,
            onValueChange = { onConfig(config.copy(applicationId = it)) },
            supporting = "The application id, e.g. com.example.app.",
        )
        SettingsDropdownRow(
            label = "Minimum SDK",
            options = Config.MIN_SDKS,
            selected = config.minSdk,
            onSelect = { onConfig(config.copy(minSdk = it)) },
            supporting = "How far back the app runs. API " + config.minSdk + " and above.",
        )
        failure?.let { (message, log) ->
            ManagerNoticeCard(
                title = "Could not create the project",
                message = if (log.isBlank()) message else "$message\n\n${log.takeLast(400)}",
            )
        }
        if (!config.isValid) {
            Text(
                when {
                    config.folder.isEmpty() -> "Give the project a name."
                    else -> "The package name needs at least two parts, e.g. com.example.app."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Working(step: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(ControlSize.iconButtonSm),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSize.lg),
                strokeWidth = StrokeWidth.thick,
            )
        }
        Text(
            step.ifBlank { "Working…" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "The first Android project on a device takes a few minutes: the SDK's template is " +
                "unpacked and the project is written out.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
