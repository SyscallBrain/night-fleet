package pt.tiago.nightfleet.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The frame of every meta screen: gradient background, safe insets, a back button and a title.
 * Scrollable screens get a verticalScroll column; lazy lists pass `scrollable = false` and use weight.
 */
@Composable
fun MetaScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        val gutter: Dp = if (maxWidth < 360.dp) 16.dp else 20.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                            tint = colors.text,
                        )
                    }
                    Text(
                        title,
                        style = type.screenTitle,
                        color = colors.text,
                        modifier = Modifier.padding(start = 4.dp).semantics { heading() },
                    )
                }
                val body: Modifier = Modifier.fillMaxWidth().weight(1f)
                if (scrollable) {
                    Column(
                        body.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        content = content,
                    )
                } else {
                    Column(body, content = content)
                }
            }
        }
    }
}

/** A card with the card radius of the theme and the thin `line` border. */
@Composable
fun MetaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    Surface(
        shape = RoundedCornerShape(shapes.card),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** A small uppercase label above a group of rows (11 sp, tracked). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LocalFleetType.current.label,
        color = LocalFleetColors.current.textMuted,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** A switch row. The whole row toggles, and the minimum height is 56 dp. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.bodyText, color = if (enabled) colors.text else colors.textMuted)
            if (subtitle != null) Text(subtitle, style = type.caption, color = colors.textMuted)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A radio row for a single choice. The whole row is the target. */
@Composable
fun RadioRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(title, style = type.bodyText, color = if (enabled) colors.text else colors.textMuted)
            if (subtitle != null) Text(subtitle, style = type.caption, color = colors.textMuted)
        }
    }
}

/** A labelled slider with its current value written on the right. */
@Composable
fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = type.bodyText, color = colors.text, modifier = Modifier.weight(1f))
            Text(valueText, style = type.coordinate.copy(fontSize = 13.sp, lineHeight = 18.sp), color = colors.textMuted)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A thin rounded bar with the accent fill. [fraction] is clamped to 0..1. */
@Composable
fun MetaProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val colors = LocalFleetColors.current
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(colors.surface2, CircleShape),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(colors.accent, CircleShape),
        )
    }
}

/** A short rounded label, used for filters, tags and difficulty chips. */
@Composable
fun TagChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, style = LocalFleetType.current.caption, color = color)
    }
}

/** A centred line for a list with nothing in it yet. */
@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = LocalFleetType.current.bodyText, color = LocalFleetColors.current.textMuted)
    }
}

/** A thin divider in the `line` colour. */
@Composable
fun MetaDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = LocalFleetColors.current.line)
}

/** A fixed-size icon slot, so text next to icons lines up in every language. */
@Composable
fun IconSlot(icon: ImageVector, description: String?, tint: Color, size: Dp = 24.dp) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(size))
}
