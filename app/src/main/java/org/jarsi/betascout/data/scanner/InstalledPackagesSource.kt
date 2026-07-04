package org.jarsi.betascout.data.scanner

/**
 * Raw data for a single installed package. Kept separate from PackageManager
 * so the scan logic is unit-testable with a fake.
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
