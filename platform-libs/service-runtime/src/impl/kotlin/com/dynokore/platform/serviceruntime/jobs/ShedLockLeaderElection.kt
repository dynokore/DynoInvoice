package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.TenantScope
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.jooq.DSLContext
import java.time.Clock

/**
 * Leader election with ShedLock 6 on PostgreSQL (STK-25, INV-REQ-2405 b1): at most one instance holds a
 * job's lock at a time. The lock name is the job's, plus the tenant and entity for an ENTITY run
 * (INV-Q-2948). Leases are the job's lockAtMostFor/lockAtLeastFor (PT10M/PT30S by default, TPL-09); an
 * instance that dies holding the lock releases nothing, the lease lapses and another instance takes the
 * next firing (INV-AC-2406). Lease times come from the database clock so replicas agree.
 */
internal class ShedLockLeaderElection(
    private val provider: LockProvider,
    private val clock: Clock,
) {
    /** Runs [body] if this instance takes the lock; null when another instance holds it. */
    fun <T> runIfLeader(
        job: JobDefinition,
        target: JobTarget,
        body: () -> T,
    ): T? {
        val name = lockName(job, target)
        val configuration = LockConfiguration(clock.instant(), name, job.lockAtMostFor, job.lockAtLeastFor)
        val lock = provider.lock(configuration).orElse(null) ?: return null
        try {
            return body()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /**
         * The lock name of [job] on [target]:
         * a tenant id in `shedlock.name` names the entity a job lock covers: scheduling metadata, not tenant data
         * (owner answer INV-Q-4817 A)
         */
        fun lockName(
            job: JobDefinition,
            target: JobTarget,
        ): String =
            when (target) {
                JobTarget.Whole -> "${job.ownerService}.${job.name}"
                is JobTarget.Entity -> "${job.ownerService}.${job.name}/${target.tenant}/${target.entity}"
            }

        /**
         * ShedLock over the service's own `shedlock` table (PLATFORM_SCOPED;
         * a tenant id in `shedlock.name` names the entity a job lock covers: scheduling metadata, not tenant data
         * (owner answer INV-Q-4817 A))
         * on tenancy-jooq's DSLContext, each operation in one platform binding and one transaction of [scope] (owner
         * answer to
         * INV-Q-5210, option A): ShedLock core's [StorageBasedLockProvider] over [ShedLockJooqAccessor].
         */
        fun postgres(
            dsl: DSLContext,
            scope: TenantScope,
            schema: String,
            clock: Clock,
        ) = ShedLockLeaderElection(ShedLockJooqLockProvider(ShedLockJooqAccessor(scope, dsl, schema)), clock)
    }
}
