package org.jarsi.betavahti

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.betavahti.ui.BetaVahtiNavHost
import org.jarsi.betavahti.ui.theme.BetaVahtiTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BetaVahtiTheme {
                BetaVahtiNavHost()
            }
        }
    }
}
