package org.jarsi.betascout

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The tap target of the "sign in again" notification: raises a sign-in request
 * and brings the app forward. It exists so that request can only come from this
 * app. It is not exported — the notification's PendingIntent, created by the app
 * itself, is the one thing that can start it — and it passes the request on in
 * process memory ([SignInRequests]), never through the exported launcher
 * activity's intent, which any app could imitate.
 *
 * Never shown: it finishes inside onCreate. It runs in a task of its own that is
 * kept out of Recents, so the app's real task always starts from the ordinary
 * launcher intent and reopening it from Recents cannot replay the request.
 */
class SignInRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SignInRequests.request()
        startActivity(
            // The launcher's own intent, so an existing app task is brought
            // forward as it is and a new one looks like any other app start.
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(this, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
        )
        finish()
    }
}
