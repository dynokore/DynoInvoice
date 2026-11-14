package com.dynokore.gst.ingestion.adapters.permission

import com.dynokore.gst.ingestion.application.permission.EntityPermission
import com.dynokore.gst.ingestion.application.triggers.CoverageOperation
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

/** INV-AC-20939: the scoped permission key asked for each coverage operation, by the acting user id. */
class CoverageWritePermissionAdapterTest {
    private val user = UUID.randomUUID()
    private val calls = mutableListOf<Triple<String, String, String>>()

    private fun adapter(answer: Boolean) =
        CoverageWritePermissionAdapter(
            EntityPermission { actor, entity, key ->
                calls += Triple(actor, entity, key)
                answer
            },
        )

    @Test
    fun `INV-AC-20939 MAP asks entity-config manage for the user and the entity`() {
        adapter(true).permits(user, CoverageOperation.MAP, "E-001") shouldBe true
        calls shouldBe listOf(Triple(user.toString(), "E-001", "entity-config.manage"))
    }

    @Test
    fun `INV-AC-20939 WAIVE asks entity-config manage for the user and the entity`() {
        adapter(true).permits(user, CoverageOperation.WAIVE, "E-002") shouldBe true
        calls shouldBe listOf(Triple(user.toString(), "E-002", "entity-config.manage"))
    }

    @Test
    fun `INV-AC-20939 COVER_ELSEWHERE asks vp-61 set for the user and the entity`() {
        adapter(true).permits(user, CoverageOperation.COVER_ELSEWHERE, "E-001") shouldBe true
        calls shouldBe listOf(Triple(user.toString(), "E-001", "vp-61.set"))
    }

    @Test
    fun `INV-AC-20939 an EntityPermission that refuses refuses every operation`() {
        CoverageOperation.entries.forEach { adapter(false).permits(user, it, "E-001") shouldBe false }
        calls.size shouldBe CoverageOperation.entries.size
    }
}
