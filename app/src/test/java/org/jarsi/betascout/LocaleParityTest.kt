package org.jarsi.betascout

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every shipped locale must translate exactly the keys the default locale
 * declares translatable, and every locale must be listed in locales_config.xml
 * so Android 13+ offers it as a per-app language.
 */
class LocaleParityTest {

    private val localeDirs = mapOf(
        "values-de" to "de",
        "values-es" to "es",
        "values-fi" to "fi",
        "values-fr" to "fr",
        "values-it" to "it",
        "values-pt-rBR" to "pt-BR",
    )

    private val resDir: File = listOf("src/main/res", "app/src/main/res")
        .map { File(System.getProperty("user.dir"), it) }
        .firstOrNull { it.isDirectory }
        ?: error("res directory not found from ${System.getProperty("user.dir")}")

    private val stringTag = Regex("""<string\s+name="([^"]+)"([^>]*)>""")

    private fun keysOf(file: File, includeUntranslatable: Boolean = false): Set<String> {
        assertTrue("missing strings file: $file", file.isFile)
        return stringTag.findAll(file.readText())
            .filter { includeUntranslatable || !it.groupValues[2].contains("translatable=\"false\"") }
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `every locale translates exactly the default translatable keys`() {
        val expected = keysOf(File(resDir, "values/strings.xml"))
        for (dir in localeDirs.keys) {
            val actual = keysOf(File(resDir, "$dir/strings.xml"), includeUntranslatable = true)
            val missing = expected - actual
            val extra = actual - expected
            assertEquals("$dir missing=$missing extra=$extra", expected, actual)
        }
    }

    @Test
    fun `locales_config lists the default language and every translation`() {
        val config = File(resDir, "xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(config)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals((localeDirs.values + "en").toSet(), listed)
    }
}
