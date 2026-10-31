package com.dynokore.gst.evidence.retention

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED

/**
 * The caller edge of `setPolicy` (INV-Q-12586 A): the line `POST /internal/v1/policies/{variationPoint}` for
 * `console-bff` comes from the released service-runtime, which generates it from its own contract; this service holds
 * no `sync-edge-callers.tsv` of its own.
 */
class PolicyCallerTest {
    private val line = "POST\t/internal/v1/policies/{variationPoint}\tconsole-bff"
    private val years = mapOf(STRUCTURED to 5, ACK to 5, SOURCE to 5)

    @Test
    fun `the service-runtime jar holds the setPolicy caller line and this service holds none`() {
        val resources = javaClass.classLoader.getResources("META-INF/gst/sync-edge-callers.tsv").toList()
        val own = resources.filter { it.protocol == "file" && it.path.contains("evidence-bootstrap") }
        own shouldBe emptyList()
        val runtime =
            resources.single {
                it.path.contains("service-runtime-") &&
                    !it.path.contains("service-runtime-api")
            }
        runtime.readText().lines().count { it == line } shouldBe 1
    }

    @Test
    fun `only the workload token of console-bff reaches setPolicy`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            val tenant = s.newTenant()
            for (caller in listOf("control-plane-svc", "console-bff-two", "Console-Bff")) {
                val answer = s.setAs(caller, tenant, years, "baseline")
                answer.status shouldBe 403
                answer.code shouldBe "SRT-004"
                s.stored(tenant) shouldBe 0
            }
            s.setAs(null, tenant, years, "baseline").status shouldBe 401
            s.stored(tenant) shouldBe 0
            s.setAs("console-bff", tenant, years, "baseline").accepted()
            s.stored(tenant) shouldBe 1
        }

    @Test
    fun `without an end-user resolver setPolicy answers 401 SRT-003 and stores nothing`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java)).use { s ->
            val tenant = s.newTenant()
            val answer = s.setAs("console-bff", tenant, years, "baseline")
            answer.status shouldBe 401
            answer.code shouldBe "SRT-003"
            s.stored(tenant) shouldBe 0
        }

    @Test
    fun `with an end-user resolver and no role resolver setPolicy answers 403 SRT-004 and stores nothing`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir, listOf(WorkloadTokensOnly::class.java, EndUserTokensOnly::class.java)).use { s ->
            val tenant = s.newTenant()
            val answer = s.setAs("console-bff", tenant, years, "baseline")
            answer.status shouldBe 403
            answer.code shouldBe "SRT-004"
            s.stored(tenant) shouldBe 0
        }
}
