package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * INV-T-15800, fail-closed gate (INV-Q-10027): the callers of the consultant operations are decided by permission keys
 * only. The subject is the text of every `.kt` file directly under the three `consultant` directories in `src/main`;
 * none may contain one of four role string literals, compared as whole tokens. `implementation.consultant` is the
 * role of the grant written (data) and is taken from the `Role` constant, so it never appears as a literal either.
 */
class ConsultantGateTest {
    private val roots =
        listOf(
            "../access-domain/src/main/kotlin/com/dynokore/gst/access/domain/consultant",
            "../access-application/src/main/kotlin/com/dynokore/gst/access/application/consultant",
            "../access-adapters/src/main/kotlin/com/dynokore/gst/access/adapters/consultant",
        )

    private val forbidden =
        setOf("\"platform.support\"", "\"deployment.operator\"", "\"platform.engineer\"", "\"tenant.admin\"")

    private val token = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")

    /** The offending literals of [text], whole tokens only: `"platform.supporter"` is a different token. */
    private fun findings(text: String): List<String> =
        token
            .findAll(text)
            .map { it.value }
            .filter { it in forbidden }
            .toList()

    private fun subject(): Map<String, String> =
        roots
            .flatMap { dir ->
                File(dir).listFiles { f -> f.isFile && f.name.endsWith(".kt") }.orEmpty().toList()
            }.associate { it.path to it.readText() }

    @Test
    fun `the consultant sources of the three modules name no role of a caller`() {
        val files = subject()
        assertThat(files.keys).anyMatch { it.endsWith("ConsultantGrants.kt") }.allMatch { it.contains("src/main") }
        assertThat(files.filterValues { findings(it).isNotEmpty() }.keys).isEmpty()
    }

    @Test
    fun `a role comparison line added to the request use case fails the gate`() {
        val request = subject().entries.single { it.key.endsWith("ConsultantGrants.kt") }.value
        val tampered = request + "\nfun probe(caller: Any) = if (caller.role == \"platform.support\") 1 else 0\n"
        assertThat(findings(tampered)).containsExactly("\"platform.support\"")
        listOf("deployment.operator", "platform.engineer", "tenant.admin").forEach {
            assertThat(findings("val x = \"$it\"")).containsExactly("\"$it\"")
        }
    }

    @Test
    fun `the same line under src test is outside the subject and a look-alike literal is another token`() {
        assertThat(File("src/test/kotlin/com/dynokore/gst/access/bootstrap/ConsultantGateTest.kt").path)
            .doesNotContain("src/main")
        assertThat(subject().keys).noneMatch { it.contains("src/test") }
        assertThat(findings("if (caller.role == \"platform.supporter\") 1")).isEmpty()
        assertThat(findings("val s = \"x platform.support y\"")).isEmpty()
    }
}
