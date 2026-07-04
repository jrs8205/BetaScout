package org.jarsi.betascout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.betascout.ui.BetaScoutNavHost
import org.jarsi.betascout.ui.theme.BetaScoutTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BetaScoutTheme {
                BetaScoutNavHost()
            }
        }
    }
}
