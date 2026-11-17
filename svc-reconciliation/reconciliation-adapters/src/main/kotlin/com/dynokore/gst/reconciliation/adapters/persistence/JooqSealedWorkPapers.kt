package com.dynokore.gst.reconciliation.adapters.persistence

import com.dynokore.gst.reconciliation.adapters.persistence.jooq.tables.references.WORK_PAPER_SEAL
import com.dynokore.gst.reconciliation.application.workpaper.SealedRef
import com.dynokore.gst.reconciliation.application.workpaper.SealedWorkPapers
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperReference
import com.dynokore.gst.reconciliation.domain.workpaper.WorkPaperSeal
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectStore
import org.jooq.DSLContext
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID

/**
 * The sealed work-paper store (INV-T-24187, INV-AC-24183, INV-Q-24164 A): the bytes go to the object store once under
 * the immutable key [WorkPaperReference.of] builds, and an append-only seal row records the key and the SHA-256 of
 * exactly those bytes. The caller binds the tenant and opens the transaction. An object left behind by a rolled-back
 * transaction is harmless: the next attempt writes the same immutable key and the committed row records the hash of
 * the bytes it wrote.
 */
class JooqSealedWorkPapers(
    private val dsl: DSLContext,
    private val store: ObjectStore,
    private val clock: Clock,
    private val retentionDays: Long,
) : SealedWorkPapers {
    init {
        require(retentionDays > 0) { "the work-paper retention is a positive number of days" }
    }

    override fun latest(
        tenant: String,
        runId: String,
    ): SealedRef? {
        val tenantId = UUID.fromString(tenant)
        return dsl
            .select(WORK_PAPER_SEAL.REFERENCE, WORK_PAPER_SEAL.SHA256, WORK_PAPER_SEAL.SEAL_VERSION)
            .from(WORK_PAPER_SEAL)
            .where(WORK_PAPER_SEAL.TENANT_ID.eq(tenantId))
            .and(WORK_PAPER_SEAL.RUN_ID.eq(runId))
            .orderBy(WORK_PAPER_SEAL.SEAL_VERSION.desc())
            .limit(1)
            .fetchOne()
            ?.let { SealedRef(it.value1().orEmpty(), it.value2().orEmpty().trim(), checkNotNull(it.value3())) }
    }

    override fun put(
        tenant: String,
        entityRef: String,
        runId: String,
        sealVersion: Int,
        bytes: ByteArray,
    ): String {
        val tenantId = UUID.fromString(tenant)
        val reference = WorkPaperReference.of(tenant, runId, sealVersion)
        val now = clock.instant()
        store.put(ObjectKey(reference), bytes, now.plus(Duration.ofDays(retentionDays)))
        dsl
            .insertInto(WORK_PAPER_SEAL)
            .set(WORK_PAPER_SEAL.TENANT_ID, tenantId)
            .set(WORK_PAPER_SEAL.RUN_ID, runId)
            .set(WORK_PAPER_SEAL.SEAL_VERSION, sealVersion)
            .set(WORK_PAPER_SEAL.ENTITY_REF, entityRef)
            .set(WORK_PAPER_SEAL.REFERENCE, reference)
            .set(WORK_PAPER_SEAL.SHA256, WorkPaperSeal.of(bytes).sha256Hex)
            .set(WORK_PAPER_SEAL.SEALED_AT, now.atOffset(ZoneOffset.UTC))
            .execute()
        return reference
    }
}
