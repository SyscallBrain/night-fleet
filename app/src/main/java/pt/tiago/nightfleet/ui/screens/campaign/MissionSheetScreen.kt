package pt.tiago.nightfleet.ui.screens.campaign

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.catalog.CatalogResult
import pt.tiago.nightfleet.core.content.MissionDto
import pt.tiago.nightfleet.core.content.MissionKind
import pt.tiago.nightfleet.core.content.MissionTag
import pt.tiago.nightfleet.core.weapons.Weapon
import pt.tiago.nightfleet.data.Difficulty
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.variantKey
import pt.tiago.nightfleet.game.campaign.CampaignRules
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.game.campaign.CampaignSetup
import pt.tiago.nightfleet.ui.intro.IntroCardSheet
import pt.tiago.nightfleet.ui.intro.IntroCardTracker
import pt.tiago.nightfleet.ui.intro.MechanicIntro
import pt.tiago.nightfleet.ui.intro.MissionIntros
import pt.tiago.nightfleet.ui.intro.introCardsSeen
import pt.tiago.nightfleet.ui.intro.withIntroCardsSeen
import pt.tiago.nightfleet.meta.Seas
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetShapes
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The mission sheet: title, type, objective, the rules and weapons as chips (each chip
 * explains itself in one line), the star thresholds, the best result and the Play button.
 */
@Composable
fun MissionSheetScreen(
    container: AppContainer,
    mission: Int,
    difficulty: Difficulty,
    onPlay: () -> Unit,
    onBack: () -> Unit,
) {
    var dto by remember(mission) { mutableStateOf<MissionDto?>(null) }
    var profile by remember(mission) { mutableStateOf<Profile?>(null) }
    /** The one-line explanation of the chip that was tapped (a string resource). */
    var explained by remember(mission) { mutableStateOf<Int?>(null) }
    LaunchedEffect(mission) {
        dto = (container.catalog.mission(Seas.missionId(CampaignRules.seaOf(mission), mission)) as? CatalogResult.Ok)?.value
        profile = container.profile.load()
    }
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val mission0 = dto
    val progress = profile
    val variant = mission0?.variants?.get(CampaignSetup.difficultyOf(difficulty))
    val scope = rememberCoroutineScope()
    /** Intro cards still to show before the battle starts, one after another. */
    var introQueue by remember(mission) { mutableStateOf<List<MechanicIntro>>(emptyList()) }
    val tracker = remember(progress) {
        progress?.let { loaded ->
            IntroCardTracker(
                seen = loaded.introCardsSeen(),
                onSeen = { seen -> scope.launch { container.profile.update { it.withIntroCardsSeen(seen) } } },
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.campaign_back)) }
        if (mission0 == null || variant == null || progress == null) {
            Text(stringResource(R.string.campaign_loading), style = type.bodyText)
            return@Column
        }
        val kind = mission0.type
        Text(stringResource(R.string.campaign_mission_number, mission), style = type.label, color = colors.textMuted)
        Text(CampaignText.missionTitle(kind, mission0.tags), style = type.seaName, color = colors.text, modifier = Modifier.semantics { heading() })
        Text(stringResource(CampaignText.kindRes(kind)), style = type.caption, color = colors.textMuted)
        Text(stringResource(objectiveRes(kind)), style = type.bodyText, color = colors.text)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            mission0.tags.forEach { tag ->
                AssistChip(
                    onClick = { explained = explainRes(tag) },
                    label = { Text(stringResource(CampaignText.tagRes(tag))) },
                )
            }
            variant.weapons.keys.mapNotNull { name -> Weapon.entries.firstOrNull { it.name == name } }.forEach { weapon ->
                AssistChip(
                    onClick = { explained = weaponHintRes(weapon) },
                    label = { Text(stringResource(weaponNameRes(weapon))) },
                )
            }
        }
        explained?.let { Text(stringResource(it), style = type.caption, color = colors.textMuted) }

        val thresholds = variant.stars
        if (thresholds != null) {
            Text(
                pluralStringResource(R.plurals.campaign_sheet_thresholds, thresholds.three, thresholds.three, thresholds.two),
                style = type.bodyText,
                color = colors.text,
            )
        }
        val best = progress.variants[variantKey(mission0.id, difficulty)]?.bestShots
        Text(
            if (best != null) pluralStringResource(R.plurals.campaign_sheet_best, best, best) else stringResource(R.string.campaign_sheet_no_best),
            style = type.caption,
            color = colors.textMuted,
        )
        val unlocked = CampaignRules.isUnlocked(progress, mission, difficulty)
        if (!unlocked) {
            Text(stringResource(R.string.campaign_sheet_locked), style = type.caption, color = colors.textMuted)
        }
        Button(
            onClick = {
                val pending = tracker?.pendingFor(MissionIntros.mechanicsOf(mission0.tags, mission0.type)).orEmpty()
                if (pending.isEmpty()) onPlay() else introQueue = pending
            },
            enabled = unlocked,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.campaign_sheet_play))
        }
    }
    introQueue.firstOrNull()?.let { card ->
        IntroOverlay(card) {
            tracker?.markSeen(card)
            val rest = introQueue.drop(1)
            introQueue = rest
            if (rest.isEmpty()) onPlay()
        }
    }
    }
}

/** The intro card of one mechanic, in a sheet over the mission sheet. */
@Composable
private fun IntroOverlay(card: MechanicIntro, onDismiss: () -> Unit) {
    val colors = LocalFleetColors.current
    val shapes = LocalFleetShapes.current
    Box(
        Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.72f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(topStart = shapes.sheet, topEnd = shapes.sheet))
                .padding(20.dp),
        ) {
            IntroCardSheet(mechanic = card, onDismiss = onDismiss)
        }
    }
}

private fun objectiveRes(kind: MissionKind): Int = when (kind) {
    MissionKind.DUEL -> R.string.campaign_objective_duel
    MissionKind.HUNT -> R.string.campaign_objective_hunt
    MissionKind.CONVOY -> R.string.campaign_objective_convoy
    MissionKind.BOSS -> R.string.campaign_objective_boss
    MissionKind.PUZZLE -> R.string.campaign_objective_puzzle
}

/** One line that explains a rule chip (the introduction cards have the longer text). */
private fun explainRes(tag: MissionTag): Int = when (tag) {
    MissionTag.ISLANDS, MissionTag.REEFS -> R.string.intro_islands_reefs_line1
    MissionTag.MINES -> R.string.intro_mines_line1
    MissionTag.TIMED -> R.string.intro_shot_clock_line1
    MissionTag.SALVO -> R.string.intro_salvo_line1
    MissionTag.DIVING -> R.string.intro_diving_subs_line1
    MissionTag.FOG -> R.string.intro_fog_line1
    MissionTag.MOVING -> R.string.intro_moving_ships_line1
    MissionTag.SCHOOL -> R.string.intro_school_rules_line1
    MissionTag.LARGE -> R.string.intro_large_grid_line1
    MissionTag.SONAR -> R.string.intro_sonar_line1
    MissionTag.TORPEDO -> R.string.intro_torpedo_line1
    MissionTag.RECON -> R.string.intro_air_recon_line1
    MissionTag.CROSS -> R.string.intro_cross_barrage_line1
    MissionTag.DECOY -> R.string.intro_decoy_line1
    MissionTag.REPAIR -> R.string.intro_repair_line1
}

private fun weaponNameRes(weapon: Weapon): Int = when (weapon) {
    Weapon.SONAR -> R.string.battle_weapon_sonar
    Weapon.TORPEDO -> R.string.battle_weapon_torpedo
    Weapon.RECON -> R.string.battle_weapon_recon
    Weapon.CROSS_SHELL -> R.string.battle_weapon_cross
    Weapon.REPAIR -> R.string.battle_weapon_repair
    Weapon.DECOY -> R.string.battle_weapon_decoy
    else -> R.string.battle_weapon_sonar
}

private fun weaponHintRes(weapon: Weapon): Int = when (weapon) {
    Weapon.SONAR -> R.string.battle_weapon_sonar_hint
    Weapon.TORPEDO -> R.string.battle_weapon_torpedo_hint
    Weapon.RECON -> R.string.battle_weapon_recon_hint
    Weapon.CROSS_SHELL -> R.string.battle_weapon_cross_hint
    Weapon.REPAIR -> R.string.battle_weapon_repair_hint
    Weapon.DECOY -> R.string.battle_weapon_decoy_hint
    else -> R.string.battle_weapon_sonar_hint
}
