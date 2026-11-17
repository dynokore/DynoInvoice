package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidenceArtefact.Companion.EVIDENCE_ARTEFACT
import com.dynokore.gst.evidence.application.export.ExportClassSource
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.ObjectKey
import com.dynokore.platform.evidencestore.ObjectKeyPrefix
import com.dynokore.platform.evidencestore.ObjectStore
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.HexFormat

/** The sealed bytes of a work-paper do not match the hash recorded for [reference] (INV-AC-24184). */
class WorkPaperIntegrityRefusal(
    val reference: String,
) : RuntimeException("the sealed bytes of the work-paper $reference do not match the recorded hash")

/** A recorded work-paper that cannot be exported: [reason] names why; never carries content. */
class WorkPaperRefusal(
    reason: String,
) : RuntimeException(reason)

/**
 * The export source of RECONCILIATION_WORK_PAPERS (INV-REQ-877, INV-AC-24184, INV-Q-24164 A): one candidate per
 * recorded artefact row of type RECONCILIATION_WORK_PAPER of the bound tenant, with the sealed bytes read from the
 * object store by reference and verified against the recorded SHA-256. Fail closed (OWASP ASVS V1): the tenant
 * predicate is in the WHERE clause as well as the binding (CS-19); a reference that is not
 * `reconciliation/<tenant>/<runId>/v<n>` of the bound tenant is refused before any read; the bytes under the tenant
 * prefix are summed and a total above [maxBytes] is refused before any object is read. [maxBytes] is configuration.
 */
class JooqReconciliationWorkPapers(
    private val dsl: DSLContext,
    private val store: ObjectStore,
    private val maxBytes: Long,
) : ExportClassSource {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    override fun candidates(tenant: TenantId): List<PackageCandidate> {
        val prefix = "$ROOT/${tenant.value}/"
        if (store.bytesUnder(ObjectKeyPrefix(prefix)) > maxBytes) throw PackageSizeRefusal(CLASS, maxBytes)
        val a = EVIDENCE_ARTEFACT
        val rows =
            dsl
                .select(a.ENTITY_REF, a.REFERENCE, a.HASH)
                .from(a)
                .where(a.TENANT_ID.eq(tenant.value), a.ARTEFACT_TYPE.eq(TYPE))
                .orderBy(a.ENTITY_REF, a.REFERENCE)
                .fetch()
        val candidates = mutableListOf<PackageCandidate>()
        var total = 0L
        for (row in rows) {
            val reference = checkNotNull(row.get(a.REFERENCE))
            val candidate = candidate(tenant, row.get(a.ENTITY_REF), reference, row.get(a.HASH))
            total += candidate.item.bytes().size
            if (total > maxBytes) throw PackageSizeRefusal(CLASS, maxBytes)
            candidates += candidate
        }
        return candidates
    }

    private fun candidate(
        tenant: TenantId,
        entity: String?,
        reference: String,
        hash: String?,
    ): PackageCandidate {
        if (entity == null) throw WorkPaperRefusal("a recorded work-paper has no entity")
        val name = itemName(tenant, reference)
        val bytes = store.get(ObjectKey(reference))
        if (sha256(bytes) != hash) throw WorkPaperIntegrityRefusal(reference)
        val period = PeriodRef(periodOf(bytes, reference))
        return PackageCandidate(tenant, EntityRef(entity), period, PackageItem(CLASS, name, bytes))
    }

    /** `<runId>-v<n>.json` of a reference that has exactly the four segments of the bound tenant. */
    private fun itemName(
        tenant: TenantId,
        reference: String,
    ): String {
        val parts = reference.split('/')
        val ok =
            parts.size == SEGMENTS && parts[0] == ROOT && parts[1] == tenant.value.toString() &&
                RUN_ID.matches(parts[RUN_INDEX]) && VERSION.matches(parts[VERSION_INDEX])
        if (!ok) throw WorkPaperRefusal("a recorded work-paper reference is not of the bound tenant")
        return "${parts[RUN_INDEX]}-${parts[VERSION_INDEX]}.json"
    }

    private fun periodOf(
        bytes: ByteArray,
        reference: String,
    ): String {
        val period = MAPPER.readTree(bytes).path("header").path("periodRef")
        val text = if (period.isString) period.asString() else ""
        if (text.isBlank()) throw WorkPaperRefusal("the work-paper $reference has no header.periodRef")
        return text
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).let { HexFormat.of().formatHex(it) }

    private companion object {
        val CLASS = PackageContentClass.RECONCILIATION_WORK_PAPERS
        const val TYPE = "RECONCILIATION_WORK_PAPER"
        const val ROOT = "reconciliation"
        const val SEGMENTS = 4
        const val RUN_INDEX = 2
        const val VERSION_INDEX = 3
        val RUN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,120}")
        val VERSION = Regex("v[0-9]{1,9}")
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}
