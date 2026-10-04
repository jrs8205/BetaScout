package org.jarsi.betascout.data.betadb

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to a sibling temp file and moves it over this file in one step,
 * so a reader never sees a half-written file: either the previous content or
 * the complete new one. [write] is the raw write step, replaceable in tests.
 */
internal fun File.writeTextAtomically(
    text: String,
    write: (File, String) -> Unit = { file, content -> file.writeText(content) },
) {
    val temp = File(parentFile, "$name.tmp")
    try {
        write(temp, text)
        Files.move(
            temp.toPath(),
            toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } finally {
        temp.delete()
    }
}
