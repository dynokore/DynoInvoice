package com.dynokore.gst.evidence.retention

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.evidence.syncedge.ContractDocument
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.core.io.ByteArrayResource
import java.nio.file.Path
import com.dynokore.gst.evidence.application.digest.MaxRetention as DigestMaxRetention
import com.dynokore.gst.evidence.domain.retention.RetentionTier.ACKNOWLEDGEMENTS_AND_TRACES as ACK
import com.dynokore.gst.evidence.domain.retention.RetentionTier.SOURCE_DOCUMENT_REFERENCES as SOURCE
import com.dynokore.gst.evidence.domain.retention.RetentionTier.STRUCTURED_SUBMISSIONS as STRUCTURED

/** getMaxEvidenceRetention of the published contract over seeded settings (INV-REQ-3767, INV-AC-6532). */
class MaxRetentionTest {
    private val reason = "obligation of the parent"

    private fun all(years: Int) = mapOf(STRUCTURED to years, ACK to years, SOURCE to years)

    private fun contractProperties(): Set<String> {
        val text = ContractDocument.text(javaClass.classLoader)
        val document = YamlMapFactoryBean().apply { setResources(ByteArrayResource(text.toByteArray())) }.`object`!!

        @Suppress("UNCHECKED_CAST")
        val schemas = (document["components"] as Map<String, Any>)["schemas"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        return ((schemas["MaxEvidenceRetention"] as Map<String, Any>)["properties"] as Map<String, Any>).keys
    }

    @Test
    fun `INV-AC-6532 the figure is P5Y with no setting, P10Y with 7 and 10`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val none = s.getMax("control-plane-svc")
            none.status shouldBe 200
            none.body.get("maxRetention").asString() shouldBe "P5Y"

            val seven = s.newTenant()
            s.setTenant(seven, all(7), reason).accepted()
            s
                .getMax("control-plane-svc")
                .body
                .get("maxRetention")
                .asString() shouldBe "P7Y"
            val ten = s.newTenant()
            s.setEntity(ten, "ENT-A", all(10), reason).accepted()

            val answer = s.getMax("control-plane-svc")
            answer.status shouldBe 200
            answer.body.get("maxRetention").asString() shouldBe "P10Y"
            answer.body
                .propertyNames()
                .toSet() shouldBe contractProperties()
            contractProperties() shouldBe setOf("maxRetention")
        }

    @Test
    fun `INV-AC-6532 a caller other than control-plane-svc is refused with 403`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            for (caller in listOf("access-svc", "control-plane-svc-two", "Control-Plane-Svc", null)) {
                val answer = s.getMax(caller)
                answer.status shouldBe if (caller == null) 401 else 403
                answer.body.has("maxRetention") shouldBe false
            }
        }

    @Test
    fun `INV-AC-6532 a superseded 10 replaced by 6 leaves the larger of the others`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val other = s.newTenant()
            s.setTenant(other, all(7), reason).accepted()
            val tenant = s.newTenant()
            s.setEntity(tenant, "ENT-A", all(10), reason).accepted()
            s
                .getMax("control-plane-svc")
                .body
                .get("maxRetention")
                .asString() shouldBe "P10Y"
            s.setEntity(tenant, "ENT-A", all(6), reason).accepted()
            s
                .getMax("control-plane-svc")
                .body
                .get("maxRetention")
                .asString() shouldBe "P7Y"
        }

    @Test
    fun `INV-AC-6532 a read that fails answers 503 with the EVD-001 envelope and no figure`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val answer = s.withBrokenReads { s.getMax("control-plane-svc") }
            answer.status shouldBe 503
            answer.code shouldBe "EVD-001"
            answer.body.get("status").asInt() shouldBe 503
            answer.body.has("maxRetention") shouldBe false
            answer.body
                .propertyNames()
                .toSet() shouldBe
                setOf("type", "title", "status", "correlationId", "code", "severity", "disposition", "remediationOwner")
            s
                .getMax("control-plane-svc")
                .body
                .get("maxRetention")
                .asString() shouldBe "P5Y"
        }

    @Test
    fun `the generated ErrorCode holds EVD-001 as retryable BLOCK, TERMINAL for the Platform`() {
        val code = ErrorCode.entries.single { it.code == "EVD-001" }
        code.severity.name shouldBe "BLOCK"
        code.disposition?.name shouldBe "TERMINAL"
        code.owner shouldBe "Platform"
    }

    @Test
    fun `the digest retention bean gives the figure of getMaxEvidenceRetention in whole years`(
        @TempDir dir: Path,
    ): Unit =
        RetentionService.start(dir).use { s ->
            s.clearAll()
            val bean = s.bean(DigestMaxRetention::class.java)
            bean.longest().years shouldBe 5
            s.setEntity(s.newTenant(), "ENT-A", all(10), reason).accepted()
            bean.longest().years shouldBe 10
            s
                .getMax("control-plane-svc")
                .body
                .get("maxRetention")
                .asString() shouldBe "P10Y"
        }
}
