package org.jarsi.betascout

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.betascout.ui.BetaScoutNavHost
import org.jarsi.betascout.ui.onboarding.OnboardingScreen
import org.jarsi.betascout.ui.theme.BetaScoutTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BetaScoutTheme {
                val viewModel: MainViewModel = hiltViewModel()
                val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
                when (onboardingDone) {
                    null -> Surface { Box(Modifier.fillMaxSize()) }
                    false -> Surface { OnboardingScreen(onDone = viewModel::completeOnboarding) }
                    true -> BetaScoutNavHost()
                }
            }
        }
    }
}
