// gradle/gates/lock-consistency.gradle.kts — the lockConsistency check (INV-T-19569, coordinator ruling on the reviewer stop).
//
// Gradle's strict dependency locking does not refuse a stale lower line for a module the BOM constrains higher (the lock
// state wins and the stale version is resolved without an error). So the committed lock state is compared, byte for byte,
// with the lock state a fresh `resolveAndLockAll --write-locks` run produces in a scratch copy of the working tree.
//
// Fails closed. It fails when
//   - a committed lockfile differs from the scratch result in any byte (the message names the file and the first differing line);
//   - the scratch run has a lockfile the working tree lacks, or the reverse;
//   - the scratch run exits non-zero;
//   - no `gradle.lockfile` exists at all (in the working tree or in the scratch result).
// It never skips and is never up to date. A lockfile is a file whose whole name equals `gradle.lockfile`,
// `buildscript-gradle.lockfile` or `settings-gradle.lockfile`; a look-alike such as `gradle.lockfile.orig` is ignored.
// The scratch copy drops `gradle.lockfile` and `buildscript-gradle.lockfile` first, so a committed file the fresh run
// no longer writes is reported as present only in the working tree. The settings lockfiles stay in the copy, because
// settings.gradle.kts refuses a build without the root one (STK-72); `--write-locks` rewrites them.

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

abstract class LockConsistency : DefaultTask() {
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Internal
    abstract val scratchRoot: DirectoryProperty

    @get:Internal
    abstract val gradleHome: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    private val lockNames = setOf("gradle.lockfile", "buildscript-gradle.lockfile", "settings-gradle.lockfile")
    private val dropNames = setOf("gradle.lockfile", "buildscript-gradle.lockfile")
    private val skipDirs = setOf(".git", ".gradle", ".gradle-cache", ".kotlin", "build", "node_modules")

    private fun relPaths(root: Path): Map<String, Path> {
        val found = sortedMapOf<String, Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { p -> root.relativize(p).none { it.toString() in skipDirs } }
                .filter { it.fileName.toString() in lockNames }
                .forEach { found[root.relativize(it).joinToString("/")] = it }
        }
        return found
    }

    private fun firstDifference(
        name: String,
        committed: ByteArray,
        fresh: ByteArray,
    ): String {
        val a = String(committed, Charsets.UTF_8).split("\n")
        val b = String(fresh, Charsets.UTF_8).split("\n")
        var i = 0
        while (i < a.size && i < b.size && a[i] == b[i]) i++
        val have = a.getOrNull(i)?.let { "'$it'" } ?: "(end of file)"
        val want = b.getOrNull(i)?.let { "'$it'" } ?: "(end of file)"
        return "$name differs from the fresh --write-locks result at line ${i + 1}: committed $have, fresh $want"
    }

    @TaskAction
    fun verify() {
        val root = projectRoot.get().asFile.toPath()
        val scratch = scratchRoot.get().asFile.toPath()
        scratch.toFile().deleteRecursively()
        Files.createDirectories(scratch)
        Files.walk(root).use { stream ->
            stream
                .filter { p -> root.relativize(p).none { it.toString() in skipDirs } }
                .forEach { p ->
                    val target = scratch.resolve(root.relativize(p).toString())
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target)
                    } else if (Files.isRegularFile(p)) {
                        Files.createDirectories(target.parent)
                        Files.copy(p, target)
                    }
                }
        }
        val committed = relPaths(root)
        relPaths(scratch).forEach { (rel, path) -> if (path.fileName.toString() in dropNames) Files.delete(path) }

        val out = ByteArrayOutputStream()
        val result =
            execOps.exec {
                workingDir = scratch.toFile()
                commandLine(
                    "${gradleHome.get()}/bin/gradle",
                    "--console=plain",
                    "--no-daemon",
                    "resolveAndLockAll",
                    "--write-locks",
                )
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        val fresh = relPaths(scratch)
        val findings = mutableListOf<String>()
        if (result.exitValue != 0) {
            findings += "the scratch run `gradle resolveAndLockAll --write-locks` exited with ${result.exitValue}: " +
                out.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.takeLast(8).joinToString(" | ")
        }
        if (committed.keys.none { it.substringAfterLast('/') == "gradle.lockfile" }) {
            findings += "no gradle.lockfile in the working tree"
        }
        if (fresh.keys.none { it.substringAfterLast('/') == "gradle.lockfile" }) {
            findings += "no gradle.lockfile in the scratch result"
        }
        (committed.keys - fresh.keys).forEach { findings += "$it is committed but the fresh --write-locks run does not write it" }
        (fresh.keys - committed.keys).forEach { findings += "$it is written by the fresh --write-locks run but is not in the working tree" }
        (committed.keys intersect fresh.keys).forEach { rel ->
            val a = Files.readAllBytes(committed.getValue(rel))
            val b = Files.readAllBytes(fresh.getValue(rel))
            if (!a.contentEquals(b)) findings += firstDifference(rel, a, b)
        }
        if (findings.isNotEmpty()) {
            throw GradleException(
                "lockConsistency failed:\n" + findings.joinToString("\n") { "    $it" } +
                    "\n    regenerate with `./gradlew resolveAndLockAll --write-locks` (README.md, \"Dependency locking and verification\")",
            )
        }
    }
}

val lockConsistency =
    tasks.register<LockConsistency>("lockConsistency") {
        group = "verification"
        description = "INV-T-19569: every committed lockfile equals the lockfile a fresh resolveAndLockAll --write-locks run writes."
        projectRoot.set(layout.projectDirectory)
        scratchRoot.set(layout.buildDirectory.dir("lock-consistency/scratch"))
        gradleHome.set(gradle.gradleHomeDir!!.absolutePath)
        outputs.upToDateWhen { false }
    }
tasks.named("check") { dependsOn(lockConsistency) }
