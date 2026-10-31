package com.dynokore.gst.evidence.adapters.jobs

import com.dynokore.gst.evidence.application.integrity.ChainVerificationRecorder
import com.dynokore.gst.evidence.domain.integrity.RunCause
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobBody
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobRunContext
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The (tenant, entity) pairs the verification job runs for (INV-Q-7251 A (4)): key columns from the SECURITY DEFINER
 * function `verification_entities`, owned by `evidence_sys` (TPL-09, ARC 4.3 step 1a), read as the platform tenant
 * the way service-runtime's own cross-tenant maintenance reads its sys functions.
 *
 * The reserved tenant-wide entity of the audit chains (`EntityRef.TENANT_WIDE`) cannot be a [JobTarget.Entity]
 * (service-runtime refuses it), so a pair that names it is left out here and those chains are not verified by this
 * task; INV-T-13295 and INV-T-13297 add them (INV-Q-12984 A). The SQL function lists them without a filter.
 */
class VerificationEntitySource(
    private val scope: TenantScope,
    private val dsl: DSLContext,
) : JobEntitySource {
    private val mapper = JsonMapper.builder().build()

    override fun entities(job: JobDefinition): List<JobTarget.Entity> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                val json =
                    dsl
                        .select(
                            DSL.function(DSL.name("evidence", "verification_entities"), SQLDataType.JSONB),
                        ).fetchOne()
                        ?.value1()
                        ?.data()
                        ?: "[]"
                val pairs = mapper.readTree(json)
                (0 until pairs.size())
                    .map { pairs[it] }
                    .map {
                        EntityRef(it["entity_ref"].asString()) to
                            TenantId(UUID.fromString(it["tenant_id"].asString()))
                    }.filterNot { (entity, _) -> entity.isTenantWide }
                    .map { (entity, tenant) -> JobTarget.Entity(tenant, entity) }
            }
        }
}

/**
 * The body of `evidence-chain-verification` for one entity: one tenant transaction per period, so a period that
 * cannot be recorded rolls back alone and the others are still recorded. A period whose chain or object copy could not
 * be read is recorded as a FAILED run with cause UNREADABLE (INV-Q-12988 A); the body then fails the job run at the
 * end, after every period is recorded, so the run never reports a period it could not read as verified.
 */
class ChainVerificationJobBody(
    private val service: String,
    private val scope: TenantScope,
    private val recorder: ChainVerificationRecorder,
    private val newRunId: () -> String = { UUID.randomUUID().toString() },
) : JobBody {
    @Suppress("TooGenericExceptionCaught")
    override fun run(context: JobRunContext) {
        val tenant = context.target.tenant
        val entity = context.target.entity.value
        val actor = WorkloadPrincipal(service).job(context.job.name)
        val runId = newRunId()

        fun <T> inTenant(block: () -> T): T = scope.bind(tenant, actor) { scope.transactions.inTransaction(block) }

        var failure: Exception? = null
        for (period in inTenant { recorder.periods(tenant, entity) }) {
            try {
                val cause = inTenant { recorder.verifyPeriod(tenant, entity, period, runId) }
                if (cause == RunCause.UNREADABLE) {
                    failure = failure ?: IllegalStateException("evidence chain of $entity $period is unreadable")
                }
            } catch (e: Exception) {
                failure = failure ?: e
            }
        }
        failure?.let { throw it }
    }
}
