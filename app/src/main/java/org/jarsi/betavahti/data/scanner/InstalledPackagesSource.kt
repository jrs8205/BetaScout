package org.jarsi.betavahti.data.scanner

/**
 * Raakadata yhdestä asennetusta paketista. Erillään PackageManagerista,
 * jotta skannauslogiikka on yksikkötestattavissa fakella.
 */
data class RawInstalledPackage(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val installerPackage: String?,
    val isSystem: Boolean,
)

interface InstalledPackagesSource {
    fun installedPackages(): List<RawInstalledPackage>
}
