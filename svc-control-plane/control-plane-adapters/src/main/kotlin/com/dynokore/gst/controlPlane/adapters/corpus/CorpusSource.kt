package com.dynokore.gst.controlPlane.adapters.corpus

import java.nio.file.Files
import java.nio.file.Path

/** The files of a corpus tree, read by exact file name. A file that is not there is null, never a default. */
fun interface CorpusSource {
    fun read(name: String): ByteArray?
}

/** The corpus tree that ships as the classpath resource directory `conformance-corpus/` (INV-T-9787). */
class ClasspathCorpusSource(
    private val root: String = DEFAULT_ROOT,
) : CorpusSource {
    override fun read(name: String): ByteArray? =
        ClasspathCorpusSource::class.java.classLoader
            .getResourceAsStream("$root/$name")
            ?.use { it.readAllBytes() }

    companion object {
        const val DEFAULT_ROOT = "conformance-corpus"
    }
}

/** A corpus tree in a directory; the name is looked up by exact equality, so a look-alike spelling is absent. */
class DirectoryCorpusSource(
    private val directory: Path,
) : CorpusSource {
    override fun read(name: String): ByteArray? {
        val listed = Files.list(directory).use { stream -> stream.anyMatch { it.fileName.toString() == name } }
        return if (listed) Files.readAllBytes(directory.resolve(name)) else null
    }
}
