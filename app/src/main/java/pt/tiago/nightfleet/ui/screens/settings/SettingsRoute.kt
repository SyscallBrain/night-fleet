package pt.tiago.nightfleet.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.audio.GameAudio
import pt.tiago.nightfleet.data.AdmiralSpeed
import pt.tiago.nightfleet.data.LanguageChoice
import pt.tiago.nightfleet.data.MotionPreference
import pt.tiago.nightfleet.data.PaletteChoice
import pt.tiago.nightfleet.data.Profile
import pt.tiago.nightfleet.data.Settings
import pt.tiago.nightfleet.data.SettingsRepository
import pt.tiago.nightfleet.meta.Admirals
import pt.tiago.nightfleet.meta.Paint
import pt.tiago.nightfleet.meta.PaintCatalog
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.common.RadioRow
import pt.tiago.nightfleet.ui.common.SectionLabel
import pt.tiago.nightfleet.ui.common.SliderRow
import pt.tiago.nightfleet.ui.common.SwitchRow
import pt.tiago.nightfleet.ui.common.rankNameRes
import pt.tiago.nightfleet.ui.theme.FleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType
import pt.tiago.nightfleet.ui.theme.ThemeId
import pt.tiago.nightfleet.ui.theme.fleetColors

/**
 * The Settings screen. Every change is saved through [SettingsRepository] at once.
 * The sound sliders also update [audio] live when it is available (the Activity owns it).
 *
 * Wiring: `SettingsRoute(container = container, onBack = navController::popBackStack, audio = gameAudio)`.
 */
@Composable
fun SettingsRoute(
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    audio: GameAudio? = null,
    onOpenDiagnostics: () -> Unit = {},
) {
    val repo = container.settings
    val settings by repo.settings.collectAsState(initial = Settings())
    val profile by container.profile.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val systemReduced = remember(context) { prefersReducedMotion(context) }
    val save: (suspend SettingsRepository.() -> Unit) -> Unit = { change -> scope.launch { repo.change() } }

    MetaScreen(title = stringResource(R.string.set_title), onBack = onBack, modifier = modifier) {
        SectionLabel(stringResource(R.string.set_sec_sound))
        SoundCard(settings, audio, save)

        SectionLabel(stringResource(R.string.set_sec_game))
        GameCard(settings, save)

        SectionLabel(stringResource(R.string.set_sec_appearance))
        AppearanceCard(settings, profile, container, save)

        SectionLabel(stringResource(R.string.set_sec_access))
        AccessibilityCard(settings, systemReduced, save)

        SectionLabel(stringResource(R.string.set_sec_language))
        LanguageCard(settings, context, save)

        SectionLabel(stringResource(R.string.set_sec_data))
        DataCard(container)
        LicencesCard()

        SectionLabel(stringResource(R.string.set_sec_about))
        AboutCard(onOpenDiagnostics = onOpenDiagnostics)
    }
}

@Composable
private fun SoundCard(settings: Settings, audio: GameAudio?, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    // While a slider is dragged the value lives here, so the stored value is written once, on release.
    var sfxDraft by remember { mutableStateOf<Float?>(null) }
    var musicDraft by remember { mutableStateOf<Float?>(null) }
    val sfx = sfxDraft ?: settings.sfxVolume
    val music = musicDraft ?: settings.musicVolume
    MetaCard {
        SliderRow(
            title = stringResource(R.string.set_sfx),
            valueText = "${volumePercent(sfx)} %",
            value = sfx,
            onValueChange = { sfxDraft = it; audio?.setSfxVolume(it) },
            onValueChangeFinished = {
                val value = sfxDraft ?: return@SliderRow
                sfxDraft = null
                save { setSfxVolume(value) }
            },
        )
        SliderRow(
            title = stringResource(R.string.set_music),
            valueText = "${volumePercent(music)} %",
            value = music,
            onValueChange = { musicDraft = it; audio?.setMusicVolume(it) },
            onValueChangeFinished = {
                val value = musicDraft ?: return@SliderRow
                musicDraft = null
                save { setMusicVolume(value) }
            },
        )
        SwitchRow(
            title = stringResource(R.string.set_vibration),
            checked = settings.vibration,
            onCheckedChange = { value ->
                audio?.setVibration(value)
                save { setVibration(value) }
            },
        )
    }
}

@Composable
private fun GameCard(settings: Settings, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    MetaCard {
        SwitchRow(
            title = stringResource(R.string.set_confirm),
            subtitle = stringResource(R.string.set_confirm_sub),
            checked = settings.confirmShot,
            onCheckedChange = { value -> save { setConfirmShot(value) } },
        )
        Text(stringResource(R.string.set_speed), style = LocalFleetType.current.label, color = LocalFleetColors.current.textMuted)
        RadioRow(
            title = stringResource(R.string.set_speed_normal),
            selected = settings.admiralSpeed == AdmiralSpeed.NORMAL,
            onSelect = { save { setAdmiralSpeed(AdmiralSpeed.NORMAL) } },
        )
        RadioRow(
            title = stringResource(R.string.set_speed_fast),
            selected = settings.admiralSpeed == AdmiralSpeed.FAST,
            onSelect = { save { setAdmiralSpeed(AdmiralSpeed.FAST) } },
        )
        SwitchRow(
            title = stringResource(R.string.set_swap),
            checked = settings.swapSeasOnAdmiralTurn,
            onCheckedChange = { value -> save { setSwapSeasOnAdmiralTurn(value) } },
        )
        SwitchRow(
            title = stringResource(R.string.set_shake),
            checked = settings.screenShake,
            onCheckedChange = { value -> save { setScreenShake(value) } },
        )
        SwitchRow(
            title = stringResource(R.string.set_left),
            subtitle = stringResource(R.string.set_left_sub),
            checked = settings.leftHanded,
            onCheckedChange = { value -> save { setLeftHanded(value) } },
        )
    }
}

@Composable
private fun AppearanceCard(
    settings: Settings,
    profile: Profile,
    container: AppContainer,
    save: (suspend SettingsRepository.() -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val unlocked = PaintCatalog.grandAdmiralFinishUnlocked(profile)
    val selectedTheme = settings.theme.toThemeId()

    Text(stringResource(R.string.set_theme), style = type.label, color = colors.textMuted)
    val themes = listOf(
        ThemeId.CHART to R.string.set_theme_chart,
        ThemeId.SONAR to R.string.set_theme_sonar,
        ThemeId.PAPER to R.string.set_theme_paper,
        ThemeId.HOLO to R.string.set_theme_holo,
    )
    themes.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { (id, label) ->
                ThemeOptionCard(
                    themeId = id,
                    label = stringResource(label),
                    selected = selectedTheme == id,
                    onSelect = { save { setTheme(id.toChoice()) } },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    MetaCard {
        SwitchRow(
            title = stringResource(R.string.set_grand),
            subtitle = if (unlocked) {
                stringResource(R.string.set_grand_on)
            } else {
                stringResource(R.string.set_grand_locked, PaintCatalog.hardBossesMissing(profile))
            },
            checked = settings.grandAdmiralActive(profile),
            enabled = unlocked,
            onCheckedChange = { value -> save { setGrandAdmiralFinish(value) } },
        )
        SwitchRow(
            title = stringResource(R.string.set_weather),
            subtitle = stringResource(R.string.set_weather_sub),
            checked = settings.weatherEffects,
            onCheckedChange = { value -> save { setWeatherEffects(value) } },
        )
    }

    MetaCard {
        Text(stringResource(R.string.set_paint), style = type.label, color = colors.textMuted)
        val unlockedPaints = PaintCatalog.unlockedIds(profile)
        val selectPaint: (Paint) -> Unit = { paint ->
            scope.launch {
                container.profile.update { it.copy(selectedPaint = paint.id) }
                container.settings.update { it.copy(fleetPaint = paint.id) }
            }
        }
        RadioRow(
            title = stringResource(R.string.set_paint_default),
            selected = profile.selectedPaint == PaintCatalog.DEFAULT_ID,
            onSelect = { selectPaint(PaintCatalog.defaultPaint) },
        )
        Text(stringResource(R.string.set_paint_admirals), style = type.caption, color = colors.textMuted)
        PaintCatalog.bossPaints.forEach { paint ->
            val admiral = Admirals.byId(paint.admiralId.orEmpty())
            val name = admiral?.let { stringResource(it.name) }.orEmpty()
            val available = paint.id in unlockedPaints
            RadioRow(
                title = name,
                subtitle = if (available) null else stringResource(R.string.set_paint_boss_locked, name),
                selected = profile.selectedPaint == paint.id,
                enabled = available,
                onSelect = { selectPaint(paint) },
            )
        }
        Text(stringResource(R.string.set_paint_ranks), style = type.caption, color = colors.textMuted)
        PaintCatalog.rankPaints.forEach { paint ->
            val rank = paint.rank!!
            val name = stringResource(rankNameRes(rank))
            val available = paint.id in unlockedPaints
            RadioRow(
                title = name,
                subtitle = if (available) null else stringResource(R.string.set_paint_rank_locked, name),
                selected = profile.selectedPaint == paint.id,
                enabled = available,
                onSelect = { selectPaint(paint) },
            )
        }
    }
}

@Composable
private fun AccessibilityCard(settings: Settings, systemReduced: Boolean, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    MetaCard {
        Text(stringResource(R.string.set_palette), style = type.label, color = colors.textMuted)
        PaletteChoice.entries.forEach { choice ->
            PaletteRow(
                name = stringResource(paletteNameRes(choice)),
                palette = fleetColors(settings.theme.toThemeId(), choice.toPalette()),
                selected = settings.palette == choice,
                onSelect = { save { setPalette(choice) } },
            )
        }
        Text(stringResource(R.string.set_motion), style = type.label, color = colors.textMuted)
        MotionPreference.entries.forEach { choice ->
            RadioRow(
                title = stringResource(motionNameRes(choice)),
                subtitle = if (choice == MotionPreference.FOLLOW_SYSTEM) {
                    stringResource(if (systemReduced) R.string.set_motion_system_reduced else R.string.set_motion_system_normal)
                } else {
                    null
                },
                selected = settings.reducedMotion == choice,
                onSelect = { save { setReducedMotion(choice) } },
            )
        }
        SwitchRow(
            title = stringResource(R.string.set_larger),
            checked = settings.largerText,
            onCheckedChange = { value -> save { setLargerText(value) } },
        )
        SwitchRow(
            title = stringResource(R.string.set_contrast),
            checked = settings.highContrastGrid,
            onCheckedChange = { value -> save { setHighContrastGrid(value) } },
        )
    }
}

/** A palette option with three swatches (hit, miss, accent) so the choice can be seen before it is made. */
@Composable
private fun PaletteRow(name: String, palette: FleetColors, selected: Boolean, onSelect: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, style = type.bodyText, color = colors.text, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(palette.hit, palette.miss, palette.accent).forEach { swatch ->
                Box(Modifier.size(14.dp).background(swatch, CircleShape))
            }
        }
    }
}

@Composable
private fun LanguageCard(settings: Settings, context: Context, save: (suspend SettingsRepository.() -> Unit) -> Unit) {
    MetaCard {
        LanguageChoice.entries.forEach { choice ->
            RadioRow(
                title = stringResource(languageNameRes(choice)),
                selected = settings.language == choice,
                onSelect = {
                    save { setLanguage(choice) }
                    // Applies at once on API 33+. Below that, the cached choice is read by AppLocale.wrap after a recreate.
                    AppLocale.apply(context, choice)
                    if (AppLocale.needsRecreate(Build.VERSION.SDK_INT)) context.findActivity()?.recreate()
                },
            )
        }
    }
}

/** Reads the system "remove animations" switch. Animator scale 0 is what the system sets for it. */
private fun prefersReducedMotion(context: Context): Boolean = runCatching {
    AndroidSettings.Global.getFloat(context.contentResolver, AndroidSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun paletteNameRes(choice: PaletteChoice): Int = when (choice) {
    PaletteChoice.NORMAL -> R.string.set_palette_normal
    PaletteChoice.DEUTERANOPIA -> R.string.set_palette_deuteranopia
    PaletteChoice.PROTANOPIA -> R.string.set_palette_protanopia
    PaletteChoice.TRITANOPIA -> R.string.set_palette_tritanopia
}

private fun motionNameRes(choice: MotionPreference): Int = when (choice) {
    MotionPreference.FOLLOW_SYSTEM -> R.string.set_motion_system
    MotionPreference.ON -> R.string.set_motion_on
    MotionPreference.OFF -> R.string.set_motion_off
}

private fun languageNameRes(choice: LanguageChoice): Int = when (choice) {
    LanguageChoice.SYSTEM -> R.string.set_lang_system
    LanguageChoice.EN -> R.string.set_lang_en
    LanguageChoice.PT_PT -> R.string.set_lang_pt
}
