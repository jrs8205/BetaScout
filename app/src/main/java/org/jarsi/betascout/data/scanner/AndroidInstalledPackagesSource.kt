package org.jarsi.betascout.data.scanner

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Thin adapter over the real PackageManager. No logic —
 * all decisions happen in DefaultPackageScanner (unit-tested).
 * Requires the QUERY_ALL_PACKAGES permission to see all packages.
 */
class AndroidInstalledPackagesSource(
    private val packageManager: PackageManager,
) : InstalledPackagesSource {

    override fun installedPackages(): List<RawInstalledPackage> =
        getInstalledPackagesCompat().map { pkg ->
            val appInfo = pkg.applicationInfo
            RawInstalledPackage(
                packageName = pkg.packageName,
                label = appInfo?.loadLabel(packageManager)?.toString() ?: pkg.packageName,
                versionName = pkg.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pkg.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    pkg.versionCode.toLong()
                },
                installerPackage = installerOf(pkg.packageName),
                isSystem = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }

    private fun getInstalledPackagesCompat() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

    private fun installerOf(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}
