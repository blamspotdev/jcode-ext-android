package dev.jcode.ext.android.designer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The inspector's inputs, sized for an inspector.
 *
 * Two things are borrowed from JCode's own Settings and one is deliberately not.
 *
 * **Borrowed: the box.** A translucent `surfaceVariant` fill, a hairline `outlineVariant` border and
 * a 10dp corner — the same recipe `SettingsTextFieldRow` uses — so a field here reads as part of the
 * same app rather than as a Material default dropped into it. Written out rather than imported,
 * because `dev.jcode.design` is not part of the extension ABI: the only compile contract a plugin
 * has is `:core:ext-api`, and reaching past it would make this break on a JCode release that
 * refactors its own design system.
 *
 * **Borrowed: the height.** Settings' field is a 40dp box around a `BasicTextField`, not a 56dp
 * `OutlinedTextField` — which is where the bulk came from, before its floating label adds more.
 *
 * **Not borrowed: the label above the field.** Settings is a page you scroll through slowly; this is
 * a panel beside a canvas holding a dozen properties at once, so the label sits beside its value and
 * a property costs one row instead of three.
 */
@Composable
internal fun InspectorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    dirty: Boolean,
    onCommit: (String) -> Unit,
    onRevert: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    monospace: Boolean = false,
    /** A closed-ish vocabulary for this value, offered in a menu. Empty means free text. */
    options: List<String> = emptyList(),
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LABEL_WIDTH),
        )
        FieldBox(
            modifier = Modifier.weight(1f),
            trailing = if (options.isEmpty()) {
                null
            } else {
                {
                    Box {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = "Choose a value",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp).clickable { menu = true },
                        )
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            options.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(option, style = MaterialTheme.typography.bodySmall)
                                    },
                                    // Picking commits straight away. Typing needs a confirmation
                                    // because a half-typed value is not a value; choosing one from a
                                    // list of valid values already is.
                                    onClick = {
                                        menu = false
                                        onValueChange(option)
                                        onCommit(option)
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) {
            FieldText(value, onValueChange, placeholder, monospace)
        }
        // Icons rather than "Apply" and "Revert": committing is a fixed pair of actions on a row
        // that is already narrow, and two words would cost more width than the value they follow.
        if (dirty) {
            TinyIcon(Icons.Rounded.Check, "Apply", MaterialTheme.colorScheme.primary) { onCommit(value) }
            TinyIcon(Icons.Rounded.Close, "Revert", MaterialTheme.colorScheme.onSurfaceVariant, onRevert)
        }
    }
}

/** The palette's filter, in the same box as everything else. */
@Composable
internal fun InspectorSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    FieldBox(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            FieldText(value, onValueChange, placeholder, monospace = false)
        }
    }
}

/**
 * A labelled switch, one row tall.
 *
 * The switch keeps its own size and only its *touch target* is relaxed, from the 48dp Material
 * enforces to 36dp. That minimum exists for good reasons and is not worth discarding entirely —
 * but three of these in a panel beside a canvas were taller than the canvas controls above them,
 * and 36dp is still comfortably tappable.
 */
@Composable
internal fun InspectorToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides ROW_HEIGHT) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun FieldBox(
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = ROW_HEIGHT).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { content() }
            trailing?.invoke()
        }
    }
}

@Composable
private fun FieldText(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    monospace: Boolean,
) {
    Box(contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty() && placeholder.isNotEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = if (monospace) FontFamily.Monospace else null,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TinyIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Wide enough for `layout_width` and `verticalArrangement`, narrow enough to leave a value room. */
private val LABEL_WIDTH = 96.dp

/**
 * One row, whether it holds a field or a switch.
 *
 * Shared rather than tuned per control: fields and toggles sit directly above one another in the
 * inspector, and two heights a couple of dp apart is a ragged edge nobody chose. The number is the
 * switch's — 36dp is the smallest touch target worth keeping, so it is the one that cannot move,
 * and it is still four short of what Settings gives a field on its own page.
 */
private val ROW_HEIGHT = 36.dp
