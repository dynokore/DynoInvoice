package com.dynokore.gst.controlPlane.acceptance.architecture

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The two grep gates of INV-T-4985 as tests (INV-Q-11181 A, INV-Q-11183 A, INV-NOTE-10639 R5): the release code names
 * no role, and no file holds the retired role-valued property names. Paths are compared as written, with no glob.
 */
class ReleaseRoleNameGateTest {
    private val roleWords = Regex("""OperatorRoles?\b|OperatorRole\.|release\.manager|RELEASE_MANAGER""")
    private val retired = Regex(RETIRED_A + "|" + RETIRED_B)

    private val releasePaths =
        listOf(
            "control-plane-application/src/main/kotlin/com/dynokore/gst/controlPlane/application/release/",
            "control-plane-domain/src/main/kotlin/com/dynokore/gst/controlPlane/domain/release/",
            "control-plane-bootstrap/src/main/kotlin/com/dynokore/gst/controlPlane/bootstrap/ReleaseOperationsConfiguration.kt",
        )

    private fun filesOf(path: String): List<File> =
        File(Repository.root, path).let { if (it.isDirectory) it.walkTopDown().filter(File::isFile).toList() else listOf(it) }

    private fun hits(
        regex: Regex,
        files: List<File>,
    ) = files.flatMap { f -> f.readLines().withIndex().filter { regex.containsMatchIn(it.value) }.map { "${f.name}:${it.index + 1}" } }

    private fun trackedFiles() =
        Repository.root
            .walkTopDown()
            .onEnter { it.name !in setOf(".git", "build", ".gradle", ".kotlin", "node_modules") }
            .filter { it.isFile && it.length() < MAX_BYTES && !it.name.endsWith(".jar") && !it.name.endsWith(".zip") }
            .toList()

    @Test
    fun `the release code names no role`() {
        hits(roleWords, releasePaths.flatMap(::filesOf)).shouldBeEmpty()
    }

    @Test
    fun `the pack ports hold no release manager constant`() {
        val ports =
            File(Repository.root, "control-plane-application/src/main/kotlin/com/dynokore/gst/controlPlane/application/pack/Ports.kt")
        hits(Regex("RELEASE_MANAGER"), listOf(ports)).shouldBeEmpty()
    }

    @Test
    fun `the role gate catches its probes`() {
        listOf(
            "import com.dynokore.gst.controlPlane.application.pack.OperatorRoles",
            "val x = \"release.manager\"",
            " * release.manager holds the record",
            "OperatorRole.AUTHOR",
            "OperatorRole.RELEASE_MANAGER",
        ).map { roleWords.containsMatchIn(it) } shouldContainOnly listOf(true)
        roleWords.containsMatchIn("OperatorRolesX") shouldBe false
    }

    @Test
    fun `no file holds a retired role-valued property name and the plural look-alike is caught`() {
        hits(retired, trackedFiles()).shouldBeEmpty()
        retired.containsMatchIn(RETIRED_A + "s") shouldBe true
        retired.containsMatchIn(RETIRED_B + "s") shouldBe true
    }

    private companion object {
        const val MAX_BYTES = 2_000_000L

        // Built from parts so that this file does not hold the names it forbids.
        const val RETIRED_A = "classifiedBy" + "Role"
        const val RETIRED_B = "classified_by" + "_role"
    }
}
