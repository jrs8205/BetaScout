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
    /** True if the package has a launcher activity, i.e. it is an app the user sees
     *  in the app drawer — distinguishes real (pre)installed apps from framework
     *  packages, overlays and providers. */
    val hasLauncher: Boolean,
)

interface InstalledPackagesSource {
    fun installedPackages(): List<RawInstalledPackage>
}
