package com.dynokore.platform.auditlog

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.util.UUID

/** [OverrideListing] over `override_grant` on PostgreSQL 17 and 18 (INV-AC-1329, INV-REQ-1376). */
@Tag("integration")
class OverrideListingIT {
    private fun AuditHarness.give(
        tenant: UUID,
        entity: String,
        date: LocalDate?,
    ): UUID {
        val doc = UUID.randomUUID()
        val command = overrideCommand(tenant, doc).copy(entity = EntityRef(entity), transactionDate = date)
        asTenant(tenant) { overrides.override(command) }
        return doc
    }

    private fun query(
        tenant: UUID,
        entity: String,
        from: String = "2026-01-01",
        to: String = "2026-03-31",
        reader: String = "auditor-1",
    ) = OverrideListQuery(
        TenantId(tenant),
        EntityRef(entity),
        LocalDate.parse(from),
        LocalDate.parse(to),
        reader,
        CorrelationId("corr-list"),
        CausationId("cmd-list"),
    )

    private fun AuditHarness.list(q: OverrideListQuery) = asTenant(q.tenant.value) { overrides.list(q) }

    private fun AuditHarness.listings(tenant: UUID) =
        count("select count(*) from submission.outbox where data ->> 'action' = 'listOverrides'", listOf(tenant))

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `the range is inclusive and another entity is never listed`(image: String) {
        AuditHarness(image).use { h ->
            val t = UUID.randomUUID()
            val d1 = h.give(t, "E1", LocalDate.of(2026, 1, 15))
            val d2 = h.give(t, "E1", LocalDate.of(2026, 3, 31))
            h.give(t, "E1", LocalDate.of(2026, 4, 1))
            h.give(t, "E2", LocalDate.of(2026, 2, 1))

            val result = h.list(query(t, "E1"))

            assertEquals(listOf(d1, d2), result.dated.map { it.grant.docUuid.value })
            assertEquals(
                listOf(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 3, 31)),
                result.dated.map { it.transactionDate },
            )
            assertTrue(result.undated.isEmpty())
            val first = result.dated.first().grant
            assertEquals("user-7", first.actor)
            assertEquals("UEN confirmed with the supplier", first.reason)
            assertEquals(AuditHarness.MDM_003, first.code)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `an undated override is listed for every range of its entity and for no other entity`(image: String) {
        AuditHarness(image).use { h ->
            val t = UUID.randomUUID()
            val undated = h.give(t, "E1", null)
            h.give(t, "E2", LocalDate.of(2026, 2, 1))

            listOf("2026-01-01" to "2026-03-31", "2030-01-01" to "2030-01-01").forEach { (from, to) ->
                val e1 = h.list(query(t, "E1", from, to))
                assertEquals(listOf(undated), e1.undated.map { it.grant.docUuid.value })
                assertTrue(e1.dated.isEmpty())
                assertEquals(null, e1.undated.single().transactionDate)
            }
            val e2 = h.list(query(t, "E2"))
            assertTrue(e2.undated.isEmpty())
            assertEquals(1, e2.dated.size)
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `entries are ordered by transaction date, then grant time, then id`(image: String) {
        AuditHarness(image).use { h ->
            val t = UUID.randomUUID()
            val late = h.give(t, "E1", LocalDate.of(2026, 3, 1))
            val early = h.give(t, "E1", LocalDate.of(2026, 1, 1))
            val undatedB = h.give(t, "E1", null)
            val result = h.list(query(t, "E1"))
            assertEquals(listOf(early, late), result.dated.map { it.grant.docUuid.value })
            assertEquals(listOf(undatedB), result.undated.map { it.grant.docUuid.value })
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `each listing leaves exactly one listOverrides entry naming the entity, the dates and the reader`(
        image: String,
    ) {
        AuditHarness(image).use { h ->
            val t = UUID.randomUUID()
            h.give(t, "E1", LocalDate.of(2026, 1, 15))
            assertEquals(0, h.listings(t))

            h.list(query(t, "E1", reader = "auditor-1"))
            assertEquals(1, h.listings(t))
            h.list(query(t, "E1", from = "2026-02-01", to = "2026-02-28", reader = "auditor-2"))
            assertEquals(2, h.listings(t))

            val scope =
                "select count(*) from submission.outbox where data ->> 'action' = 'listOverrides' " +
                    "and data ->> 'actor' = ? and data ->> 'scope' = ? and data ->> 'reason' is null and data ->> 'priorState' is null"
            assertEquals(1, h.count(scope, listOf(t), "auditor-1", "entity:E1/from:2026-01-01/to:2026-03-31"))
            assertEquals(1, h.count(scope, listOf(t), "auditor-2", "entity:E1/from:2026-02-01/to:2026-02-28"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `when the audit write fails the listing throws and returns nothing`(image: String) {
        AuditHarness(image).use { h ->
            val t = UUID.randomUUID()
            h.give(t, "E1", LocalDate.of(2026, 1, 15))
            val failing =
                object : AuditWriter {
                    override fun record(
                        entry: AuditEntry,
                        context: EventContext,
                    ): Envelope = throw AuditWriteFailed(IllegalStateException("outbox down"))
                }
            val service =
                JdbcOverrideService(
                    h.dsl,
                    h.boundary,
                    failing,
                    AuditHarness.CATALOGUE,
                    AuditHarness.SCHEMA,
                    AuditHarness.CLOCK,
                )
            var result: OverrideListingResult? = null
            assertThrows(AuditWriteFailed::class.java) { result = h.asTenant(t) { service.list(query(t, "E1")) } }
            assertEquals(null, result)
            assertEquals(0, h.listings(t))

            // The real writer with a refusing outbox does the same.
            h.refuseOutbox()
            assertThrows(AuditWriteFailed::class.java) { h.list(query(t, "E1")) }
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `another tenant's grants for the same entity text never appear`(image: String) {
        AuditHarness(image).use { h ->
            val t1 = UUID.randomUUID()
            val t2 = UUID.randomUUID()
            val mine = h.give(t1, "E1", LocalDate.of(2026, 1, 15))
            val myUndated = h.give(t1, "E1", null)
            h.give(t2, "E1", LocalDate.of(2026, 1, 16))
            h.give(t2, "E1", null)

            val result = h.list(query(t1, "E1"))
            assertEquals(listOf(mine), result.dated.map { it.grant.docUuid.value })
            assertEquals(listOf(myUndated), result.undated.map { it.grant.docUuid.value })

            val other = h.list(query(t2, "E1"))
            assertEquals(1, other.dated.size)
            assertEquals(1, other.undated.size)
            assertTrue((other.dated + other.undated).none { it.grant.docUuid.value in setOf(mine, myUndated) })
        }
    }
}
