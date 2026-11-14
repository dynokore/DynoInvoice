package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.workspace.DirectTransactions
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.gst.tenancy.application.workspace.InMemoryWorkspaceStore
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** An overdue REQUESTED record expires when the next request for the scope arrives (INV-AC-22959). */
class OffboardExpiryTest {
    private val tenantRef = "00000000-0000-0000-0000-0000000000a1"
    private val created = Instant.parse("2026-11-14T10:00:00Z")
    private val limit = Duration.ofHours(24)
    private val results = FakeResults()
    private var now = created
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    private val expiry = ExpireOverdueOffboarding(results, clock, limit)

    /** The blocking view of the same rows: a record not FAILED blocks. */
    private val store =
        object : OffboardingStore {
            val inserted = mutableListOf<OffboardingRecord>()

            override fun byIdempotencyKey(key: String) = null

            override fun blocking(
                scope: OffboardingScope,
                entityRef: String?,
            ) = (results.rows.values.map { it.record } + inserted).firstOrNull {
                it.scope == scope && it.entityRef == entityRef && it.state != OffboardingState.FAILED
            }

            override fun nextOffboardingRef() = "R-NEW"

            override fun insert(record: OffboardingRecord) {
                inserted += record
            }
        }
    private val tenants = InMemoryTenantContext(TenantId(UUID.fromString(tenantRef)))
    private val tenantStore =
        InMemoryWorkspaceStore(
            tenants,
            TenantRecord(UUID.fromString(tenantRef), "TEN-A1", "Legal", "PLAN", "C-1", state = TenantState.OPEN.name),
        )
    private val structure: StructureStore =
        StructureStore::class.java.cast(
            Proxy.newProxyInstance(
                StructureStore::class.java.classLoader,
                arrayOf(StructureStore::class.java),
            ) { _, m, _ ->
                error("${m.name} is not used")
            },
        )
    private val authority = ActionAuthority { _, _, _ -> false }
    private val holder = RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example")

    private fun request(): Any =
        OffboardTenant(
            DirectTransactions,
            structure,
            store,
            { _, _ -> 0 },
            { ExportManifest("EXP-N", ExportState.REQUESTED, ExportContent.entries.take(2)) },
            authority,
            tenants,
            tenantStore,
            expiry,
        ).forWholeTenant(tenantRef, "k-new", OffboardRequest(OffboardingScope.TENANT, null, holder))

    @Test
    fun `INV-AC-22959 after the limit the old record becomes FAILED and the new request is accepted`() {
        results.add("R1", OffboardingScope.TENANT, null, created)
        now = created.plus(limit).plusSeconds(1)
        val outcome = request()
        assertInstanceOf(OffboardOutcome.Accepted::class.java, outcome)
        assertEquals(
            OffboardingState.FAILED,
            results.rows
                .getValue("R1")
                .record.state,
        )
        assertEquals("no export result within PT24H", results.reasons["R1"])
    }

    @Test
    fun `INV-AC-22959 within the limit the request is refused TNC-010 and the record stays REQUESTED`() {
        results.add("R1", OffboardingScope.TENANT, null, created)
        now = created.plus(limit)
        val outcome = assertInstanceOf(OffboardOutcome.Conflict::class.java, request())
        assertEquals("TNC-010", outcome.code)
        assertEquals(
            OffboardingState.REQUESTED,
            results.rows
                .getValue("R1")
                .record.state,
        )
    }

    @Test
    fun `INV-AC-22959 a COMPLETED or FAILED record is never expired`() {
        results.add("C1", OffboardingScope.TENANT, null, created, OffboardingState.COMPLETED)
        results.add("F1", OffboardingScope.ENTITY, "E1", created, OffboardingState.FAILED)
        now = created.plus(limit).plus(Duration.ofDays(30))
        assertFalse(expiry.expireOverdue(OffboardingScope.TENANT, null))
        assertFalse(expiry.expireOverdue(OffboardingScope.ENTITY, "E1"))
        assertEquals(
            OffboardingState.COMPLETED,
            results.rows
                .getValue("C1")
                .record.state,
        )
        assertEquals(
            OffboardingState.FAILED,
            results.rows
                .getValue("F1")
                .record.state,
        )
        assertTrue(results.reasons.isEmpty())
    }

    @Test
    fun `INV-AC-22959 a late result for an expired record is ignored`() {
        results.add("R1", OffboardingScope.TENANT, null, created)
        now = created.plus(limit).plusSeconds(1)
        assertTrue(expiry.expireOverdue(OffboardingScope.TENANT, null))
        val closes = IntArray(1)
        val late = CompleteOffboardingExport(DirectTransactions, results, closeCounter(closes))
        val ready = ExportManifest("EXP-1", ExportState.READY, ExportContent.entries)
        assertEquals(ExportResultOutcome.IGNORED, late.apply("R1", ready, null))
        assertEquals(0, closes[0])
    }
}
