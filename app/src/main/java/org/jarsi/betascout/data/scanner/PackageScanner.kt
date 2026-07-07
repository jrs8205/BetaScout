package org.jarsi.betascout.data.scanner

import org.jarsi.betascout.domain.InstalledAppInfo

interface PackageScanner {
    suspend fun scan(): List<InstalledAppInfo>
}

class DefaultPackageScanner(
    private val source: InstalledPackagesSource,
    private val ownPackageName: String,
    private val clock: () -> Long,
) : PackageScanner {

    override suspend fun scan(): List<InstalledAppInfo> {
        val now = clock()
        return source.installedPackages()
            .filter { it.packageName != ownPackageName }
            .map {
                InstalledAppInfo(
                    packageName = it.packageName,
                    label = it.label,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    installerPackage = it.installerPackage,
                    isSystem = it.isSystem,
                    hasLauncher = it.hasLauncher,
                    lastScanned = now,
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
