package com.dynokore.gst.access.adapters.http

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fail-closed gate of INV-Q-10027 (INV-AC-22070): no role name as a string literal in the controller or its check,
 * because the callers are decided by the permission key only. The subject is the text of the files this task adds
 * under `src/main`; a literal is matched as a whole token, quotes included.
 */
class AuthorityIncarnationGateTest {
    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val base = root.resolve("access-adapters/src/main/kotlin/com/dynokore/gst/access/adapters")
    private val named = listOf(base.resolve("http/AuthorityIncarnationOperation.kt"), base.resolve("http/BumpPermissionCheck.kt"))

    /** Every `.kt` under any module `src/main`, skipping build, .git, .gradle and .gradle-cache. */
    private fun mainSources(top: Path): List<Path> =
        Files.walk(top).use { w ->
            w.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") && "/src/main/" in it.toString().replace('\\', '/') }
                .filter { p -> top.relativize(p).none { it.toString() in SKIPPED } }
                .toList()
        }

    /** Files this task adds: the named pair, anything under an `incarnation/` directory, and the git-added `.kt` under src/main. */
    private fun subjectOf(top: Path): List<Path> {
        val all = mainSources(top)
        val byDir = all.filter { p -> top.relativize(p).any { it.toString() == "incarnation" } }
        val added = gitAdded(top).map { top.resolve(it) }.filter { it in all }
        return (named.filter { top == root } + byDir + added).distinct()
    }

    private fun gitAdded(top: Path): List<String> {
        if (top != root) return emptyList()
        return runCatching {
            val pb = ProcessBuilder("git", "diff", "--name-only", "--diff-filter=A", "origin/main").directory(top.toFile()).redirectErrorStream(true).start()
            val out = pb.inputStream.bufferedReader().readLines()
            if (pb.waitFor() == 0) out.filter { inSubject(it) } else emptyList()
        }.getOrDefault(emptyList())
    }

    private val subject: List<Path> get() = subjectOf(root)

    @Test
    fun `INV-AC-22070 the subject holds the operation and its check`() {
        named.forEach { assertThat(it).exists() }
        assertThat(subject).containsAll(named)
    }

    @Test
    fun `INV-AC-22070 probe a file added under an incarnation dir enters the subject and fails the gate`() {
        val tmp = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "gate")
        try {
            val dir = Files.createDirectories(tmp.resolve("m/src/main/kotlin/x/incarnation"))
            val f = dir.resolve("Extra.kt")
            Files.writeString(f, "val r = \"platform.engineer\"\nimport com.dynokore.platform.serviceruntime.replica.AuthorityReplica\n")
            val other = Files.createDirectories(tmp.resolve("m/src/main/kotlin/x/other")).resolve("Ok.kt")
            Files.writeString(other, "val r = 1")
            val found = subjectOf(tmp)
            assertThat(found).containsExactly(f)
            assertThat(roleLiterals(Files.readString(f))).isNotEmpty()
            assertThat(replicaUse(Files.readString(f))).isTrue()
        } finally {
            Files.walk(tmp).use { w -> w.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test
    fun `INV-AC-22070 no role name literal occurs in the operation or its check`() {
        subject.forEach { assertThat(roleLiterals(Files.readString(it))).describedAs(it.toString()).isEmpty() }
    }

    @Test
    fun `INV-AC-22070 probe a role comparison in the controller fails the gate`() {
        val line = "if (caller.role == \"platform.engineer\") return true"
        assertThat(roleLiterals(line)).containsExactly("\"platform.engineer\"")
        assertThat(roleLiterals("val x = \"deployment.operator\"")).containsExactly("\"deployment.operator\"")
        assertThat(roleLiterals("listOf(\"tenant.admin\")")).containsExactly("\"tenant.admin\"")
    }

    @Test
    fun `INV-AC-22070 probe the same line under src-test is outside the subject`() {
        val thisFile = Path.of("src/test/kotlin/com/dynokore/gst/access/adapters/http/AuthorityIncarnationGateTest.kt")
        assertThat(inSubject("access-adapters/src/test/kotlin/x/AuthorityIncarnationOperationTest.kt")).isFalse()
        assertThat(inSubject("access-adapters/src/main/kotlin/x/AuthorityIncarnationOperation.kt")).isTrue()
        assertThat(inSubject("access-adapters/src/main/resources/x.yaml")).isFalse()
        assertThat(Files.exists(thisFile)).isTrue()
    }

    @Test
    fun `INV-AC-22070 probe a look-alike literal is another token and does not fail the gate`() {
        assertThat(roleLiterals("val x = \"platform.engineers\"")).isEmpty()
        assertThat(roleLiterals("val x = \"x-platform.engineer\"")).isEmpty()
        assertThat(roleLiterals("val x = \"Platform.Engineer\"")).isEmpty()
    }

    @Test
    fun `INV-AC-22070 no replica token or import occurs in the subject`() {
        subject.forEach { assertThat(replicaUse(Files.readString(it))).describedAs(it.toString()).isFalse() }
    }

    @Test
    fun `INV-AC-22070 probe the replica import fails the gate, a look-alike token does not`() {
        assertThat(replicaUse("import com.dynokore.platform.serviceruntime.replica.AuthorityReplica")).isTrue()
        assertThat(replicaUse("val r: AuthorityReplica? = null")).isTrue()
        assertThat(replicaUse("val r: AuthorityReplicaView? = null")).isFalse()
        assertThat(replicaUse("val r: MyAuthorityReplica? = null")).isFalse()
    }

    @Test
    fun `INV-AC-22070 probe an enum token comparison fails the gate, a look-alike token does not`() {
        assertThat(roleTokens("if (role == Role.DEPLOYMENT_OPERATOR) true")).containsExactly("DEPLOYMENT_OPERATOR")
        assertThat(roleTokens("x == PLATFORM_ENGINEER")).containsExactly("PLATFORM_ENGINEER")
        assertThat(roleTokens("x == PLATFORM_ENGINEERS")).isEmpty()
        subject.forEach { assertThat(roleTokens(Files.readString(it))).describedAs(it.toString()).isEmpty() }
    }

    private fun replicaUse(text: String) =
        REPLICA_TOKEN.containsMatchIn(text) || "com.dynokore.platform.serviceruntime.replica" in text

    private fun roleTokens(text: String): List<String> = ROLE_TOKEN.findAll(text).map { it.value }.toList()

    private fun roleLiterals(text: String): List<String> = LITERAL.findAll(text).map { it.value }.toList()

    /** The `git diff --name-only --diff-filter=A` lines the gate reads: a `.kt` file under `/src/main/`. */
    private fun inSubject(path: String) = path.endsWith(".kt") && path.contains("/src/main/")

    private companion object {
        val SKIPPED = setOf("build", ".git", ".gradle", ".gradle-cache")
        val REPLICA_TOKEN = Regex("(?<![A-Za-z0-9_])AuthorityReplica(?![A-Za-z0-9_])")
        val ROLE_TOKEN = Regex("(?<![A-Za-z0-9_])(?:PLATFORM_ENGINEER|DEPLOYMENT_OPERATOR)(?![A-Za-z0-9_])")
        val LITERAL = Regex("\"(?:platform\\.engineer|deployment\\.operator|tenant\\.admin)\"")
    }
}
