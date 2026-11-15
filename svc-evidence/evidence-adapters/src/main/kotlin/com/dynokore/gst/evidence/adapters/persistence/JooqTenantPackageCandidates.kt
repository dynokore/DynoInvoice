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
import org.jooq.impl.DSL
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** The content of one tenant read is larger than the configured limit; nothing is returned (fail closed). */
class PackageSizeRefusal(
    val contentClass: PackageContentClass,
    val maxBytes: Long,
) : RuntimeException("the content of the tenant (${contentClass.name} read) exceeds the limit of $maxBytes bytes")

/**
 * The package candidates of the four sourced content classes of one tenant (INV-REQ-877). Each source selects the
 * rows of the bound tenant, with the tenant predicate in the WHERE clause as well as the binding (CS-19), ordered by
 * entity, period and seq. [maxBytes] bounds the content of the whole tenant, all four chain tables together
 * (INV-AC-22948: a limit below the total of the tenant is refused): each source first sums the content bytes of the
 * tenant in the four tables in the database and refuses above the limit, before any content is held in memory. Rows
 * are then streamed and the running total is checked per row, in case rows were added since the sum. [maxBytes] is
 * configuration, never a constant.
 */
class JooqTenantPackageCandidates(
    private val dsl: DSLContext,
    private val maxBytes: Long,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    private class Columns(
        val tenant: Field<UUID?>,
        val entity: Field<String?>,
        val period: Field<String?>,
        val seq: Field<Long?>,
        val content: Field<ByteArray?>,
    )

    private class Chain(
        val contentClass: PackageContentClass,
        val table: Table<*>,
        val columns: Columns,
    )

    private val chains =
        listOf(
            Chain(
                PackageContentClass.TRANSMITTED_XML,
                EVIDENCE_PAYLOAD,
                Columns(
                    EVIDENCE_PAYLOAD.TENANT_ID,
                    EVIDENCE_PAYLOAD.ENTITY_REF,
                    EVIDENCE_PAYLOAD.PERIOD_REF,
                    EVIDENCE_PAYLOAD.SEQ,
                    EVIDENCE_PAYLOAD.CONTENT,
                ),
            ),
            Chain(
                PackageContentClass.ACKNOWLEDGEMENTS,
                ACKNOWLEDGEMENT,
                Columns(
                    ACKNOWLEDGEMENT.TENANT_ID,
                    ACKNOWLEDGEMENT.ENTITY_REF,
                    ACKNOWLEDGEMENT.PERIOD_REF,
                    ACKNOWLEDGEMENT.SEQ,
                    ACKNOWLEDGEMENT.CONTENT,
                ),
            ),
            Chain(
                PackageContentClass.DECISION_TRACES,
                DECISION_TRACE,
                Columns(
                    DECISION_TRACE.TENANT_ID,
                    DECISION_TRACE.ENTITY_REF,
                    DECISION_TRACE.PERIOD_REF,
                    DECISION_TRACE.SEQ,
                    DECISION_TRACE.CONTENT,
                ),
            ),
            Chain(
                PackageContentClass.AUDIT_LOG,
                AUDIT_LOG,
                Columns(
                    AUDIT_LOG.TENANT_ID,
                    AUDIT_LOG.ENTITY_REF,
                    AUDIT_LOG.PERIOD_REF,
                    AUDIT_LOG.SEQ,
                    AUDIT_LOG.CONTENT,
                ),
            ),
        )

    /** One source per sourced class; the two unsourced classes have none. */
    fun sources(): Map<PackageContentClass, ExportClassSource> =
        chains.associate { chain -> chain.contentClass to ExportClassSource { tenant -> read(chain, tenant) } }

    private fun read(
        chain: Chain,
        tenant: TenantId,
    ): List<PackageCandidate> {
        if (tenantBytes(tenant) > maxBytes) throw PackageSizeRefusal(chain.contentClass, maxBytes)
        val candidates = mutableListOf<PackageCandidate>()
        var total = 0L
        val c = chain.columns
        dsl
            .select(c.entity, c.period, c.seq, c.content)
            .from(chain.table)
            .where(c.tenant.eq(tenant.value))
            .orderBy(c.entity, c.period, c.seq)
            .fetchSize(FETCH_SIZE)
            .fetchLazy()
            .use { cursor ->
                for (row in cursor) {
                    val bytes = checkNotNull(row.get(c.content)) { "chain content is null" }
                    total += bytes.size
                    if (total > maxBytes) throw PackageSizeRefusal(chain.contentClass, maxBytes)
                    val entity = checkNotNull(row.get(c.entity))
                    val period = checkNotNull(row.get(c.period))
                    val name = "${checkNotNull(row.get(c.seq))}-${scopeDigest(entity, period)}"
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

    /** The content bytes of the tenant over all four sourced tables; null sums (no rows) count as zero. */
    private fun tenantBytes(tenant: TenantId): Long =
        chains.fold(0L) { sum, chain ->
            val c = chain.columns
            val bytes =
                dsl
                    .select(DSL.sum(DSL.octetLength(c.content.coerce(String::class.java))))
                    .from(chain.table)
                    .where(c.tenant.eq(tenant.value))
                    .fetchOne()
                    ?.value1()
            sum + (bytes?.toLong() ?: 0L)
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
            return digest.digest().let { HexFormat.of().formatHex(it) }.take(DIGEST_HEX)
        }
    }
}
