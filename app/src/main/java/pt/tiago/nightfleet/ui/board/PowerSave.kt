package pt.tiago.nightfleet.ui.board

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** True when the system battery saver is on. */
fun batterySaverOn(context: Context): Boolean = try {
    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true
} catch (_: RuntimeException) {
    false
}

/**
 * The battery saver state, read again whenever the screen starts. The receiver for the power save change is
 * registered only between start and stop, so a stopped screen holds no receiver.
 */
@Composable
fun rememberBatterySaver(): Boolean {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var saver by remember { mutableStateOf(batterySaverOn(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                saver = batterySaverOn(context)
            }
        }
        var registered = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!registered) {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    registered = true
                    saver = batterySaverOn(context)
                }
                Lifecycle.Event.ON_STOP -> if (registered) {
                    context.unregisterReceiver(receiver)
                    registered = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (registered) context.unregisterReceiver(receiver)
        }
    }
    return saver
}
