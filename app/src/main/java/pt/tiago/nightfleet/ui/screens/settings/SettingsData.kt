package pt.tiago.nightfleet.ui.screens.settings

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pt.tiago.nightfleet.AppContainer
import pt.tiago.nightfleet.BuildConfig
import pt.tiago.nightfleet.R
import pt.tiago.nightfleet.backup.BackupIntegrityException
import pt.tiago.nightfleet.backup.DiagnosticsReport
import pt.tiago.nightfleet.backup.ImportPreview
import pt.tiago.nightfleet.ui.common.MetaCard
import pt.tiago.nightfleet.ui.theme.LocalFleetColors
import pt.tiago.nightfleet.ui.theme.LocalFleetType

/** Taps on the version in About that open the debug checks. */
private const val DIAGNOSTICS_TAPS = 7

/**
 * Export, import, diagnostics and campaign reset. Files go through the system document picker, so the app
 * needs no storage permission. Import shows a preview and only writes after the player confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Int?>(null) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    var askReset by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching {
                val bytes = container.backup.exportBytes()
                writeBytes(context, uri, bytes)
            }.fold(onSuccess = { R.string.set_exported }, onFailure = { R.string.set_error_generic })
            busy = false
        }
    }
    val diagnosticsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = runCatching {
                val text = DiagnosticsReport.build(context)
                writeBytes(context, uri, text.toByteArray(Charsets.UTF_8))
            }.fold(onSuccess = { R.string.set_diag_done }, onFailure = { R.string.set_error_generic })
            busy = false
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            message = null
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { container.backup.importFrom(it) }
                        ?: error("The file cannot be opened")
                }
            }.onSuccess { preview = it }
                .onFailure { error ->
                    message = if (error is BackupIntegrityException) R.string.set_import_damaged else R.string.set_import_bad
                }
            busy = false
        }
    }

    MetaCard {
        if (busy) {
            Text(stringResource(R.string.set_busy), style = type.caption, color = colors.textMuted)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
        }
        DataRow(
            title = stringResource(R.string.set_export),
            subtitle = stringResource(R.string.set_export_sub),
            enabled = !busy,
            onClick = { exportLauncher.launch("nightfleet-progress.json") },
        )
        DataRow(
            title = stringResource(R.string.set_import),
            subtitle = stringResource(R.string.set_import_sub),
            enabled = !busy,
            onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        )
        DataRow(
            title = stringResource(R.string.set_diag),
            subtitle = stringResource(R.string.set_diag_sub),
            enabled = !busy,
            onClick = { diagnosticsLauncher.launch("nightfleet-diagnostics.txt") },
        )
        DataRow(
            title = stringResource(R.string.set_reset),
            subtitle = stringResource(R.string.set_reset_sub),
            enabled = !busy,
            onClick = { askReset = true },
        )
        message?.let { Text(stringResource(it), style = type.caption, color = colors.text) }
    }

    preview?.let { pending ->
        ModalBottomSheet(onDismissRequest = { preview = null }) {
            Column(
                Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.set_import_title), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.set_import_body), style = type.bodyText, color = colors.textMuted)
                Text(stringResource(R.string.set_import_stars, pending.totalStars), style = type.bodyText, color = colors.text)
                Text(stringResource(R.string.set_import_logbook, pending.logbookEntries), style = type.bodyText, color = colors.text)
                Text(stringResource(R.string.set_import_saved, pending.savedMatches), style = type.bodyText, color = colors.text)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            container.backup.applyImport(pending)
                            message = R.string.set_import_done
                            busy = false
                        }
                        preview = null
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.set_import_confirm)) }
                TextButton(onClick = { preview = null }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.set_cancel))
                }
            }
        }
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text(stringResource(R.string.set_reset_title)) },
            text = { Text(stringResource(R.string.set_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    askReset = false
                    scope.launch {
                        container.profile.resetCampaign()
                        message = R.string.set_reset_done
                    }
                }) { Text(stringResource(R.string.set_reset_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { askReset = false }) { Text(stringResource(R.string.set_cancel)) }
            },
        )
    }
}

@Composable
internal fun DataRow(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, style = type.bodyText, color = if (enabled) colors.text else colors.textMuted)
            Text(subtitle, style = type.caption, color = colors.textMuted)
        }
    }
}

/** Writes [bytes] to the document the player picked. Runs on the IO dispatcher. */
internal suspend fun writeBytes(context: Context, uri: Uri, bytes: ByteArray) {
    withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri) ?: error("The file cannot be written")
        stream.use { it.write(bytes) }
    }
}

/** The About group: the description, the author's name and his e-mail (selectable). Nothing else. */
@Composable
fun AboutCard(onOpenDiagnostics: () -> Unit = {}) {
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    var nameTaps by remember { mutableIntStateOf(0) }
    val nameTapInteraction = remember { MutableInteractionSource() }
    MetaCard {
        Text(stringResource(R.string.set_about_description), style = type.bodyText, color = colors.text)
        // Debug builds only: seven taps on the name open the on-device checks.
        Text(
            stringResource(R.string.set_about_name),
            style = type.cardTitle,
            color = colors.text,
            modifier = if (!BuildConfig.DEBUG) Modifier else Modifier.clickable(
                interactionSource = nameTapInteraction,
                indication = null,
                role = Role.Button,
            ) {
                nameTaps++
                if (nameTaps >= DIAGNOSTICS_TAPS) {
                    nameTaps = 0
                    onOpenDiagnostics()
                }
            },
        )
        SelectionContainer {
            Text(stringResource(R.string.set_about_email), style = type.bodyText, color = colors.accent)
        }
    }
}

/**
 * The fonts' licences (OFL): each file of assets/licenses, read on tap. Kept out of About on purpose; it sits in the
 * Data section of Settings so the licence text stays reachable.
 */
@Composable
fun LicencesCard() {
    val context = LocalContext.current
    val colors = LocalFleetColors.current
    val type = LocalFleetType.current
    val licences by produceState<List<String>>(initialValue = emptyList()) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.list("licenses")?.sorted() }.getOrNull().orEmpty()
        }
    }
    var openLicence by remember { mutableStateOf<String?>(null) }

    MetaCard {
        Text(stringResource(R.string.set_licences), style = type.cardTitle, color = colors.text, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.set_licences_sub), style = type.caption, color = colors.textMuted)
        licences.forEach { name ->
            val open = openLicence == name
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { openLicence = if (open) null else name },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, style = type.bodyText, color = colors.text)
            }
            if (open) LicenceText(name)
        }
    }
}

@Composable
private fun LicenceText(name: String) {
    val context = LocalContext.current
    val text by produceState("", name) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }
                .getOrDefault("")
        }
    }
    SelectionContainer {
        Text(text, style = LocalFleetType.current.caption, color = LocalFleetColors.current.textMuted)
    }
}
