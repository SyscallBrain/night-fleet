package pt.tiago.nightfleet.ui.screens.debug

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.EffectEvent
import pt.tiago.nightfleet.ui.board.EffectKind
import pt.tiago.nightfleet.ui.board.MiniBoard
import pt.tiago.nightfleet.ui.board.ReconMark
import pt.tiago.nightfleet.ui.board.ReducedMotionProvider
import pt.tiago.nightfleet.ui.board.SeaBoard
import pt.tiago.nightfleet.ui.board.ShakeLevel
import pt.tiago.nightfleet.ui.board.ShipOutline
import pt.tiago.nightfleet.ui.board.SonarMark
import pt.tiago.nightfleet.ui.board.WeatherKind
import pt.tiago.nightfleet.ui.board.WeatherLayer
import pt.tiago.nightfleet.ui.board.screenShake
import pt.tiago.nightfleet.ui.theme.FleetTheme
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId

/**
 * Debug gallery of the board kit. It shows every mark, two sunk ships
 * (one seaplane), a revealed ship, sonar and recon marks, the own mini sea, every effect, the weather and
 * the four themes. It is how the visuals are checked on the phone.
 *
 * Debug builds only: a long press on the brand in Home opens it.
 */
@Composable
fun BoardGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier, onDiagnostics: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    var theme by rememberSaveable { mutableStateOf(ThemeId.CHART) }
    var forceReduced by rememberSaveable { mutableStateOf(false) }
    FleetTheme(themeId = theme) {
        ReducedMotionProvider(override = if (forceReduced) true else null) {
            GalleryContent(
                theme = theme,
                onThemeSelected = { theme = it },
                forceReduced = forceReduced,
                onForceReducedChanged = { forceReduced = it },
                onBack = onBack,
                onDiagnostics = onDiagnostics,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun GalleryContent(
    theme: ThemeId,
    onThemeSelected: (ThemeId) -> Unit,
    forceReduced: Boolean,
    onForceReducedChanged: (Boolean) -> Unit,
    onBack: () -> Unit,
    onDiagnostics: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var effects by remember { mutableStateOf<List<EffectEvent>>(emptyList()) }
    var playCount by remember { mutableIntStateOf(0) }
    var shake by remember { mutableLongStateOf(0L) }
    var weather by rememberSaveable { mutableStateOf(WeatherKind.CALM) }
    var lastTap by rememberSaveable { mutableStateOf<String?>(null) }
    val enemy = remember { enemySample() }

    fun play(kind: EffectKind, cells: List<Int>, autoWater: List<Int> = emptyList(), isRow: Boolean = true) {
        playCount++
        effects = listOf(EffectEvent(kind, cells, seed = playCount.toLong(), autoWater = autoWater, isRow = isRow))
    }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.gallery_back), style = type.button) }
            Text(stringResource(R.string.gallery_title), style = type.screenTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        }
        OutlinedButton(onClick = onDiagnostics) { Text(stringResource(R.string.diag_title), style = type.button) }
        Text(stringResource(R.string.gallery_hint), style = type.caption, color = colors.textMuted)

        SectionTitle(R.string.gallery_section_theme)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeId.entries.chunked(2).forEach { pair ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { option ->
                        FilterChip(
                            selected = option == theme,
                            onClick = { onThemeSelected(option) },
                            label = { Text(stringResource(themeLabel(option)), style = type.caption) },
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.gallery_reduced), style = type.bodyText, color = colors.text, modifier = Modifier.weight(1f))
            Switch(checked = forceReduced, onCheckedChange = onForceReducedChanged)
        }

        SectionTitle(R.string.gallery_section_enemy)
        Box(Modifier.fillMaxWidth()) {
            WeatherLayer(weather, Modifier.matchParentSize())
            SeaBoard(
                model = enemy,
                themeId = theme,
                effects = effects,
                onCellTap = { index -> lastTap = coordinate(index, enemy.width) },
                modifier = Modifier.fillMaxWidth().screenShake(shake, ShakeLevel.SUNK),
            )
        }
        lastTap?.let { Text(stringResource(R.string.gallery_last_tap, it), style = type.caption, color = colors.textMuted) }

        SectionTitle(R.string.gallery_section_effects)
        EffectButtons(onPlay = { kind ->
            when (kind) {
                EffectKind.SPLASH -> play(kind, listOf(44))
                EffectKind.EXPLOSION -> play(kind, listOf(45))
                EffectKind.SINK -> play(kind, listOf(20, 21, 22, 23), autoWater = listOf(10, 11, 12, 13, 14, 15, 24, 25, 26, 27))
                EffectKind.SONAR_PULSE -> play(kind, listOf(34))
                EffectKind.TORPEDO_TRAIL -> play(kind, (60..65).toList())
                EffectKind.RECON_SHADOW -> play(kind, (20..29).toList(), isRow = true)
                EffectKind.CROSS -> play(kind, listOf(44, 34, 54, 43, 45))
                EffectKind.MINE_FLASH -> play(kind, listOf(77))
            }
        }, onShake = { shake = System.nanoTime() })

        SectionTitle(R.string.gallery_section_weather)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(WeatherKind.entries) { kind ->
                FilterChip(
                    selected = kind == weather,
                    onClick = { weather = kind },
                    label = { Text(stringResource(weatherLabel(kind)), style = type.caption) },
                )
            }
        }

        SectionTitle(R.string.gallery_section_own)
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniBoard(model = ownSample(), themeId = theme)
            Text(stringResource(R.string.gallery_own_note), style = type.caption, color = colors.textMuted, modifier = Modifier.weight(1f))
        }

        SectionTitle(R.string.gallery_section_sizes)
        Text(stringResource(R.string.gallery_size_label, 8), style = type.caption, color = colors.textMuted)
        SeaBoard(model = squareSample(8), themeId = theme, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.gallery_size_label, 12), style = type.caption, color = colors.textMuted)
        SeaBoard(model = squareSample(12), themeId = theme, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SectionTitle(textRes: Int) {
    val type = LocalFleetType.current
    val colors = LocalFleetColors.current
    Text(stringResource(textRes), style = type.label, color = colors.textMuted)
}

@Composable
private fun EffectButtons(onPlay: (EffectKind) -> Unit, onShake: () -> Unit) {
    val rows = EffectKind.entries.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { kind ->
                    Button(
                        onClick = { onPlay(kind) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalFleetColors.current.surface2,
                            contentColor = LocalFleetColors.current.text,
                        ),
                    ) { Text(stringResource(effectLabel(kind)), style = LocalFleetType.current.button) }
                }
            }
        }
        Button(
            onClick = onShake,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = LocalFleetColors.current.surface2,
                contentColor = LocalFleetColors.current.text,
            ),
        ) { Text(stringResource(R.string.gallery_effect_shake), style = LocalFleetType.current.button) }
    }
}

private fun coordinate(index: Int, width: Int): String {
    val col = index % width
    val row = index / width
    return "${'A' + col}${row + 1}"
}

private fun themeLabel(id: ThemeId): Int = when (id) {
    ThemeId.CHART -> R.string.theme_chart
    ThemeId.SONAR -> R.string.theme_sonar
    ThemeId.PAPER -> R.string.theme_paper
    ThemeId.HOLO -> R.string.theme_holo
}

private fun effectLabel(kind: EffectKind): Int = when (kind) {
    EffectKind.SPLASH -> R.string.gallery_effect_splash
    EffectKind.EXPLOSION -> R.string.gallery_effect_explosion
    EffectKind.SINK -> R.string.gallery_effect_sink
    EffectKind.SONAR_PULSE -> R.string.gallery_effect_sonar
    EffectKind.TORPEDO_TRAIL -> R.string.gallery_effect_torpedo
    EffectKind.RECON_SHADOW -> R.string.gallery_effect_recon
    EffectKind.CROSS -> R.string.gallery_effect_cross
    EffectKind.MINE_FLASH -> R.string.gallery_effect_mine
}

private fun weatherLabel(kind: WeatherKind): Int = when (kind) {
    WeatherKind.CALM -> R.string.gallery_weather_calm
    WeatherKind.RAIN -> R.string.gallery_weather_rain
    WeatherKind.FOG -> R.string.gallery_weather_fog
    WeatherKind.AURORA -> R.string.gallery_weather_aurora
    WeatherKind.SNOW -> R.string.gallery_weather_snow
    WeatherKind.STORM -> R.string.gallery_weather_storm
    WeatherKind.ALGAE -> R.string.gallery_weather_algae
    WeatherKind.STARS -> R.string.gallery_weather_stars
    WeatherKind.DEEP -> R.string.gallery_weather_deep
    WeatherKind.GULLS -> R.string.gallery_weather_gulls
    WeatherKind.MOON -> R.string.gallery_weather_moon
}

// SAMPLE DATA for the gallery only: fixed cells, never game state.

/** 10×10 enemy sea with every mark, two sunk ships (a straight one and a seaplane) and a revealed ship. */
private fun enemySample(): BoardRenderModel {
    val cells = MutableList(100) { CellVisual.UNKNOWN }
    val row0 = listOf(
        CellVisual.UNKNOWN, CellVisual.WATER, CellVisual.AUTO_WATER, CellVisual.HIT, CellVisual.CONTACT,
        CellVisual.MINE, CellVisual.DECOY_WRECK, CellVisual.REPAIRED, CellVisual.ISLAND, CellVisual.REEF,
    )
    row0.forEachIndexed { col, visual -> cells[col] = visual }
    listOf(CellVisual.OWN_SHIP, CellVisual.OWN_SHIP_HIT).forEachIndexed { col, visual -> cells[10 + col] = visual }
    // Sunk straight ship on row 2, columns 0..3.
    for (col in 0..3) cells[20 + col] = CellVisual.SUNK
    // Sunk seaplane: bar on row 5 (columns 5..7) with a wing on row 4, column 6.
    for (index in listOf(46, 55, 56, 57)) cells[index] = CellVisual.SUNK
    // Revealed ship at the end of the battle.
    cells[81] = CellVisual.REVEALED_SHIP
    cells[82] = CellVisual.REVEALED_SHIP
    return BoardRenderModel(
        width = 10,
        height = 10,
        cells = cells,
        ships = listOf(
            ShipOutline(cells = listOf(20, 21, 22, 23), sunk = true),
            ShipOutline(cells = listOf(46, 55, 56, 57), sunk = true),
            ShipOutline(cells = listOf(81, 82), sunk = false, revealed = true),
        ),
        sonarReadings = listOf(SonarMark(centerIndex = 34, count = 3, fresh = true), SonarMark(centerIndex = 77, count = 0, fresh = false)),
        recon = listOf(ReconMark(isRow = true, index = 2, present = true), ReconMark(isRow = false, index = 5, present = false)),
        lastShot = 3,
        selected = 33,
        highlight = setOf(40, 41),
        fadedMisses = mapOf(1 to 0.4f),
    )
}

/** 10×10 own sea: a 5-cell carrier, a 3-cell cruiser (vertical) and a 2-cell destroyer, one of them hit. */
private fun ownSample(): BoardRenderModel {
    val cells = MutableList(100) { CellVisual.UNKNOWN }
    val carrier = (0..4).toList()
    val cruiser = listOf(20, 30, 40)
    val destroyer = listOf(88, 89)
    (carrier + cruiser + destroyer).forEach { cells[it] = CellVisual.OWN_SHIP }
    cells[2] = CellVisual.OWN_SHIP_HIT
    cells[30] = CellVisual.OWN_SHIP_HIT
    cells[55] = CellVisual.WATER
    cells[66] = CellVisual.AUTO_WATER
    return BoardRenderModel(
        width = 10,
        height = 10,
        cells = cells,
        ships = listOf(
            ShipOutline(carrier, sunk = false),
            ShipOutline(cruiser, sunk = false),
            ShipOutline(destroyer, sunk = false),
        ),
    )
}

/** Square sample board: water and hits in a pattern and one straight ship. */
private fun squareSample(size: Int): BoardRenderModel {
    val cells = MutableList(size * size) { index ->
        val row = index / size
        val col = index % size
        when {
            (row + col) % 7 == 0 -> CellVisual.HIT
            (row * 3 + col) % 5 == 0 -> CellVisual.WATER
            else -> CellVisual.UNKNOWN
        }
    }
    val ship = (0 until minOf(4, size)).map { size + it }
    ship.forEach { cells[it] = CellVisual.SUNK }
    return BoardRenderModel(
        width = size,
        height = size,
        cells = cells,
        ships = listOf(ShipOutline(ship, sunk = true)),
    )
}
