package org.jarsi.betascout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Open the sign-in page" requests made inside this app: by the tap on the
 * "sign in again" notification (through [SignInRequestActivity]) and by the main
 * screen's "session expired" line.
 *
 * Deliberately process memory and nothing an intent can carry. The launcher
 * activity is exported, so anything it read from its intent could be sent by
 * another app; opening the sign-in page is not harmless there, because a WebView
 * that still holds valid Google cookies completes the sign-in — session saved,
 * scan started — without the user choosing anything.
 */
internal object SignInRequests {

    private val _pending = MutableStateFlow(false)

    /** A request is waiting for the navigation host to act on it. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }
}
