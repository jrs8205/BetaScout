package org.jarsi.betascout.domain

object BetaLinkBuilder {

    /** The Play Store's own testing page 302-redirects to the shared
     *  "Google system services" program and the direct fetch times out on-device
     *  every time; the alias points straight at the redirect target so the scan
     *  reads the real program in one hop. */
    private const val GOOGLE_SYSTEM_SERVICES_PACKAGE = "com.google.android.gms"

    fun testingUrl(packageName: String): String =
        "https://play.google.com/apps/testing/${aliased(clean(packageName))}"

    private fun aliased(packageName: String): String =
        if (packageName == PLAY_STORE_PACKAGE) GOOGLE_SYSTEM_SERVICES_PACKAGE else packageName

    fun playStoreUri(packageName: String): String =
        "market://details?id=${clean(packageName)}"

    fun playStoreWebUrl(packageName: String): String =
        "https://play.google.com/store/apps/details?id=${clean(packageName)}"

    private fun clean(packageName: String): String {
        val trimmed = packageName.trim()
        require(trimmed.isNotEmpty()) { "packageName must not be blank" }
        return trimmed
    }
}
