package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.application.workspace.DirectTransactions
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Instant

/** In-memory results port: rows with their created_at and failure reason. */
class FakeResults : OffboardingExportResults {
    val rows = mutableMapOf<String, StoredOffboarding>()
    val reasons = mutableMapOf<String, String?>()
    var applyCalls = 0

    fun add(
        ref: String,
        scope: OffboardingScope,
        entityRef: String?,
        createdAt: Instant,
        state: OffboardingState = OffboardingState.REQUESTED,
    ) {
        val holder = RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example")
        val manifest = ExportManifest("EXP-$ref", ExportState.REQUESTED, emptyList())
        rows[ref] =
            StoredOffboarding(
                OffboardingRecord(ref, "T1", scope, entityRef, state, holder, manifest, idempotencyKey = "k-$ref"),
                createdAt,
            )
    }

    override fun find(offboardingRef: String) = rows[offboardingRef]

    override fun applyResult(
        offboardingRef: String,
        state: OffboardingState,
        manifest: ExportManifest,
        failureReason: String?,
    ): Boolean {
        applyCalls++
        val row = rows[offboardingRef]
        val changes = row != null && row.record.state == OffboardingState.REQUESTED
        if (row != null && changes) {
            rows[offboardingRef] = row.copy(record = row.record.copy(state = state, export = manifest))
            reasons[offboardingRef] = failureReason
        }
        return changes
    }

    override fun requestedFor(
        scope: OffboardingScope,
        entityRef: String?,
    ) = rows.values.firstOrNull {
        it.record.scope == scope && it.record.entityRef == entityRef && it.record.state == OffboardingState.REQUESTED
    }
}

/** A TenantStore that counts close calls; no other method is used here. */
fun closeCounter(counter: IntArray): TenantStore =
    TenantStore::class.java.cast(
        Proxy.newProxyInstance(TenantStore::class.java.classLoader, arrayOf(TenantStore::class.java)) { _, method, _ ->
            if (method.name == "close") counter[0]++ else error("${method.name} is not used")
            null
        },
    )

/** The export result use case over fakes (INV-AC-22959). */
class CompleteOffboardingExportTest {
    private val created = Instant.parse("2026-11-14T10:00:00Z")
    private val closes = IntArray(1)
    private val results = FakeResults()
    private val useCase = CompleteOffboardingExport(DirectTransactions, results, closeCounter(closes))
    private val ready = ExportManifest("EXP-1", ExportState.READY, ExportContent.entries)

    init {
        results.add("R1", OffboardingScope.TENANT, null, created)
        results.add("R2", OffboardingScope.ENTITY, "E1", created)
    }

    @Test
    fun `INV-AC-22959 a READY result with six contents makes R1 COMPLETED and closes the tenant once`() {
        assertEquals(ExportResultOutcome.APPLIED, useCase.apply("R1", ready, null))
        assertEquals(
            OffboardingState.COMPLETED,
            results.rows
                .getValue("R1")
                .record.state,
        )
        assertEquals(1, closes[0])
    }

    @Test
    fun `INV-AC-22959 the same result again changes nothing and calls close no further time`() {
        useCase.apply("R1", ready, null)
        assertEquals(ExportResultOutcome.IGNORED, useCase.apply("R1", ready, null))
        assertEquals(
            OffboardingState.COMPLETED,
            results.rows
                .getValue("R1")
                .record.state,
        )
        assertEquals(1, closes[0])
    }

    @Test
    fun `INV-AC-22959 a FAILED result with a reason makes R2 FAILED with the reason and no close`() {
        val failed = ExportManifest("EXP-2", ExportState.FAILED, emptyList())
        assertEquals(ExportResultOutcome.APPLIED, useCase.apply("R2", failed, "no source for AUDIT_LOG"))
        assertEquals(
            OffboardingState.FAILED,
            results.rows
                .getValue("R2")
                .record.state,
        )
        assertEquals("no source for AUDIT_LOG", results.reasons["R2"])
        assertEquals(0, closes[0])
    }

    @Test
    fun `INV-AC-22959 a READY result with five contents leaves the record REQUESTED with no close`() {
        val five = ready.copy(contents = ExportContent.entries.dropLast(1))
        assertEquals(ExportResultOutcome.PENDING, useCase.apply("R1", five, null))
        assertEquals(
            OffboardingState.REQUESTED,
            results.rows
                .getValue("R1")
                .record.state,
        )
        assertEquals(0, closes[0])
    }

    @Test
    fun `INV-AC-22959 a READY result for an ENTITY record does not close the tenant`() {
        useCase.apply("R2", ready, null)
        assertEquals(
            OffboardingState.COMPLETED,
            results.rows
                .getValue("R2")
                .record.state,
        )
        assertEquals(0, closes[0])
    }

    @Test
    fun `INV-AC-22959 a result for a FAILED record is ignored and an unknown record is not found`() {
        results.add("R3", OffboardingScope.TENANT, null, created, OffboardingState.FAILED)
        assertEquals(ExportResultOutcome.IGNORED, useCase.apply("R3", ready, null))
        assertEquals(
            OffboardingState.FAILED,
            results.rows
                .getValue("R3")
                .record.state,
        )
        assertNull(results.reasons["R3"])
        assertEquals(0, results.applyCalls)
        assertEquals(0, closes[0])
        assertEquals(ExportResultOutcome.NOT_FOUND, useCase.apply("nope", ready, null))
    }
}
