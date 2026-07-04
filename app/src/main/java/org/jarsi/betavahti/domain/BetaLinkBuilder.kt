package org.jarsi.betavahti.domain

object BetaLinkBuilder {

    fun testingUrl(packageName: String): String =
        "https://play.google.com/apps/testing/${clean(packageName)}"

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
