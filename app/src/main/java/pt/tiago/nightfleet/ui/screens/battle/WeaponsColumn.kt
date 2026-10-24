package pt.tiago.nightfleet.ui.screens.battle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.core.weapons.WeaponConfig
import pt.tiago.nightfleet.game.battle.BattleMatch
import pt.tiago.nightfleet.game.battle.BattleUi
import pt.tiago.nightfleet.game.battle.Recharge
import pt.tiago.nightfleet.game.battle.Targeting
import pt.tiago.nightfleet.game.battle.TimelineNotice
import pt.tiago.nightfleet.game.battle.NoticeKind
import pt.tiago.nightfleet.game.battle.WeaponCatalog
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The weapons strip under the enemy sea: one chip per weapon enabled in the rules, then the move
 * chip. The chips scroll sideways, so no name breaks inside a word. The description of the selected weapon, or
 * the reason it cannot fire, shows in one caption line below the chips.
 */
@Composable
fun WeaponsStrip(
    ui: BattleUi,
    onPickWeapon: (Weapon) -> Unit,
    onPickMove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val rules = ui.match.state.rules
    val sides = ui.match.state.sides[ui.viewer]
    val myTurn = ui.myTurn && !ui.busy
    val selectedWeapon = (ui.targeting as? Targeting.Square)?.weapon ?: (ui.targeting as? Targeting.Line)?.weapon
    // A plain-fire mission has nothing to pick: no title over an empty strip.
    if (ui.weapons.isEmpty() && !rules.movingShips) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.battle_weapons_title), style = type.label, color = colors.textMuted)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ui.weapons.forEach { weapon ->
                val charges = WeaponCatalog.charges(sides.charges, weapon)
                WeaponChip(
                    weapon = weapon,
                    charges = charges,
                    selected = selectedWeapon == weapon,
                    charged = ui.notice?.kind == NoticeKind.CHARGE_EARNED && ui.notice?.weapon == weapon,
                    enabled = myTurn && !WeaponCatalog.isEmpty(charges),
                    onClick = { onPickWeapon(weapon) },
                )
            }
            if (rules.movingShips) {
                val moveUsed = sides.moveUsed
                MoveChip(
                    selected = ui.targeting is Targeting.Move,
                    enabled = myTurn && !moveUsed,
                    onClick = onPickMove,
                )
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 18.dp)) {
            if (selectedWeapon != null) {
                val charges = WeaponCatalog.charges(sides.charges, selectedWeapon)
                Text(
                    if (charges > 0) stringResource(weaponHint(selectedWeapon)) else rechargeHint(rules.weapons[selectedWeapon]),
                    style = type.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A weapon chip: icon, name and charges on one line, at least 48 dp tall. The state is spoken with the name. */
@Composable
private fun WeaponChip(
    weapon: Weapon,
    charges: Int,
    selected: Boolean,
    charged: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    val name = stringResource(weaponName(weapon))
    val countText = stringResource(R.string.battle_charges, charges)
    val state = when {
        selected -> stringResource(R.string.battle_weapon_state_selected)
        WeaponCatalog.isEmpty(charges) -> stringResource(R.string.battle_weapon_state_empty)
        else -> stringResource(R.string.battle_weapon_state_ready)
    }
    val highlighted = selected || charged
    val tint = if (enabled) colors.accent else colors.textMuted
    Surface(
        shape = RoundedCornerShape(shapes.button),
        color = colors.surface,
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) colors.accent else colors.line),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$name, $countText, $state" },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WeaponIcon(weapon, tint, Modifier.size(20.dp))
            Text(
                name,
                style = type.caption,
                color = if (enabled) colors.text else colors.textMuted,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(countText, style = type.label, color = colors.textMuted, maxLines = 1, softWrap = false)
        }
    }
}

/** The move chip, shown when the rules allow moving ships. */
@Composable
private fun MoveChip(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val shapes = LocalFleetShapes.current
    Surface(
        shape = RoundedCornerShape(shapes.button),
        color = colors.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.line),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else WeaponCatalog.EMPTY_ALPHA)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.battle_move_button),
                style = type.caption,
                color = colors.text,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rechargeHint(config: WeaponConfig?): String = when (val recharge = WeaponCatalog.recharge(config)) {
    Recharge.Sink -> stringResource(R.string.battle_recharge_sink)
    is Recharge.Streak -> stringResource(R.string.battle_recharge_streak, recharge.n)
    is Recharge.Every -> pluralStringResource(R.plurals.battle_recharge_every, recharge.n, recharge.n)
    Recharge.None -> stringResource(R.string.battle_recharge_none)
}

/**
 * A small vector drawing of a weapon, in one tint: sonar rings, a torpedo with its arrow, a recon eye,
 * the cross of the cross shell, the ring of the repair and the diamond of the decoy.
 */
@Composable
fun WeaponIcon(weapon: Weapon, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val centre = Offset(w / 2f, h / 2f)
        val line = 1.6.dp.toPx()
        val thin = Stroke(width = line, cap = StrokeCap.Round)
        when (weapon) {
            Weapon.SONAR -> {
                drawCircle(tint, radius = w * 0.1f, center = centre)
                drawCircle(tint, radius = w * 0.3f, center = centre, style = thin)
                drawCircle(tint, radius = w * 0.46f, center = centre, style = thin)
            }
            Weapon.TORPEDO -> {
                drawLine(tint, Offset(w * 0.06f, h / 2f), Offset(w * 0.78f, h / 2f), strokeWidth = line, cap = StrokeCap.Round)
                val tip = Path().apply {
                    moveTo(w * 0.96f, h / 2f)
                    lineTo(w * 0.7f, h * 0.2f)
                    lineTo(w * 0.7f, h * 0.8f)
                    close()
                }
                drawPath(tip, tint)
            }
            Weapon.RECON -> {
                drawOval(tint, topLeft = Offset(w * 0.06f, h * 0.3f), size = Size(w * 0.88f, h * 0.4f), style = thin)
                drawCircle(tint, radius = w * 0.12f, center = centre)
            }
            Weapon.CROSS_SHELL -> {
                drawLine(tint, Offset(w / 2f, h * 0.1f), Offset(w / 2f, h * 0.9f), strokeWidth = line * 2f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.1f, h / 2f), Offset(w * 0.9f, h / 2f), strokeWidth = line * 2f, cap = StrokeCap.Round)
            }
            Weapon.REPAIR -> {
                drawCircle(tint, radius = w * 0.44f, center = centre, style = thin)
                drawLine(tint, Offset(w / 2f, h * 0.28f), Offset(w / 2f, h * 0.72f), strokeWidth = line * 1.6f, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.28f, h / 2f), Offset(w * 0.72f, h / 2f), strokeWidth = line * 1.6f, cap = StrokeCap.Round)
            }
            Weapon.DECOY -> {
                val diamond = Path().apply {
                    moveTo(w / 2f, h * 0.12f)
                    lineTo(w * 0.88f, h / 2f)
                    lineTo(w / 2f, h * 0.88f)
                    lineTo(w * 0.12f, h / 2f)
                    close()
                }
                drawPath(diamond, tint, style = thin)
            }
        }
    }
}

/** Name resource of a weapon. */
internal fun weaponName(weapon: Weapon): Int = when (weapon) {
    Weapon.SONAR -> R.string.battle_weapon_sonar
    Weapon.TORPEDO -> R.string.battle_weapon_torpedo
    Weapon.RECON -> R.string.battle_weapon_recon
    Weapon.CROSS_SHELL -> R.string.battle_weapon_cross
    Weapon.REPAIR -> R.string.battle_weapon_repair
    Weapon.DECOY -> R.string.battle_weapon_decoy
}

/** One-line description resource of a weapon, shown in the button when it has charges. */
internal fun weaponHint(weapon: Weapon): Int = when (weapon) {
    Weapon.SONAR -> R.string.battle_weapon_sonar_hint
    Weapon.TORPEDO -> R.string.battle_weapon_torpedo_hint
    Weapon.RECON -> R.string.battle_weapon_recon_hint
    Weapon.CROSS_SHELL -> R.string.battle_weapon_cross_hint
    Weapon.REPAIR -> R.string.battle_weapon_repair_hint
    Weapon.DECOY -> R.string.battle_weapon_decoy_hint
}

/**
 * The line that replaces the sea's header while a weapon or a salvo is being aimed: the salvo count, the
 * row/column and margin chips of a torpedo or recon, the repair and move prompts, and the arrows of a move.
 */
@Composable
fun TargetingPanel(
    ui: BattleUi,
    onFireSalvo: () -> Unit,
    onClearSalvo: () -> Unit,
    onFlipAxis: () -> Unit,
    onFlipMargin: () -> Unit,
    onCancel: () -> Unit,
    onMoveStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    when (val target = ui.targeting) {
        Targeting.Idle -> Unit
        is Targeting.Salvo -> {
            val required = target.required
            Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    pluralStringResource(R.plurals.battle_salvo_header, required, required, target.picked.size),
                    style = type.caption,
                    color = colors.text,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(stringResource(R.string.battle_salvo_fire), enabled = target.picked.size == required, onClick = onFireSalvo)
                    Chip(stringResource(R.string.battle_salvo_clear), enabled = true, onClick = onClearSalvo)
                }
            }
        }
        is Targeting.Square -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (target.weapon == Weapon.REPAIR) R.string.battle_repair_pick else R.string.battle_square_pick),
                style = type.caption,
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            Chip(stringResource(R.string.battle_cancel), enabled = true, onClick = onCancel)
        }
        is Targeting.Line -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (target.anchor == null) stringResource(R.string.battle_line_pick) else stringResource(R.string.battle_line_confirm),
                style = type.caption,
                color = colors.textMuted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    stringResource(if (target.isRow) R.string.battle_axis_row else R.string.battle_axis_column),
                    enabled = target.anchor != null,
                    onClick = onFlipAxis,
                )
                if (target.weapon == Weapon.TORPEDO) {
                    Chip(marginLabel(target.isRow, target.fromStart), enabled = target.anchor != null, onClick = onFlipMargin)
                }
                Chip(stringResource(R.string.battle_cancel), enabled = true, onClick = onCancel)
            }
        }
        is Targeting.Move -> Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target.shipId == null) {
                Text(stringResource(R.string.battle_move_pick), style = type.caption, color = colors.textMuted, modifier = Modifier.weight(1f))
            } else {
                Text(stringResource(R.string.battle_move_choose), style = type.caption, color = colors.text, modifier = Modifier.weight(1f))
                if (-1 in target.deltas) Chip(stringResource(R.string.battle_move_back_arrow), enabled = true) { onMoveStep(-1) }
                if (1 in target.deltas) Chip(stringResource(R.string.battle_move_forward_arrow), enabled = true) { onMoveStep(1) }
            }
            Chip(stringResource(R.string.battle_cancel), enabled = true, onClick = onCancel)
        }
    }
}

/** The margin chip: which edge the torpedo leaves from, in words that fit the line. */
@Composable
private fun marginLabel(isRow: Boolean, fromStart: Boolean): String = when {
    isRow && fromStart -> stringResource(R.string.battle_margin_left)
    isRow -> stringResource(R.string.battle_margin_right)
    fromStart -> stringResource(R.string.battle_margin_top)
    else -> stringResource(R.string.battle_margin_bottom)
}

@Composable
private fun Chip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Surface(
        shape = RoundedCornerShape(LocalFleetShapes.current.button),
        color = if (enabled) colors.surface2 else colors.surface,
        border = BorderStroke(1.dp, colors.line),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            label,
            style = type.caption,
            color = if (enabled) colors.text else colors.textMuted,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The shot clock ring drawn around the pause button: the arc is the share of the turn left, red in the last
 * three seconds. Draws nothing when the clock is off.
 */
@Composable
fun ClockRing(ui: BattleUi, modifier: Modifier = Modifier) {
    val clock = ui.clock
    if (!clock.enabled) return
    val colors = LocalFleetColors.current
    val track = colors.line
    val arc = if (clock.danger) colors.danger else colors.accent
    val fraction = clock.fraction
    Canvas(modifier.size(52.dp)) {
        drawClockRing(fraction, track, arc)
    }
}

internal fun DrawScope.drawClockRing(fraction: Float, track: Color, arc: Color) {
    val stroke = 3.dp.toPx()
    val inset = stroke / 2f
    val size = Size(this.size.width - stroke, this.size.height - stroke)
    drawArc(
        color = track,
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = size,
        style = Stroke(width = stroke),
    )
    drawArc(
        color = arc,
        startAngle = -90f,
        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(inset, inset),
        size = size,
        style = Stroke(width = stroke),
    )
}

/** The text of the notice of a moment (the log line while its animation plays). */
@Composable
fun noticeText(notice: TimelineNotice): String = when (notice.kind) {
    NoticeKind.SONAR -> pluralStringResource(R.plurals.notice_sonar, notice.count ?: 0, notice.count ?: 0)
    NoticeKind.RECON_PRESENT -> stringResource(R.string.notice_recon_present)
    NoticeKind.RECON_EMPTY -> stringResource(R.string.notice_recon_empty)
    NoticeKind.TORPEDO_BLOCKED -> stringResource(R.string.notice_torpedo_blocked)
    NoticeKind.MINE_PLAYER -> stringResource(R.string.notice_mine_player)
    NoticeKind.MINE_ADMIRAL -> stringResource(R.string.notice_mine_admiral)
    NoticeKind.TURN_SKIPPED -> stringResource(if (notice.own) R.string.notice_skipped_own else R.string.notice_skipped_admiral)
    NoticeKind.DECOY_REVEALED -> stringResource(R.string.notice_decoy)
    NoticeKind.ENEMY_REPAIRED -> stringResource(R.string.notice_enemy_repaired)
    NoticeKind.ENEMY_MOVED -> stringResource(R.string.notice_enemy_moved)
    NoticeKind.OWN_MOVED -> stringResource(R.string.notice_own_moved)
    NoticeKind.SUBMERGED_CONTACT -> stringResource(R.string.notice_submerged)
    NoticeKind.SUBMARINES_DIVED -> stringResource(R.string.notice_dived)
    NoticeKind.SUBMARINES_SURFACED -> stringResource(R.string.notice_surfaced)
    NoticeKind.CHARGE_EARNED -> stringResource(R.string.notice_charge, notice.weapon?.let { stringResource(weaponName(it)) }.orEmpty())
    NoticeKind.CROSS -> stringResource(R.string.notice_cross)
}

/** Refusal reason codes of the engine and the controller, as words. Unknown codes get a generic line. */
@Composable
fun refusalText(code: String): String = stringResource(refusalRes(code))

internal fun refusalRes(code: String): Int = when (code) {
    "BATTLE_OVER" -> R.string.refusal_battle_over
    "OUT_OF_BOUNDS" -> R.string.refusal_out_of_bounds
    "NOT_A_TARGET" -> R.string.refusal_not_a_target
    "ALREADY_FIRED" -> R.string.battle_already_fired
    "SALVO_NOT_ENABLED" -> R.string.refusal_generic
    "SALVO_SIZE" -> R.string.refusal_salvo_size
    "SALVO_DUPLICATE" -> R.string.refusal_salvo_size
    "NO_TARGET" -> R.string.refusal_no_target
    "BAD_TARGET" -> R.string.refusal_no_target
    "WEAPON_NOT_AVAILABLE" -> R.string.refusal_weapon_off
    "NO_CHARGE" -> R.string.refusal_no_charge
    "REPAIR_ALREADY_USED" -> R.string.refusal_repair_used
    "NOT_REPAIRABLE" -> R.string.refusal_not_repairable
    "MOVE_NOT_ENABLED" -> R.string.refusal_move_off
    "MOVE_ALREADY_USED" -> R.string.refusal_move_used
    "BAD_SHIP" -> R.string.refusal_generic
    "BAD_DELTA" -> R.string.refusal_generic
    "SHIP_DAMAGED" -> R.string.refusal_ship_damaged
    "MOVE_BLOCKED" -> R.string.refusal_move_blocked
    else -> R.string.refusal_generic
}
