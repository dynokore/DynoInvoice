package com.dynokore.platform.serviceruntime.jobs

import java.time.Duration
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * How critical a job is (INV-REQ-1136, INV-Q-2942). A pause of a [DEADLINE_CRITICAL] job (scheduled
 * submission, readiness drains) is bounded by the entity's nearest due date minus its safety margin
 * (INV-REQ-1138 b3). Wire values as `JobRegistered.criticality`.
 */
enum class JobCriticality { STANDARD, DEADLINE_CRITICAL }

/**
 * [PLATFORM]: the job runs once for the installation. [ENTITY]: the job runs per entity, the entity given
 * per run (INV-Q-2942); its scheduler state, lock and pause are keyed by the entity (INV-REQ-2405 b4).
 */
enum class JobEntityScope { PLATFORM, ENTITY }

/**
 * The job's schedule as the owning service declares it (INV-REQ-1136): a six-field cron expression
 * (second, minute, hour, day of month, month, day of week; the `@Scheduled(cron = …)` form of TPL-09)
 * evaluated in [zone]. Each service keeps its own scheduler (INV-REQ-1136 b2); the expression makes the
 * schedule visible (`JobRegistered.schedule`) and defines the run window of a run-now (INV-Q-3034).
 * The expression is checked when the job registers.
 */
data class JobSchedule(val cron: String, val zone: ZoneId = ZoneOffset.UTC) {
    init {
        require(cron.isNotBlank()) { "schedule must not be blank" }
    }
}

/**
 * The uniform job contract (INV-REQ-1136, FR-AG.62): name, owner service, entity scope, schedule,
 * criticality and tolerance, how late a run may be before it counts as missed (INV-Q-2067, mandatory,
 * INV-Q-2942). [lockAtMostFor] and [lockAtLeastFor] are the ShedLock leases, PT10M/PT30S by default
 * (TPL-09); a job that can run longer overrides [lockAtMostFor] (INV-Q-2948). [lockAtMostFor] also bounds
 * a run: a run still in progress after it is recorded FAILED("abandoned") by the sweeper.
 */
data class JobDefinition(
    val name: String,
    val ownerService: String,
    val entityScope: JobEntityScope,
    val schedule: JobSchedule,
    val criticality: JobCriticality,
    val tolerance: Duration,
    val lockAtMostFor: Duration = DEFAULT_LOCK_AT_MOST_FOR,
    val lockAtLeastFor: Duration = DEFAULT_LOCK_AT_LEAST_FOR,
) {
    init {
        require(NAME.matches(name)) { "job name must be lower-case words like 'scheduled-despatch'" }
        require(SERVICE.matches(ownerService)) { "owner service must look like 'submission-svc'" }
        require(!tolerance.isNegative) { "tolerance must not be negative" }
        require(lockAtMostFor.isPositive) { "lockAtMostFor must be positive" }
        require(!lockAtLeastFor.isNegative && lockAtLeastFor <= lockAtMostFor) {
            "lockAtLeastFor must be within lockAtMostFor"
        }
    }

    companion object {
        val DEFAULT_LOCK_AT_MOST_FOR: Duration = Duration.ofMinutes(10)
        val DEFAULT_LOCK_AT_LEAST_FOR: Duration = Duration.ofSeconds(30)
        private val NAME = Regex("^[a-z][a-z0-9-]{0,62}$")
        private val SERVICE = Regex("^[a-z][a-z-]{0,62}$")
    }
}
