package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.served.ContractDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * INV-AC-9383, lifecycle resource: the data lines of `META-INF/gst/break-glass-lifecycle-operations.tsv` equal, in
 * order, the method and path of the five lifecycle operationIds of the staged contract; no sixth line.
 */
class BreakGlassLifecycleResourceTest {
    private val lifecycle =
        listOf(
            "unlockBreakGlass",
            "approveBreakGlassUnlock",
            "signInBreakGlass",
            "resealBreakGlass",
            "recordBreakGlassAction",
        )

    private fun lines(): List<String> {
        val urls =
            javaClass.classLoader
                .getResources("META-INF/gst/break-glass-lifecycle-operations.tsv")
                .toList()
        assertThat(urls).describedAs("one resource on the class path").hasSize(1)
        return urls.single().readText().removeSuffix("\n").split('\n')
    }

    @Test
    fun `INV-AC-9383 the five lines equal method and path of the five operations of the contract`() {
        val document = ContractDocument.read()
        val expected =
            lifecycle.map { id ->
                val op = document.single { it.operationId == id }
                "${op.method}\t${op.path}"
            }
        assertThat(lines()).isEqualTo(expected)
        assertThat(lines()).hasSize(5)
    }

    @Test
    fun `INV-AC-9383 the lines are the literal five and a look-alike path is not among them`() {
        assertThat(lines())
            .containsExactly(
                "POST\t/internal/v1/access/break-glass",
                "POST\t/internal/v1/access/break-glass/approvals",
                "POST\t/internal/v1/access/break-glass/{unlockRef}/sign-in",
                "POST\t/internal/v1/access/break-glass/{unlockRef}/reseal",
                "POST\t/internal/v1/access/break-glass/{unlockRef}/actions",
            )
        assertThat(lines()).doesNotContain(
            "GET\t/internal/v1/access/break-glass",
            "POST\t/internal/v1/access/break-glass/approvals/x",
            "POST\t/internal/v1/access/break-glass-actions",
        )
    }
}
