package com.dynokore.platform.tenancyjooq

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry

/**
 * tenancy-jooq's guard inside a real executable jar (review of !61): every class of the service and every library,
 * jOOQ included, comes from one class loader and one jar file, so the guard must tell jOOQ's own parts from a
 * consumer's by exact class, not by name, package or location. Three packagings: a Spring Boot executable jar
 * (Spring Boot's launcher, [FatJarProbe] and the spoofs in `BOOT-INF/classes`, the libraries in `BOOT-INF/lib`), the
 * same with a second, bundled copy of jOOQ listed first (a library that bundles jOOQ whole), and a flat jar that
 * merges every class into one file (a shaded uber jar).
 */
class FatJarTest {
    @ParameterizedTest
    @ValueSource(strings = ["boot", "boot with bundled jOOQ first", "flat"])
    fun `in an executable jar a window function passes and every consumer custom part is refused`(
        packaging: String,
        @TempDir dir: Path,
    ) {
        val jar = dir.resolve("service.jar").toFile()
        if (packaging == "flat") flat(jar) else build(jar, bundledJooqFirst = packaging != "boot")
        val lines = run(jar)
        val loader =
            if (packaging ==
                "flat"
            ) {
                "jdk.internal.loader.ClassLoaders\$AppClassLoader"
            } else {
                "org.springframework.boot.loader.launch.LaunchedClassLoader"
            }
        assertEquals("loader: $loader", lines.first(), lines.toString())
        val outcomes = lines.drop(1).associate { it.substringBefore(": ") to it.substringAfter(": ") }
        val window = outcomes.getValue("window function")
        assertTrue(window.startsWith("accepted: ") && window.contains("\"tenant_id\" = cast(? as uuid)"), window)
        for (spoof in listOf(
            "consumer custom part",
            "custom part in org.jooq.impl",
            "subclass of a jOOQ window function",
        )) {
            val outcome = outcomes.getValue(spoof)
            assertTrue(
                outcome.startsWith("refused: ") && outcome.contains("custom query part is refused"),
                "$spoof: $outcome",
            )
        }
    }

    private fun run(jar: File): List<String> {
        val java =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElseThrow()
        val process = ProcessBuilder(java, "-jar", jar.path).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES), output)
        assertEquals(0, process.exitValue(), output)
        return output.lines().filter {
            it.startsWith("loader: ") || it.contains(": accepted: ") ||
                it.contains(": refused: ")
        }
    }

    private fun build(
        jar: File,
        bundledJooqFirst: Boolean,
    ) {
        val libs = paths("fatJar.libs").distinct()
        val jooq = libs.single { it.name.startsWith("jooq-3.") }
        val nested =
            (if (bundledJooqFirst) listOf("aaa-bundles-jooq.jar" to jooq) else emptyList()) +
                libs.mapIndexed { i, lib -> "$i-${lib.name}" to lib }
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] = "org.springframework.boot.loader.launch.JarLauncher"
                mainAttributes.putValue("Start-Class", FatJarProbe::class.java.name)
                mainAttributes.putValue("Spring-Boot-Classes", "BOOT-INF/classes/")
                mainAttributes.putValue("Spring-Boot-Lib", "BOOT-INF/lib/")
                mainAttributes.putValue("Spring-Boot-Classpath-Index", "BOOT-INF/classpath.idx")
            }
        JarOutputStream(jar.outputStream(), manifest).use { out ->
            writeLoader(out)
            writeBootClasses(out)
            for ((name, file) in nested) stored(out, "BOOT-INF/lib/$name", file.readBytes())
            val index = nested.joinToString("") { "- \"BOOT-INF/lib/${it.first}\"\n" }
            out.putNextEntry(ZipEntry("BOOT-INF/classpath.idx"))
            out.write(index.toByteArray())
            out.closeEntry()
        }
    }

    private fun writeLoader(out: JarOutputStream) {
        JarFile(paths("fatJar.loader").single { it.name.startsWith("spring-boot-loader") }).use { loader ->
            for (entry in loader.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                out.putNextEntry(ZipEntry(entry.name))
                loader.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun writeBootClasses(out: JarOutputStream) {
        val directories = mutableSetOf<String>()
        for (directory in listOf("BOOT-INF/", "BOOT-INF/classes/", "BOOT-INF/lib/")) {
            directories += directory
            out.putNextEntry(ZipEntry(directory))
            out.closeEntry()
        }
        for (classes in paths("fatJar.classes").filter { it.isDirectory }) {
            classes.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = "BOOT-INF/classes/" + file.relativeTo(classes).invariantSeparatorsPath
                for (directory in parents(name).filter { directories.add(it) }) {
                    out.putNextEntry(ZipEntry(directory))
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** One jar holding every class of the probe and its libraries, the first copy of each entry kept. */
    private fun flat(jar: File) {
        val manifest =
            Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.MAIN_CLASS] = FatJarProbe::class.java.name
            }
        val names = mutableSetOf<String>()
        JarOutputStream(jar.outputStream(), manifest).use { out ->
            for (classes in paths("fatJar.classes").filter { it.isDirectory }) copyClassFiles(out, classes, names)
            for (lib in paths("fatJar.libs").distinct()) copyLibraryEntries(out, lib, names)
        }
    }

    private fun copyClassFiles(
        out: JarOutputStream,
        classes: File,
        names: MutableSet<String>,
    ) {
        classes.walkTopDown().filter { it.isFile }.forEach { file ->
            val name = file.relativeTo(classes).invariantSeparatorsPath
            if (names.add(name)) {
                out.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    private fun copyLibraryEntries(
        out: JarOutputStream,
        lib: File,
        names: MutableSet<String>,
    ) {
        JarFile(lib).use { source ->
            for (entry in source.entries()) {
                if (entry.isDirectory || entry.name.startsWith("META-INF/") || !names.add(entry.name)) continue
                out.putNextEntry(ZipEntry(entry.name))
                source.getInputStream(entry).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }

    /** Spring Boot opens a nested jar in place, so it must be stored, not deflated. */
    private fun stored(
        out: JarOutputStream,
        name: String,
        bytes: ByteArray,
    ) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        out.putNextEntry(entry)
        out.write(bytes)
        out.closeEntry()
    }

    /** The directory entries above [name], outermost first. */
    private fun parents(name: String): List<String> =
        name
            .split('/')
            .dropLast(1)
            .runningReduce { path, part -> "$path/$part" }
            .map { "$it/" }

    private fun paths(property: String): List<File> =
        System
            .getProperty(property)
            .split(File.pathSeparator)
            .filter {
                it.isNotBlank()
            }.map(::File)
}
