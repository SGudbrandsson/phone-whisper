package com.kafkasl.phonewhisper.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** A titled group of settings rows on a rounded card. */
@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
        )
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column { content() }
        }
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(
    modifier = Modifier.padding(horizontal = 16.dp),
    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
)

/** One row: optional leading icon, title, supporting text, optional trailing content. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    subtitleMaxLines: Int = 2,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.5f
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor.copy(alpha = alpha),
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        below?.invoke()
    }
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = title, subtitle = subtitle, icon = icon,
        modifier = Modifier,
        onClick = { onChange(!checked) },
    ) {
        // The row handles the click; the switch only mirrors it (one click target for TalkBack).
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Small colored dot + label, used in the status card. */
@Composable
fun StatusLine(ok: Boolean, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onAction != null) Modifier.clickable(role = Role.Button, onClick = onAction) else Modifier)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(10.dp),
        ) {}
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface)
        if (action != null) {
            Box(Modifier.padding(start = 4.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
