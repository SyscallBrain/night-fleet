package pt.tiago.nightfleet.ui.screens.freeplay

import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.game.battle.WeaponCatalog
import pt.tiago.nightfleet.game.freeplay.FleetChoice
import pt.tiago.nightfleet.game.freeplay.FreePlayConfig
import pt.tiago.nightfleet.game.freeplay.OpponentLevel
import pt.tiago.nightfleet.game.freeplay.FreePlayAdmirals
import pt.tiago.nightfleet.game.freeplay.Starter
import pt.tiago.nightfleet.ui.screens.battle.weaponName
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The free play setup: opponent, difficulty, grid, fleet, rules, mines, weapons, shot clock and
 * who starts. Every change goes to [onChange]; [onStart] places the fleet.
 */
@Composable
fun FreePlayScreen(
    config: FreePlayConfig,
    onChange: ((FreePlayConfig) -> FreePlayConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.bg, colors.bg2))),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onBack) { Text(stringResource(R.string.freeplay_back), style = type.button, color = colors.text) }
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.freeplay_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
            }

            Section(R.string.freeplay_section_opponent) {
                AdmiralDropdown(config.admiralId, onPick = { id -> onChange { it.copy(admiralId = id) } })
            }
            Section(R.string.freeplay_section_level) {
                ChoiceRow(OpponentLevel.entries, config.level, { levelLabel(it) }) { level -> onChange { it.copy(level = level) } }
            }
            Section(R.string.freeplay_section_grid) {
                ChoiceRow(FreePlayConfig.GRID_SIZES, config.grid, { gridLabel(it) }) { grid -> onChange { it.copy(grid = grid) } }
            }
            Section(R.string.freeplay_section_fleet) {
                ChoiceRow(FleetChoice.entries, config.fleet, { fleetLabel(it) }) { fleet -> onChange { it.copy(fleet = fleet) } }
                Text(stringResource(R.string.freeplay_fleet_note), style = type.caption, color = colors.textMuted)
            }
            Section(R.string.freeplay_section_rules) {
                ToggleRow(R.string.freeplay_islands, config.islands) { on -> onChange { it.copy(islands = on) } }
                ToggleRow(R.string.freeplay_salvo, config.salvo) { on -> onChange { it.copy(salvo = on) } }
                ToggleRow(R.string.freeplay_diving, config.diving) { on -> onChange { it.copy(diving = on) } }
                ToggleRow(R.string.freeplay_fog, config.fog) { on -> onChange { it.copy(fog = on) } }
                ToggleRow(R.string.freeplay_moving, config.movingShips) { on -> onChange { it.copy(movingShips = on) } }
            }
            Section(R.string.freeplay_section_mines) {
                MineStepper(config.minesPerSide) { count -> onChange { it.copy(minesPerSide = count) } }
            }
            Section(R.string.freeplay_section_weapons) {
                WeaponChips(config.weapons) { weapon, on ->
                    onChange { current ->
                        current.copy(weapons = if (on) current.weapons + weapon else current.weapons - weapon)
                    }
                }
            }
            Section(R.string.freeplay_section_clock) {
                ChoiceRow(listOf<Int?>(null) + FreePlayConfig.SHOT_CLOCKS, config.shotClock, { clockLabel(it) }) { seconds ->
                    onChange { it.copy(shotClock = seconds) }
                }
            }
            Section(R.string.freeplay_section_starter) {
                ChoiceRow(Starter.entries, config.starter, { starterLabel(it) }) { starter -> onChange { it.copy(starter = starter) } }
            }

            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(LocalFleetShapes.current.button),
                color = colors.accent,
                contentColor = colors.onAccent,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStart),
            ) {
                Text(
                    stringResource(R.string.freeplay_start),
                    style = type.button,
                    color = colors.onAccent,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun Section(titleRes: Int, content: @Composable () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(titleRes), style = type.label, color = colors.textMuted)
        content()
    }
}

/** A row of chips that picks one value of [options]. */
@Composable
internal fun <T> ChoiceRow(options: List<T>, selected: T, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    // A chip that does not fit moves to the next line instead of breaking its word (for example "Implacável").
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onPick(option) },
                label = { Text(label(option), maxLines = 1, softWrap = false) },
            )
        }
    }
}

@Composable
internal fun ToggleRow(labelRes: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), style = type.bodyText, color = colors.text, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun MineStepper(count: Int, onChange: (Int) -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val fewer = stringResource(R.string.freeplay_mines_fewer)
    val more = stringResource(R.string.freeplay_mines_more)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = { onChange((count - 1).coerceAtLeast(0)) },
            enabled = count > 0,
            modifier = Modifier.semantics { contentDescription = fewer },
        ) { Text("−", style = type.button, color = colors.text) }
        Text("$count", style = type.hudShots, color = colors.text)
        OutlinedButton(
            onClick = { onChange((count + 1).coerceAtMost(FreePlayConfig.MAX_MINES)) },
            enabled = count < FreePlayConfig.MAX_MINES,
            modifier = Modifier.semantics { contentDescription = more },
        ) { Text("+", style = type.button, color = colors.text) }
    }
}

@Composable
internal fun WeaponChips(selected: Set<Weapon>, onToggle: (Weapon, Boolean) -> Unit) {
    val weapons = listOf(Weapon.SONAR, Weapon.TORPEDO, Weapon.RECON, Weapon.CROSS_SHELL, Weapon.REPAIR, Weapon.DECOY)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        weapons.forEach { weapon -> WeaponChip(weapon, weapon in selected, onToggle) }
    }
}

@Composable
private fun WeaponChip(weapon: Weapon, selected: Boolean, onToggle: (Weapon, Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(weapon, !selected) },
        label = { Text(stringResource(weaponName(weapon)), maxLines = 1, softWrap = false) },
    )
}

/** The admiral dropdown: the twelve admirals of the campaign. */
@Composable
private fun AdmiralDropdown(selectedId: String, onPick: (String) -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var open by remember { mutableStateOf(false) }
    val selected = stringResource(admiralNameRes(selectedId))
    Box {
        Surface(
            shape = RoundedCornerShape(LocalFleetShapes.current.button),
            color = colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.line),
            modifier = Modifier.fillMaxWidth().clickable { open = true },
        ) {
            Text(selected, style = type.bodyText, color = colors.text, modifier = Modifier.padding(12.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FreePlayAdmirals.IDS.forEach { id ->
                DropdownMenuItem(
                    text = { Text(stringResource(admiralNameRes(id))) },
                    onClick = {
                        open = false
                        onPick(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun levelLabel(level: OpponentLevel): String = stringResource(
    when (level) {
        OpponentLevel.EASY -> R.string.freeplay_level_easy
        OpponentLevel.MEDIUM -> R.string.freeplay_level_medium
        OpponentLevel.HARD -> R.string.freeplay_level_hard
        OpponentLevel.RELENTLESS -> R.string.freeplay_level_relentless
    },
)

@Composable
private fun fleetLabel(fleet: FleetChoice): String = stringResource(
    when (fleet) {
        FleetChoice.CLASSIC -> R.string.freeplay_fleet_classic
        FleetChoice.SCHOOL -> R.string.freeplay_fleet_school
    },
)

@Composable
private fun starterLabel(starter: Starter): String = stringResource(
    when (starter) {
        Starter.PLAYER -> R.string.freeplay_starter_player
        Starter.ADMIRAL -> R.string.freeplay_starter_admiral
        Starter.RANDOM -> R.string.freeplay_starter_random
    },
)

@Composable
private fun gridLabel(grid: Int): String = stringResource(R.string.freeplay_grid_label, grid, grid)

@Composable
private fun clockLabel(seconds: Int?): String =
    if (seconds == null) stringResource(R.string.freeplay_clock_off) else stringResource(R.string.freeplay_clock_seconds, seconds)
