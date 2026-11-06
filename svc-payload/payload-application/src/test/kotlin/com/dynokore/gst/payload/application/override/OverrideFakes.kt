package com.dynokore.gst.payload.application.override

import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class FakeVersions(
    private val facts: MutableMap<Pair<UUID, Int>, DocumentVersionFacts> = mutableMapOf(),
) : DocumentVersions {
    fun put(
        doc: UUID,
        version: Int,
        total: String,
        entity: String = ENTITY,
    ) {
        facts[doc to version] = DocumentVersionFacts(entity, BigDecimal(total))
    }

    override fun find(
        docUuid: UUID,
        version: Int,
    ) = facts[docUuid to version]

    companion object {
        const val ENTITY = "E-001"
    }
}

/** Holders by user ID; the key is the permission asked for, so a wrong key holds nothing. */
class FakeAuthority(
    private val holders: Set<String>,
) : OverrideAuthority {
    val asked = mutableListOf<Triple<String, String, String>>()

    override fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean {
        asked += Triple(userId, permission, entityRef)
        return permission == VALIDATION_OVERRIDE && userId in holders
    }
}

/** The decision of access-svc for a threshold; [thresholdSgd] null is the default of one approver. */
class FakeDecisions(
    private val thresholdSgd: BigDecimal? = null,
    private val answer: ((String) -> ReleaseOutcome)? = null,
) : ReleaseDecisions {
    val calls = mutableListOf<List<String>>()

    override fun decide(request: ReleaseRequest): ReleaseOutcome {
        val approverRef = request.approverRef
        val priorApproverRefs = request.priorApproverRefs
        val totalSgd = request.totalSgd
        calls += listOf(approverRef) + priorApproverRefs
        answer?.let { return it(approverRef) }
        return when {
            approverRef in priorApproverRefs -> ReleaseOutcome.Denied("SAME_PERSON_TWICE")
            priorApproverRefs.isNotEmpty() -> ReleaseOutcome.Released
            thresholdSgd != null && totalSgd > thresholdSgd -> ReleaseOutcome.AwaitsSecond
            else -> ReleaseOutcome.Released
        }
    }
}

class FakeFindings(
    private val dispositions: MutableMap<Triple<UUID, Int, String>, FindingDisposition> = mutableMapOf(),
) : BlockingFindings {
    fun put(
        doc: UUID,
        version: Int,
        code: String,
        disposition: FindingDisposition,
    ) {
        dispositions[Triple(doc, version, code)] = disposition
    }

    override fun disposition(
        docUuid: UUID,
        version: Int,
        code: String,
    ) = dispositions[Triple(docUuid, version, code)] ?: FindingDisposition.REFUSED
}

class FakeStore : OverrideStore {
    val rows = linkedMapOf<Triple<UUID, Int, String>, StoredOverride>()
    private var tick = 0L

    override fun insert(override: NewOverride): StoredOverride? {
        val key = Triple(override.docUuid, override.version, override.code)
        if (key in rows) return null
        val now = Instant.EPOCH.plusSeconds(tick++)
        val row =
            StoredOverride(
                override.docUuid,
                override.version,
                override.code,
                override.reason,
                override.status,
                override.requestedBy,
                now,
                override.releasedBy,
                now.takeIf { override.releasedBy != null },
            )
        rows[key] = row
        return row
    }

    override fun find(
        docUuid: UUID,
        version: Int,
        code: String,
    ) = rows[Triple(docUuid, version, code)]

    override fun release(
        docUuid: UUID,
        version: Int,
        code: String,
        releasedBy: String,
    ): StoredOverride? {
        val key = Triple(docUuid, version, code)
        val row = rows[key]?.takeIf { it.status == OverrideStatus.PENDING } ?: return null
        val released =
            row.copy(
                status = OverrideStatus.RELEASED,
                releasedBy = releasedBy,
                releasedAt = Instant.EPOCH.plusSeconds(tick++),
            )
        rows[key] = released
        return released
    }

    override fun entries(
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ) = rows.values
        .filter { it.docUuid == docUuid && it.version == version }
        .sortedWith(compareBy({ it.requestedAt }, { it.code }))
        .filter {
            after == null ||
                compareValuesBy(it.requestedAt to it.code, after, { p -> p.first }, { p -> p.second }) > 0
        }.take(limit)
}

class RecordingAudit : AuditTrail {
    data class Entry(
        val kind: String,
        val actor: String,
        val firstApprover: String,
        val reason: String,
    )

    val entries = mutableListOf<Entry>()

    override fun requested(
        userId: String,
        override: StoredOverride,
    ) {
        entries += Entry("requested", userId, override.requestedBy, override.reason)
    }

    override fun released(
        userId: String,
        override: StoredOverride,
    ) {
        entries += Entry("released", userId, override.requestedBy, override.reason)
    }
}
