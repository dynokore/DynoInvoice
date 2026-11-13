package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.version.GetLatestDocumentVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** The latest-version read, booted over the real database like the override wiring (INV-AC-21135). */
class LatestVersionWiringTest : OverrideWiringBase() {
    @Test
    fun `INV-AC-21135 one GetLatestDocumentVersion bean is wired over the replica-backed authority`() {
        AccessStub().use { stub ->
            withService(localReplica("u-1" to "validation.override"), stub) { service ->
                val ctx = checkNotNull(service.context)
                val beans = ctx.getBeansOfType(GetLatestDocumentVersion::class.java)
                beans.size shouldBe 1
                val authority = ctx.getBean(OverrideAuthority::class.java)
                (authority is ReplicaOverrideAuthority) shouldBe true
                val field =
                    GetLatestDocumentVersion::class.java.getDeclaredField("authority").apply {
                        isAccessible =
                            true
                    }
                (field.get(beans.values.single()) === authority) shouldBe true
                // served end to end: a document without a stored version is 404 for a caller holding the key
                val answer =
                    service.send(
                        "GET",
                        "/internal/v1/payload/documents/${UUID.randomUUID()}/latest-version",
                        "W:subject-console-bff",
                        "Bearer U-u-1",
                        null,
                    )
                answer.status shouldBe 404
            }
        }
    }
}
