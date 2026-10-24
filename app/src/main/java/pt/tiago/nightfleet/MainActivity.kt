package pt.tiago.nightfleet

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pt.tiago.nightfleet.ui.navigation.NightFleetApp
import pt.tiago.nightfleet.ui.screens.settings.AppLocale

/**
 * Single activity. The screens and the back stack live in `ui/navigation` (see [NightFleetApp]); this class
 * only applies the saved language and forwards the activity to the composition.
 */
class MainActivity : ComponentActivity() {
    private val container: AppContainer by lazy { AppContainer.from(this) }

    /** The language chosen in Settings applies before any resource is read (see AppLocale). */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { NightFleetApp(container) }
    }

    /** The music stops with the activity; the battle also pauses its clock (see BattleScreen). */
    override fun onStop() {
        super.onStop()
        container.audio.pause()
    }

    override fun onStart() {
        super.onStart()
        container.audio.resume()
    }

    override fun onDestroy() {
        if (isFinishing) container.audio.release()
        super.onDestroy()
    }
}
