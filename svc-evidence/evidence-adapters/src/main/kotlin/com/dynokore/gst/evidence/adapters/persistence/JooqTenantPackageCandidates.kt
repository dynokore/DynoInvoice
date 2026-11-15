package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.Acknowledgement.Companion.ACKNOWLEDGEMENT
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.AuditLog.Companion.AUDIT_LOG
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.DecisionTrace.Companion.DECISION_TRACE
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.EvidencePayload.Companion.EVIDENCE_PAYLOAD
import com.dynokore.gst.evidence.application.export.ExportClassSource
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Table
import java.security.MessageDigest
import java.util.UUID

/** The content of one tenant read is larger than the configured limit; nothing is returned (fail closed). */
class PackageSizeRefusal(
    val contentClass: PackageContentClass,
    val maxBytes: Long,
) : RuntimeException("the ${contentClass.name} content of the tenant exceeds the limit of $maxBytes bytes")

/**
 * The package candidates of the four sourced content classes of one tenant (INV-REQ-877). Each source selects the
 * rows of the bound tenant, with the tenant predicate in the WHERE clause as well as the binding (CS-19), ordered by
 * entity, period and seq. Rows are streamed and the running total of content bytes is checked per row, so a tenant
 * larger than [maxBytes] is refused before it is held in memory. [maxBytes] is configuration, never a constant.
 */
class JooqTenantPackageCandidates(
    private val dsl: DSLContext,
    private val maxBytes: Long,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private class Chain(
        val contentClass: PackageContentClass,
        val table: Table<*>,
        val tenant: Field<UUID?>,
        val entity: Field<String?>,
        val period: Field<String?>,
        val seq: Field<Long?>,
        val content: Field<ByteArray?>,
    )

    private val chains =
        listOf(
            Chain(
                PackageContentClass.TRANSMITTED_XML,
                EVIDENCE_PAYLOAD,
                EVIDENCE_PAYLOAD.TENANT_ID,
                EVIDENCE_PAYLOAD.ENTITY_REF,
                EVIDENCE_PAYLOAD.PERIOD_REF,
                EVIDENCE_PAYLOAD.SEQ,
                EVIDENCE_PAYLOAD.CONTENT,
            ),
            Chain(
                PackageContentClass.ACKNOWLEDGEMENTS,
                ACKNOWLEDGEMENT,
                ACKNOWLEDGEMENT.TENANT_ID,
                ACKNOWLEDGEMENT.ENTITY_REF,
                ACKNOWLEDGEMENT.PERIOD_REF,
                ACKNOWLEDGEMENT.SEQ,
                ACKNOWLEDGEMENT.CONTENT,
            ),
            Chain(
                PackageContentClass.DECISION_TRACES,
                DECISION_TRACE,
                DECISION_TRACE.TENANT_ID,
                DECISION_TRACE.ENTITY_REF,
                DECISION_TRACE.PERIOD_REF,
                DECISION_TRACE.SEQ,
                DECISION_TRACE.CONTENT,
            ),
            Chain(
                PackageContentClass.AUDIT_LOG,
                AUDIT_LOG,
                AUDIT_LOG.TENANT_ID,
                AUDIT_LOG.ENTITY_REF,
                AUDIT_LOG.PERIOD_REF,
                AUDIT_LOG.SEQ,
                AUDIT_LOG.CONTENT,
            ),
        )

    /** One source per sourced class; the two unsourced classes have none. */
    fun sources(): Map<PackageContentClass, ExportClassSource> =
        chains.associate { chain -> chain.contentClass to ExportClassSource { tenant -> read(chain, tenant) } }

    private fun read(
        chain: Chain,
        tenant: TenantId,
    ): List<PackageCandidate> {
        val candidates = mutableListOf<PackageCandidate>()
        var total = 0L
        dsl
            .select(chain.entity, chain.period, chain.seq, chain.content)
            .from(chain.table)
            .where(chain.tenant.eq(tenant.value))
            
            .orderBy(chain.entity, chain.period, chain.seq)
            .fetchSize(FETCH_SIZE)
            .fetchLazy()
            .use { cursor ->
                for (row in cursor) {
                    val bytes = checkNotNull(row.get(chain.content)) { "chain content is null" }
                    total += bytes.size
                    if (total > maxBytes) throw PackageSizeRefusal(chain.contentClass, maxBytes)
                    val entity = checkNotNull(row.get(chain.entity))
                    val period = checkNotNull(row.get(chain.period))
                    val name = "${checkNotNull(row.get(chain.seq))}-${scopeDigest(entity, period)}"
                    candidates +=
                        PackageCandidate(
                            tenant,
                            EntityRef(entity),
                            PeriodRef(period),
                            PackageItem(chain.contentClass, name, bytes),
                        )
                }
            }
        return candidates
    }

    private companion object {
        const val FETCH_SIZE = 200
        const val DIGEST_HEX = 16

        /** The first 16 hex digits of sha256(entityRef, 0x00, periodRef): a name that cannot collide across scopes. */
        fun scopeDigest(
            entity: String,
            period: String,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(entity.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(period.toByteArray(Charsets.UTF_8))
            return digest.digest().joinToString("") { "%02x".format(it) }.take(DIGEST_HEX)
        }
    }
}
