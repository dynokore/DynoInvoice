package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the scheduler state a service keeps in its own schema (INV-REQ-2405 b2 and b4, INV-Q-2948),
 * embedded by the service's own migration (TPL-12). Additive only: three new tables, nothing existing
 * altered (T30 slot answer expand_only_migration).
 *
 * - `shedlock`: ShedLock 6 on PostgreSQL (STK-25). Lock names and lease times only, no tenant data, so no
 *   `tenant_id` and no RLS (as `consumer_offset`, INV-Q-2954); granted to the worker role only.
 * - `job_run`: one row per run, keyed by (tenant, service, job, entity or platform, tick, trigger, attempt)
 *   (INV-Q-2948, INV-Q-3039, INV-Q-3034). An ENTITY run carries the entity's tenant; a PLATFORM or whole-job
 *   run the reserved platform tenant and entity (INV-Q-2927 option D).
 * - `job_pause`: one row per pause, at most one open row per (tenant, service, job, entity or platform);
 *   a job-wide pause carries the platform tenant (INV-Q-2985). Who paused is in the audit trail, not here.
 *
 * Both state tables have `tenant_id NOT NULL` and tenancy-kernel's forced tenant policy (CS-19). The
 * cross-tenant sweeper and purge read and delete through [workerRole] with policies `TO <worker>` scoped to
 * the columns they need, never BYPASSRLS (ARC 4.3 step 1a); every other write runs in the bound tenant's
 * transaction as [appRole].
 */
object JobSchema {
    const val SHEDLOCK = "shedlock"
    const val JOB_RUN = "job_run"
    const val JOB_PAUSE = "job_pause"
    private val IDENTIFIER = Regex("^[a-z_][a-z0-9_]*$")

    fun ddl(
        schema: String,
        appRole: String,
        workerRole: String,
    ): String {
        val s = identifier(schema)
        val app = identifier(appRole)
        val worker = identifier(workerRole)
        val runRls = TenantRlsMigration.ddl(s, JOB_RUN, policyName = "${JOB_RUN}_tenant_isolation")
        val pauseRls = TenantRlsMigration.ddl(s, JOB_PAUSE, policyName = "${JOB_PAUSE}_tenant_isolation")
        return """
            |create table $s.$SHEDLOCK (
            |    name varchar(255) not null primary key,
            |    lock_until timestamp not null,
            |    locked_at timestamp not null,
            |    locked_by varchar(255) not null
            |);
            |grant usage on schema $s to $worker;
            |grant select, insert, update on $s.$SHEDLOCK to $worker;
            |create table $s.$JOB_RUN (
            |    tenant_id uuid not null,
            |    service text not null,
            |    job text not null,
            |    entity_ref text not null,
            |    tick timestamptz not null,
            |    trigger text not null check (trigger in ('SCHEDULED', 'MANUAL')),
            |    attempt integer not null check (attempt >= 1),
            |    status text not null check (status in ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
            |    reason text,
            |    started_at timestamptz not null,
            |    finished_at timestamptz,
            |    duration_millis bigint check (duration_millis >= 0),
            |    primary key (tenant_id, service, job, entity_ref, tick, trigger, attempt)
            |);
            |$runRls
            |create policy ${JOB_RUN}_worker_select on $s.$JOB_RUN for select to $worker using (true);
            |create policy ${JOB_RUN}_worker_delete on $s.$JOB_RUN for delete to $worker using (true);
            |grant select (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
            |    on $s.$JOB_RUN to $worker;
            |grant delete on $s.$JOB_RUN to $worker;
            |grant select, insert, update on $s.$JOB_RUN to $app;
            |create table $s.$JOB_PAUSE (
            |    tenant_id uuid not null,
            |    service text not null,
            |    job text not null,
            |    entity_ref text not null,
            |    paused_at timestamptz not null,
            |    paused_until timestamptz not null,
            |    ended_at timestamptz,
            |    check (paused_until > paused_at)
            |);
            |create unique index ${JOB_PAUSE}_open on $s.$JOB_PAUSE (tenant_id, service, job, entity_ref)
            |    where ended_at is null;
            |$pauseRls
            |create policy ${JOB_PAUSE}_worker_select on $s.$JOB_PAUSE for select to $worker using (true);
            |create policy ${JOB_PAUSE}_worker_delete on $s.$JOB_PAUSE for delete to $worker using (true);
            |grant select (service, paused_until, ended_at) on $s.$JOB_PAUSE to $worker;
            |grant delete on $s.$JOB_PAUSE to $worker;
            |grant select, insert, update on $s.$JOB_PAUSE to $app;
        """.trimMargin()
    }

    internal fun identifier(text: String): String {
        require(IDENTIFIER.matches(text)) { "not a plain lower-case SQL identifier" }
        return text
    }
}
