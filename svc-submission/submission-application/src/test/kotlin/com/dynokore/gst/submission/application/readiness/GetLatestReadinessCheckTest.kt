package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.gate.VALIDATION_OVERRIDE
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class GetLatestReadinessCheckTest {
    private val checkedAt = Instant.parse("2026-11-12T08:00:00Z")

    private class FakeReader(
        private val byPeriod: Map<Pair<String, String>, List<StoredReadinessCheck>>,
        private val order: MutableList<String>,
    ) : ReadinessCheckReader {
        val calls = mutableListOf<Pair<String, String>>()

        override fun latest(
            entityRef: String,
            periodRef: String,
        ): StoredReadinessCheck? {
            order += "reader"
            calls += entityRef to periodRef
            return byPeriod[entityRef to periodRef]?.maxByOrNull { it.seq }
        }

        override fun page(
            entityRef: String,
            periodRef: String,
            limit: Int,
            before: Long?,
        ): List<StoredReadinessCheck> = byPeriod[entityRef to periodRef].orEmpty()
    }

    private class FakeAuthority(
        private val holds: Boolean,
        private val order: MutableList<String>,
    ) : OverrideAuthority {
        val calls = mutableListOf<Triple<String, String, String>>()

        override fun holds(
            userId: String,
            permission: String,
            entityRef: String,
        ): Boolean {
            order += "authority"
            calls += Triple(userId, permission, entityRef)
            return holds
        }
    }

    private fun check(
        seq: Long,
        entityRef: String,
        periodRef: String,
    ) = StoredReadinessCheck(
        seq,
        StoredReadinessCheck.checkIdOf(seq),
        entityRef,
        periodRef,
        CheckVerdict.READY,
        checkedAt,
        "U1",
        null,
    )

    private val checksOfP1 = listOf(check(1, "E1", "P1"), check(2, "E1", "P1"), check(3, "E1", "P1"))

    @Test
    fun `INV-AC-21136 a held permission answers Found with the reader's latest check, not the first of the list`() {
        val order = mutableListOf<String>()
        val reader = FakeReader(mapOf(("E1" to "P1") to checksOfP1), order)
        val authority = FakeAuthority(holds = true, order)
        val useCase = GetLatestReadinessCheck(reader, authority)

        val result = useCase.execute("U1", "E1", "P1")

        val found = result.shouldBeInstanceOf<LatestCheckResult.Found>()
        found.check.checkId shouldBe "chk-3"
    }

    @Test
    fun `INV-AC-21136 a held permission asks the authority once with userId, VALIDATION_OVERRIDE, entityRef first`() {
        val order = mutableListOf<String>()
        val reader = FakeReader(mapOf(("E1" to "P1") to checksOfP1), order)
        val authority = FakeAuthority(holds = true, order)
        val useCase = GetLatestReadinessCheck(reader, authority)

        useCase.execute("U1", "E1", "P1")

        authority.calls shouldBe listOf(Triple("U1", VALIDATION_OVERRIDE, "E1"))
        reader.calls shouldBe listOf("E1" to "P1")
        order shouldBe listOf("authority", "reader")
    }

    @Test
    fun `INV-AC-21136 a period with no stored check is NotFound once the permission holds`() {
        val order = mutableListOf<String>()
        val reader = FakeReader(mapOf(("E1" to "P1") to checksOfP1), order)
        val authority = FakeAuthority(holds = true, order)
        val useCase = GetLatestReadinessCheck(reader, authority)

        val result = useCase.execute("U1", "E1", "P2")

        result shouldBe LatestCheckResult.NotFound
    }

    @Test
    fun `INV-AC-21136 a denying authority is Forbidden and the reader is never called`() {
        val order = mutableListOf<String>()
        val reader = FakeReader(mapOf(("E1" to "P1") to checksOfP1), order)
        val authority = FakeAuthority(holds = false, order)
        val useCase = GetLatestReadinessCheck(reader, authority)

        val result = useCase.execute("U1", "E1", "P1")

        result shouldBe LatestCheckResult.Forbidden
        reader.calls shouldBe emptyList()
    }
}
