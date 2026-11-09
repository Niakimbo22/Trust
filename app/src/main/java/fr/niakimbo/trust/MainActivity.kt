package fr.niakimbo.trust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.niakimbo.trust.ui.TrustRoot
import fr.niakimbo.trust.ui.theme.TrustTheme

class MainActivity : ComponentActivity() {

    private val container get() = (application as TrustApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            TrustTheme(themeMode) {
                TrustRoot(container)
            }
        }
        if (savedInstanceState == null) container.updateManager.checkIfDue()
    }

    override fun onResume() {
        super.onResume()
        container.updateManager.onActivityResumed(this)
    }

    override fun onPause() {
        container.updateManager.onActivityPaused(this)
        super.onPause()
    }
}
