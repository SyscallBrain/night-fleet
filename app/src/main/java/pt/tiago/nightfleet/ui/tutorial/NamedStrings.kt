package pt.tiago.nightfleet.ui.tutorial

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/**
 * The text of the string resource called [name] (for example a tutorial step's `textKey`), in the current
 * language and configuration. Falls back to [name] itself when no resource has that name, so a missing text is
 * visible.
 */
/**
 * Looked up by name on purpose: the keys come from the tutorial scripts and the intro cards (data), and lint cannot
 * check them, so the two lint checks that flag this lookup are suppressed here.
 */
@SuppressLint("DiscouragedApi", "LocalContextResourcesRead")
@Composable
fun namedString(name: String): String {
    val context = LocalContext.current
    val id = remember(name, context) { context.resources.getIdentifier(name, "string", context.packageName) }
    return if (id == 0) name else stringResource(id)
}
