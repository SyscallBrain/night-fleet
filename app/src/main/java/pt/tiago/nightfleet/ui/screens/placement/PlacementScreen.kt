package pt.tiago.nightfleet.ui.screens.placement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.fleet.ProblemCode
import pt.tiago.nightfleet.core.fleet.ShipClass
import pt.tiago.nightfleet.core.grid.Cell
import pt.tiago.nightfleet.core.grid.Terrain
import pt.tiago.nightfleet.game.battle.PlacementNotice
import pt.tiago.nightfleet.game.battle.PlacementState
import pt.tiago.nightfleet.ui.board.BOARD_GAP_DP
import pt.tiago.nightfleet.ui.board.BoardLayout
import pt.tiago.nightfleet.ui.board.BoardMetrics
import pt.tiago.nightfleet.ui.board.BoardRenderModel
import pt.tiago.nightfleet.ui.board.CellVisual
import pt.tiago.nightfleet.ui.board.SeaBoard
import pt.tiago.nightfleet.ui.board.ShipOutline
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.tutorial.tutorialAnchor

/** Callbacks of the placement screen. The state is owned by the view model. */
data class PlacementActions(
    val onPickShip: (ShipClass) -> Unit,
    val onTapSea: (Cell) -> Unit,
    val onLongPressSea: (Cell) -> Unit,
    val onAuto: () -> Unit,
    val onClear: () -> Unit,
    val onSaveFormation: () -> Unit,
    val onLoadFormation: (Int) -> Unit,
    val onDeleteFormation: (Int) -> Unit,
    val onReady: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Fleet placement. The own sea is large; the ships to place sit
 * in the dock. A dock ship is picked with a tap, then a tap on the sea drops it; a tap on a placed ship
 * turns it and a long press returns it to the dock. The same flow works with TalkBack.
 */
@Composable
fun PlacementScreen(
    state: PlacementState,
    themeId: ThemeId,
    actions: PlacementActions,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var formationsOpen by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.placement_back)) }
            Spacer(Modifier.size(12.dp))
            // One line only: the title shrinks to 18 sp on narrow screens and ellipses if it still does not fit.
            BoxWithConstraints(Modifier.weight(1f)) {
                val narrow = maxWidth < 360.dp
                Text(
                    stringResource(R.string.placement_title),
                    style = if (narrow) type.screenTitle.copy(fontSize = 18.sp, lineHeight = 24.sp) else type.screenTitle,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }

        Text(
            stringResource(if (state.dragging != null) R.string.placement_hint_select else R.string.placement_hint),
            style = type.caption,
            color = colors.textMuted,
        )

        PlacementSea(state = state, themeId = themeId, actions = actions)

        notice(state.notice)?.let { text ->
            Text(text, style = type.caption, color = colors.danger)
        }

        Text(stringResource(R.string.placement_dock), style = type.label, color = colors.textMuted)
        if (state.dock.isEmpty()) {
            Text(stringResource(R.string.placement_dock_done), style = type.bodyText, color = colors.text)
        } else {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.dock.forEach { ship ->
                    DockChip(ship = ship, selected = state.dragging == ship, onClick = { actions.onPickShip(ship) })
                }
            }
        }

        // The action row: Clear, Formations and Auto. Each takes an equal share so the three never overlap.
        val autoHint = stringResource(R.string.placement_a11y_auto)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val small = PaddingValues(horizontal = 12.dp)
            OutlinedButton(onClick = actions.onClear, modifier = Modifier.weight(1f), contentPadding = small) {
                Text(stringResource(R.string.placement_clear), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { formationsOpen = !formationsOpen },
                modifier = Modifier.weight(1f).tutorialAnchor("formations_button"),
                contentPadding = small,
            ) {
                Text(stringResource(R.string.placement_formations), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
                onClick = actions.onAuto,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
                modifier = Modifier.weight(1f).tutorialAnchor("auto_button").semantics { contentDescription = autoHint },
                contentPadding = small,
            ) {
                Text(stringResource(R.string.placement_auto), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        if (formationsOpen) FormationSlots(state, actions)

        Button(
            onClick = actions.onReady,
            enabled = state.complete,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).tutorialAnchor("ready_button"),
        ) { Text(stringResource(R.string.placement_ready), style = type.button) }
    }
}

/** The own sea with its tap layer. The layout is the same one the board draws with. */
@Composable
private fun PlacementSea(state: PlacementState, themeId: ThemeId, actions: PlacementActions) {
    val model = remember(state.placed, state.board) { placementModel(state) }
    val density = LocalDensity.current
    val onTap by rememberUpdatedState(actions.onTapSea)
    val onLong by rememberUpdatedState(actions.onLongPressSea)
    BoxWithConstraints(Modifier.fillMaxWidth().widthIn(max = 520.dp).aspectRatio(1f).tutorialAnchor("own_board")) {
        val side = with(density) { maxWidth.roundToPx() }
        val layout = remember(side, density.density, state.board.width, state.board.height) {
            BoardMetrics.compute(side, side, state.board.width, state.board.height, density.density, showCoordinates = true, gapDp = BOARD_GAP_DP)
        }
        val origin = seaOrigin(side, layout)
        SeaBoard(
            model = model,
            themeId = themeId,
            modifier = Modifier.fillMaxSize(),
            interactive = false,
        )
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(layout, origin) {
                    detectTapGestures(
                        onTap = { offset -> cellAt(layout, origin, offset)?.let { onTap(it) } },
                        onLongPress = { offset -> cellAt(layout, origin, offset)?.let { onLong(it) } },
                    )
                },
        )
    }
}

private fun cellAt(layout: BoardLayout, origin: Pair<Int, Int>, offset: Offset): Cell? {
    val index = layout.cellAt(offset.x - origin.first, offset.y - origin.second) ?: return null
    return Cell(index / layout.cols, index % layout.cols)
}

/** Top-left corner of a board of [layout] centred in a square of [side] px (the way SeaBoard centres it). */
internal fun seaOrigin(side: Int, layout: BoardLayout): Pair<Int, Int> =
    Pair((side - layout.widthPx) / 2, (side - layout.heightPx) / 2)

@Composable
private fun DockChip(ship: ShipClass, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    val type = LocalFleetType.current
    val name = stringResource(shipNameRes(ship))
    val shipHint = stringResource(R.string.placement_a11y_ship, name)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(shapes.button),
        color = if (selected) colors.accent else colors.surface,
        contentColor = if (selected) colors.onAccent else colors.text,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.line),
        modifier = Modifier.semantics {
            role = Role.Button
            contentDescription = shipHint
        },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(name, style = type.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(ship.size) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(1.dp)).background(if (selected) colors.onAccent else colors.textMuted))
                }
            }
        }
    }
}

@Composable
private fun FormationSlots(state: PlacementState, actions: PlacementActions) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.onSaveFormation,
            enabled = state.complete,
            colors = ButtonDefaults.buttonColors(containerColor = colors.surface2, contentColor = colors.text),
        ) { Text(stringResource(R.string.placement_formation_save)) }
        repeat(pt.tiago.nightfleet.data.MAX_FORMATIONS_PER_KEY) { slot ->
            val saved = slot < state.formations.size
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.placement_formation_slot, slot + 1), style = type.caption, color = colors.text, modifier = Modifier.weight(1f))
                if (saved) {
                    OutlinedButton(onClick = { actions.onLoadFormation(slot) }) { Text(stringResource(R.string.placement_formation_load)) }
                    Spacer(Modifier.size(6.dp))
                    OutlinedButton(onClick = { actions.onDeleteFormation(slot) }) { Text(stringResource(R.string.placement_formation_delete)) }
                } else {
                    Text(stringResource(R.string.placement_formation_empty), style = type.caption, color = colors.textMuted)
                }
            }
        }
    }
}

/** The own sea as a picture: terrain, every placed ship as a whole hull. Pure data. */
internal fun placementModel(state: PlacementState): BoardRenderModel {
    val board = state.board
    val cells = MutableList(board.size) { i ->
        when (board.terrainAt(i)) {
            Terrain.WATER -> CellVisual.UNKNOWN
            Terrain.ISLAND -> CellVisual.ISLAND
            Terrain.REEF -> CellVisual.REEF
        }
    }
    val outlines = state.placed.map { placement ->
        val squares = placement.cells().map { board.index(it) }
        squares.forEach { cells[it] = CellVisual.OWN_SHIP }
        ShipOutline(squares, sunk = false)
    }
    return BoardRenderModel(board.width, board.height, cells, outlines)
}

/** Stable string of a ship class. */
internal fun shipNameRes(ship: ShipClass): Int = when (ship) {
    ShipClass.CARRIER -> R.string.ship_carrier
    ShipClass.BATTLESHIP -> R.string.ship_battleship
    ShipClass.CRUISER -> R.string.ship_cruiser
    ShipClass.SUBMARINE -> R.string.ship_submarine
    ShipClass.DESTROYER -> R.string.ship_destroyer
    ShipClass.SEAPLANE -> R.string.ship_seaplane
    ShipClass.MIDGET_SUB -> R.string.ship_midget_sub
    ShipClass.FREIGHTER -> R.string.ship_freighter
}

/** Text of a placement problem code. */
internal fun problemRes(code: ProblemCode): Int = when (code) {
    ProblemCode.OVERLAP -> R.string.problem_overlap
    ProblemCode.TOUCHING -> R.string.problem_touching
    ProblemCode.TERRAIN -> R.string.problem_terrain
    ProblemCode.OUT_OF_BOUNDS -> R.string.problem_out_of_bounds
    ProblemCode.MINE -> R.string.problem_mine
    ProblemCode.MINE_NEAR_SHIP -> R.string.problem_mine_near
    ProblemCode.INCOMPLETE_FLEET -> R.string.problem_incomplete
    ProblemCode.DENSITY_OVER_25_PERCENT -> R.string.problem_density
    ProblemCode.INVALID_ROTATION -> R.string.problem_rotation
}

@Composable
private fun notice(notice: PlacementNotice?): String? = when (notice) {
    null -> null
    is PlacementNotice.Rejected -> stringResource(problemRes(notice.code))
    PlacementNotice.AutoFailed -> stringResource(R.string.notice_auto_failed)
    PlacementNotice.Incomplete -> stringResource(R.string.notice_formation_incomplete)
    PlacementNotice.FormationSaved -> stringResource(R.string.notice_formation_saved)
    PlacementNotice.FormationLoaded -> stringResource(R.string.notice_formation_loaded)
    PlacementNotice.FormationInvalid -> stringResource(R.string.notice_formation_invalid)
    PlacementNotice.FormationSlotsFull -> stringResource(R.string.notice_formation_full)
    PlacementNotice.FormationDeleted -> stringResource(R.string.notice_formation_deleted)
}
