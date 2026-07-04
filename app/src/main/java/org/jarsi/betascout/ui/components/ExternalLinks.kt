package org.jarsi.betascout.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import org.jarsi.betascout.domain.BetaLinkBuilder

fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        // No browser available — don't crash; the action is a no-op.
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
