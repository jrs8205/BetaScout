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

private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")

/**
 * The account email out of the raw `evaluateJavascript` result (a JSON string
 * literal, or the literal `null` when the script failed), lower-cased, or null
 * when there is none. The script reads the account chip's label, never the page
 * source: a stray address anywhere in the HTML must not become the account —
 * and thereby the accountKey — every observation is filed under.
 */
internal fun capturedEmailOf(raw: String?): String? {
    if (raw == null || raw == "null") return null
    return EMAIL.find(raw)?.value?.lowercase()
}
