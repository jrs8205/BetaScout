package org.jarsi.betascout.data.betadb

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicWriteTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `replaces the previous content and leaves no temp file behind`() {
        val target = folder.newFile("catalog_cache.json").apply { writeText("OLD") }

        target.writeTextAtomically("NEW")

        assertEquals("NEW", target.readText())
        assertEquals(listOf("catalog_cache.json"), folder.root.list()!!.toList())
    }

    @Test
    fun `a write that dies half-way leaves the previous content intact`() {
        // The 600 KB cache is rewritten on every list resume; the process can be
        // killed mid-write. A truncated cache is worse than a stale one: it fails
        // validation and drags the whole catalog back to the bundled seed.
        val target = folder.newFile("catalog_cache.json").apply { writeText("OLD") }

        assertThrows(IOException::class.java) {
            target.writeTextAtomically("NEW") { file, text ->
                file.writeText(text.take(1))
                throw IOException("process died")
            }
        }

        assertEquals("OLD", target.readText())
    }

    @Test
    fun `creates the file when none exists yet`() {
        val target = File(folder.root, "catalog_cache.json")

        target.writeTextAtomically("FIRST")

        assertEquals("FIRST", target.readText())
    }
}
