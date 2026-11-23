package org.jarsi.betascout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.betascout.data.settings.SettingsRepository

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: SettingsRepository,
) : ViewModel() {

    /** null = still loading from DataStore; drives the onboarding-vs-main decision. */
    val onboardingDone: StateFlow<Boolean?> = settings.onboardingDone
        .map { done -> done as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Opt-in Material You; the brand palette (false) is the initial value so the
     *  first frame renders on-brand instead of flashing wallpaper colors. */
    val useDynamicColor: StateFlow<Boolean> = settings.useDynamicColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Someone inside the app asked for the sign-in page — the tap on the "sign
     *  in again" notification or the main screen's "session expired" line. The
     *  navigation host answers by opening the page and then consumes it. Process
     *  state (see [SignInRequests]), so a rotation between the tap and the
     *  navigation does not lose it. */
    val signInRequested: StateFlow<Boolean> = SignInRequests.pending

    fun requestSignIn() = SignInRequests.request()

    fun consumeSignInRequest() = SignInRequests.consume()

    fun completeOnboarding() {
        viewModelScope.launch { settings.setOnboardingDone() }
    }
}
