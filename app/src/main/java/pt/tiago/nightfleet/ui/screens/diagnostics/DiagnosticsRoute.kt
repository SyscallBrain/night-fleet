package pt.tiago.nightfleet.ui.screens.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.BuildConfig
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.backup.DiagnosticsReport
import pt.tiago.nightfleet.core.ai.AiTier
import pt.tiago.nightfleet.diagnostics.DebugChecks
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.common.MetaScreen
import pt.tiago.nightfleet.ui.common.SectionLabel
import pt.tiago.nightfleet.ui.screens.settings.DataRow
import pt.tiago.nightfleet.ui.screens.settings.writeBytes
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/**
 * The debug-only screen with the on-device checks (see [DebugChecks]). Reached from Settings → About (seven taps on
 * the version) and from the board gallery. The results are kept for the diagnostics export.
 */
@Composable
fun DiagnosticsRoute(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var run by remember { mutableStateOf(DebugChecks.lastRun) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            message = runCatching {
                writeBytes(context, uri, DiagnosticsReport.build(context).toByteArray(Charsets.UTF_8))
            }.fold(onSuccess = { R.string.set_diag_done }, onFailure = { R.string.set_error_generic })
        }
    }

    MetaScreen(title = stringResource(R.string.diag_title), onBack = onBack, modifier = modifier) {
        if (!BuildConfig.DEBUG) {
            Text(stringResource(R.string.diag_release_only), style = type.bodyText, color = colors.textMuted)
            return@MetaScreen
        }
        MetaCard {
            Text(stringResource(R.string.diag_sub), style = type.caption, color = colors.textMuted)
            DataRow(
                title = stringResource(R.string.diag_run),
                subtitle = stringResource(R.string.diag_run_sub),
                enabled = !running,
                onClick = {
                    running = true
                    message = null
                    scope.launch {
                        val finished = DebugChecks.runAll(context)
                        DebugChecks.lastRun = finished
                        run = finished
                        running = false
                    }
                },
            )
            if (running) Text(stringResource(R.string.diag_running), style = type.caption, color = colors.text)
            DataRow(
                title = stringResource(R.string.diag_export),
                subtitle = stringResource(R.string.diag_export_sub),
                enabled = !running,
                onClick = { exportLauncher.launch("nightfleet-diagnostics.txt") },
            )
            message?.let { Text(stringResource(it), style = type.caption, color = colors.text) }
        }

        val current = run
        if (current == null) {
            Text(stringResource(R.string.diag_not_run), style = type.bodyText, color = colors.textMuted)
            return@MetaScreen
        }

        SectionLabel(stringResource(R.string.diag_golden_title))
        MetaCard {
            if (!current.goldenLoaded) Text(stringResource(R.string.diag_golden_missing), style = type.bodyText, color = colors.danger)
            for (result in current.golden) {
                val name = stringResource(tierName(result.tier))
                val line = when {
                    result.parseError != null -> stringResource(R.string.diag_golden_parse, name)
                    result.passed -> stringResource(R.string.diag_golden_pass, name, result.checked)
                    else -> stringResource(R.string.diag_golden_fail, name, result.mismatches)
                }
                Text(line, style = type.bodyText, color = if (result.passed) colors.text else colors.danger)
                for (mismatch in result.firstMismatches) {
                    Text(
                        stringResource(R.string.diag_mismatch, mismatch.view, mismatch.expected.orEmpty(), mismatch.actual),
                        style = type.caption,
                        color = colors.textMuted,
                    )
                }
            }
            if (current.golden.any { it.parseError == null && !it.versionMatches }) {
                Text(stringResource(R.string.diag_version_differs), style = type.caption, color = colors.danger)
            }
        }

        SectionLabel(stringResource(R.string.diag_timing_title))
        MetaCard {
            for (timing in current.timings) {
                Text(
                    stringResource(
                        R.string.diag_timing_line,
                        stringResource(tierName(timing.tier)),
                        timing.board.replace('x', '×'),
                        "%.1f".format(timing.p50),
                        "%.1f".format(timing.p95),
                        "%.1f".format(timing.max),
                    ),
                    style = type.bodyText,
                    color = colors.text,
                )
            }
        }

        SectionLabel(stringResource(R.string.diag_engine_title))
        MetaCard {
            val engine = current.engine
            Text(
                stringResource(if (engine.passed) R.string.diag_engine_pass else R.string.diag_engine_fail),
                style = type.bodyText,
                color = if (engine.passed) colors.text else colors.danger,
            )
        }
    }
}

private fun tierName(tier: AiTier): Int = when (tier) {
    AiTier.EASY -> R.string.difficulty_easy
    AiTier.MEDIUM -> R.string.difficulty_medium
    AiTier.HARD -> R.string.difficulty_hard
    AiTier.RELENTLESS -> R.string.freeplay_level_relentless
}
