package org.jarsi.betascout.data.scanner

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeInstalledPackagesSource(
    private val packages: List<RawInstalledPackage>,
) : InstalledPackagesSource {
    override fun installedPackages(): List<RawInstalledPackage> = packages
}

private fun raw(
    packageName: String,
    label: String = packageName,
    versionName: String? = "1.0",
    versionCode: Long = 1L,
    installerPackage: String? = "com.android.vending",
    isSystem: Boolean = false,
    hasLauncher: Boolean = true,
) = RawInstalledPackage(packageName, label, versionName, versionCode, installerPackage, isSystem, hasLauncher)

class DefaultPackageScannerTest {

    private fun scanner(
        packages: List<RawInstalledPackage>,
        ownPackageName: String = "org.jarsi.betascout",
        now: Long = 1_720_000_000_000L,
    ) = DefaultPackageScanner(FakeInstalledPackagesSource(packages), ownPackageName, clock = { now })

    @Test
    fun `maps raw package fields to InstalledAppInfo with scan timestamp`() = runTest {
        val result = scanner(
            packages = listOf(
                raw(
                    packageName = "com.whatsapp",
                    label = "WhatsApp",
                    versionName = "2.26.1",
                    versionCode = 261L,
                    installerPackage = "com.android.vending",
                    isSystem = false,
                )
            ),
            now = 42L,
        ).scan()

        assertEquals(1, result.size)
        val app = result.single()
        assertEquals("com.whatsapp", app.packageName)
        assertEquals("WhatsApp", app.label)
        assertEquals("2.26.1", app.versionName)
        assertEquals(261L, app.versionCode)
        assertEquals("com.android.vending", app.installerPackage)
        assertEquals(false, app.isSystem)
        assertEquals(42L, app.lastScanned)
    }

    @Test
    fun `excludes the scanner app itself from results`() = runTest {
        val result = scanner(
            packages = listOf(
                raw("org.jarsi.betascout", label = "BetaScout"),
                raw("com.whatsapp", label = "WhatsApp"),
            )
        ).scan()

        assertEquals(listOf("com.whatsapp"), result.map { it.packageName })
    }

    @Test
    fun `sorts results by label case-insensitively`() = runTest {
        val result = scanner(
            packages = listOf(
                raw("com.b", label = "beta"),
                raw("com.c", label = "Chrome"),
                raw("com.a", label = "Alpha"),
            )
        ).scan()

        assertEquals(listOf("Alpha", "beta", "Chrome"), result.map { it.label })
    }

    @Test
    fun `passes system flag through`() = runTest {
        val result = scanner(
            packages = listOf(raw("com.google.android.gms", label = "Play services", isSystem = true))
        ).scan()

        assertTrue(result.single().isSystem)
    }

    @Test
    fun `passes launcher flag through`() = runTest {
        val result = scanner(
            packages = listOf(
                raw("com.google.android.gm", label = "Gmail", isSystem = true, hasLauncher = true),
                raw("com.android.providers.media", label = "Media", isSystem = true, hasLauncher = false),
            )
        ).scan()

        assertTrue(result.single { it.packageName == "com.google.android.gm" }.hasLauncher)
        assertTrue(!result.single { it.packageName == "com.android.providers.media" }.hasLauncher)
    }
}
