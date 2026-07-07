package org.jarsi.betascout.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import org.jarsi.betascout.domain.BetaLinkBuilder

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        // No browser available — don't crash; the action is a no-op.
    }
}

/**
 * Opens the URL in a Chrome Custom Tab so the user's existing browser Google session
 * is available (joining a testing program happens as a web opt-in). Falls back to a
 * plain browser intent when no Custom Tab provider exists.
 */
fun openInCustomTab(context: Context, url: String) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    } catch (e: ActivityNotFoundException) {
        openUrl(context, url)
    }
}

/** Opens the Play Store app page, falling back to the web page without Play. */
fun openPlayPage(context: Context, packageName: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, BetaLinkBuilder.playStoreUri(packageName).toUri()),
        )
    } catch (e: ActivityNotFoundException) {
        openUrl(context, BetaLinkBuilder.playStoreWebUrl(packageName))
    }
}
