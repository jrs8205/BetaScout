package org.jarsi.betascout.ui.account

import java.net.URI

/** True only when [url]'s actual host is play.google.com. A substring check is
 *  not enough: the sign-in flow's accounts.google.com URLs carry play.google.com
 *  inside their continue parameter, and capturing cookies there would save a
 *  half-established session whose first scan fails with needs-login. */
internal fun isPlayPageUrl(url: String?): Boolean {
    if (url == null) return false
    val host = runCatching { URI(url).host }.getOrNull() ?: return false
    return host.equals("play.google.com", ignoreCase = true)
}
