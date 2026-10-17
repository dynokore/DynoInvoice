package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantRlsMigration

/**
 * DDL for the scheduler state a service keeps in its own schema (INV-REQ-2405 b2 and b4, INV-Q-2948),
 * embedded by the service's own migration (TPL-12), run as `<svc>_migrate`.
 *
 * - [SHEDLOCK]: ShedLock 6 on PostgreSQL (STK-25). Lock names and lease times only.
 *   a tenant id in `shedlock.name` names the entity a job lock covers: scheduling metadata, not tenant data (owner answer INV-Q-4817 A)
 *   so no `tenant_id` and no RLS (as `consumer_offset`, INV-Q-2954); used by the runtime login.
 * - [JOB_RUN]: one row per run, keyed by (tenant, service, job, entity or platform, tick, trigger, attempt)
 *   (INV-Q-2948, INV-Q-3039, INV-Q-3034). An ENTITY run carries the entity's tenant; a PLATFORM or whole-job
 *   run the reserved platform tenant and entity (INV-Q-2927 option D).
 * - [JOB_PAUSE]: one row per pause, at most one open row per (tenant, service, job, entity or platform);
 *   a job-wide pause carries the platform tenant (INV-Q-2985). Who paused is in the audit trail, not here.
 * - [JOB_RETENTION]: the one-row, platform-scoped job-retention window (no tenant data), written by the migration
 *   runner's Flyway afterMigrate callback from the service's configured retention at every migration.
 *
 * One runtime login per service (INV-REQ-1189 b2, INV-ADR-3968 items 5 and 6; coordinator ruling INV-Q-4346 (A)):
 * both state tables have `tenant_id NOT NULL` and tenancy-kernel's forced tenant policy (CS-19), and the runtime login
 * works on them under that policy only. The job-table maintenance that reaches across tenants is held by the NOLOGIN
 * `<svc>_sys` role and reached only through the objects it owns: [IN_PROGRESS_RUNS], the SECURITY DEFINER
 * function that returns the IN_PROGRESS runs the abandoned-run sweeper reads, and [PURGE_EXPIRED], a SECURITY DEFINER purge that takes no
 * cut-off and reads its window from [JOB_RETENTION]; [RETENTION_WINDOW] lets the job runtime compare its configured
 * retention with the row at start-up. Every function has static SQL, `SET search_path = pg_catalog, <schema>,
 * pg_temp`, EXECUTE revoked from PUBLIC and granted to the runtime login only.
 *
 * The migration role must be a member of `<svc>_sys` (it hands the view and functions to it); `<svc>_sys` holds
 * `CREATE` on the schema only for the moment of that hand-over.
 */
object JobSchema {
    const val SHEDLOCK = "shedlock"
    const val JOB_RUN = "job_run"
    const val JOB_PAUSE = "job_pause"

    /** The one-row job-retention table (comment `platform-scoped: no tenant data`). */
    const val JOB_RETENTION = "job_retention"

    /**
     * `job_run_in_progress(batch_size)`: sys-owned SECURITY DEFINER function (coordinator ruling INV-Q-5115) that
     * returns one jsonb array of the sweeper's seven columns of the IN_PROGRESS `job_run` rows across tenants, oldest
     * first, at most `batch_size` of them; the runtime login reads `job_run` only under its tenant policy.
     */
    const val IN_PROGRESS_RUNS = "job_run_in_progress"

    /** The upper bound [IN_PROGRESS_RUNS] accepts for one batch. */
    const val MAX_SWEEP_BATCH = 500

    /** `job_state_purge_expired(batch_size)`: deletes expired `job_run` and `job_pause` rows; no cut-off argument. */
    const val PURGE_EXPIRED = "job_state_purge_expired"

    /** `job_retention_window()`: the window of [JOB_RETENTION], or null when the row is missing. */
    const val RETENTION_WINDOW = "job_retention_window"

    /** The upper bound [PURGE_EXPIRED] accepts for one batch. */
    const val MAX_PURGE_BATCH = 10_000

    /**
     * The tables of [schema] this module declares `PLATFORM_SCOPED` (INV-ADR-3968 item 4a, INV-T-4134): [SHEDLOCK] (the
     * job scheduler's lock table) and [JOB_RETENTION], each with the table comment `platform-scoped: no tenant data`.
     */
    fun platformScopedTables(schema: String): List<String> =
        listOf(SHEDLOCK, JOB_RETENTION).map { "${SqlIdentifier.require(schema, "schema")}.$it" }

    /** The statements for [schema], the grants of [runtimeRole] and the objects of [sysRole]. */
    fun ddl(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        require(runtime != sys) { "the runtime login and the sys role must differ, both were '$runtime'" }
        // Statement groups joined outside SQL text, so no generated SQL is spliced into a literal (INV-Q-4036).
        return listOf(
            tables(schema),
            TenantRlsMigration.ddl(
                SqlIdentifier.require(schema, "schema"),
                JOB_RUN,
                policyName = "${JOB_RUN}_tenant_isolation",
            ),
            TenantRlsMigration.ddl(
                SqlIdentifier.require(schema, "schema"),
                JOB_PAUSE,
                policyName = "${JOB_PAUSE}_tenant_isolation",
            ),
            runtimeGrants(schema, runtimeRole),
            sysAccess(schema, sysRole),
            inProgressRunsFunction(schema, runtimeRole, sysRole),
            purgeFunction(schema, runtimeRole, sysRole),
            retentionWindowFunction(schema, runtimeRole, sysRole),
        ).joinToString("\n")
    }

    private fun tables(schema: String): String {
        val s = SqlIdentifier.require(schema, "schema")
        return """
            create table $s.$SHEDLOCK (
                name varchar(255) not null primary key,
                lock_until timestamp not null,
                locked_at timestamp not null,
                locked_by varchar(255) not null
            );
            comment on table $s.$SHEDLOCK is 'platform-scoped: no tenant data';
            create table $s.$JOB_RUN (
                tenant_id uuid not null,
                service text not null,
                job text not null,
                entity_ref text not null,
                tick timestamptz not null,
                trigger text not null check (trigger in ('SCHEDULED', 'MANUAL')),
                attempt integer not null check (attempt >= 1),
                status text not null check (status in ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
                reason text,
                started_at timestamptz not null,
                finished_at timestamptz,
                duration_millis bigint check (duration_millis >= 0),
                primary key (tenant_id, service, job, entity_ref, tick, trigger, attempt)
            );
            create table $s.$JOB_PAUSE (
                tenant_id uuid not null,
                service text not null,
                job text not null,
                entity_ref text not null,
                paused_at timestamptz not null,
                paused_until timestamptz not null,
                ended_at timestamptz,
                check (paused_until > paused_at)
            );
            create unique index ${JOB_PAUSE}_open on $s.$JOB_PAUSE (tenant_id, service, job, entity_ref)
                where ended_at is null;
            create table $s.$JOB_RETENTION (
                id boolean primary key default true check (id),
                job_window interval not null check (job_window > interval '0'),
                updated_at timestamptz not null default now()
            );
            comment on table $s.$JOB_RETENTION is 'platform-scoped: no tenant data';
            revoke all on $s.$JOB_RETENTION from public;
            """.trimIndent()
    }

    /** The runtime login: ShedLock's table, and the state tables under their tenant policy only; nothing on retention. */
    private fun runtimeGrants(
        schema: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        return """
            grant usage on schema $s to $runtime;
            grant select, insert, update on $s.$SHEDLOCK to $runtime;
            grant select, insert, update on $s.$JOB_RUN to $runtime;
            grant select, insert, update on $s.$JOB_PAUSE to $runtime;
            """.trimIndent()
    }

    /**
     * The cross-tenant access, held by the sys role only and reached by the runtime login only through the view and
     * functions the sys role owns: `SELECT`/`DELETE` policies `TO <sys>` with column grants limited to what they read,
     * and a restrictive policy that keeps an IN_PROGRESS run out of every delete.
     */
    private fun sysAccess(
        schema: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant usage on schema $s to $sys;
            create policy ${JOB_RUN}_sys_select on $s.$JOB_RUN for select to $sys using (true);
            create policy ${JOB_RUN}_sys_purge on $s.$JOB_RUN for delete to $sys using (status <> 'IN_PROGRESS');
            create policy ${JOB_RUN}_sys_purge_finished on $s.$JOB_RUN as restrictive for delete to $sys
                using (status <> 'IN_PROGRESS');
            grant select (tenant_id, service, job, entity_ref, tick, trigger, attempt, status, started_at)
                on $s.$JOB_RUN to $sys;
            grant delete on $s.$JOB_RUN to $sys;
            create policy ${JOB_PAUSE}_sys_select on $s.$JOB_PAUSE for select to $sys using (true);
            create policy ${JOB_PAUSE}_sys_purge on $s.$JOB_PAUSE for delete to $sys using (true);
            grant select (tenant_id, service, job, entity_ref, paused_at, paused_until, ended_at) on $s.$JOB_PAUSE to $sys;
            grant delete on $s.$JOB_PAUSE to $sys;
            grant select on $s.$JOB_RETENTION to $sys;
            """.trimIndent()
    }

    /**
     * The sweeper's function (coordinator ruling INV-Q-5115), handed to the sys role, `EXECUTE` for the runtime login
     * only. It refuses a batch outside 1..[MAX_SWEEP_BATCH] (errcode 22023) and returns one jsonb array of objects with
     * exactly the columns tenant_id, job, entity_ref, tick, trigger, attempt and started_at, ordered by started_at, of
     * the IN_PROGRESS `job_run` rows across tenants, and nothing else.
     */
    private fun inProgressRunsFunction(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$IN_PROGRESS_RUNS(batch_size integer) returns jsonb
            language plpgsql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
            begin
                if batch_size is null or batch_size < 1 or batch_size > $MAX_SWEEP_BATCH then
                    raise exception 'batch size % is outside 1..$MAX_SWEEP_BATCH', batch_size using errcode = '22023';
                end if;
                return coalesce((
                    select jsonb_agg(jsonb_build_object(
                        'tenant_id', r.tenant_id, 'job', r.job, 'entity_ref', r.entity_ref, 'tick', r.tick,
                        'trigger', r.trigger, 'attempt', r.attempt, 'started_at', r.started_at) order by r.started_at)
                    from (select tenant_id, job, entity_ref, tick, trigger, attempt, started_at from $s.$JOB_RUN
                          where status = 'IN_PROGRESS' order by started_at limit batch_size) r
                ), '[]'::jsonb);
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$IN_PROGRESS_RUNS(integer) owner to $sys;
            revoke all on function $s.$IN_PROGRESS_RUNS(integer) from public;
            grant execute on function $s.$IN_PROGRESS_RUNS(integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /**
     * The purge: refuses a batch outside 1..[MAX_PURGE_BATCH]; reads the window from [JOB_RETENTION] and, with no row,
     * deletes nothing, raises a warning and returns null; otherwise deletes up to the batch of `job_run` rows not
     * IN_PROGRESS that started before `now() - window`, then, within what is left of the batch, `job_pause` rows that
     * ended or ran out before it; returns the number deleted.
     */
    private fun purgeFunction(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$PURGE_EXPIRED(batch_size integer) returns bigint
            language plpgsql security definer set search_path = pg_catalog, $s, pg_temp as $$
            declare
                v_window interval;
                v_cutoff timestamptz;
                runs bigint;
                pauses bigint;
            begin
                if batch_size is null or batch_size < 1 or batch_size > $MAX_PURGE_BATCH then
                    raise exception 'batch size % is outside 1..$MAX_PURGE_BATCH', batch_size using errcode = '22023';
                end if;
                select r.job_window into v_window from $s.$JOB_RETENTION r where r.id;
                if not found or v_window is null or v_window <= interval '0' then
                    raise warning 'job retention row is missing: nothing is purged (INV-Q-4346)';
                    return null;
                end if;
                v_cutoff := now() - v_window;
                delete from $s.$JOB_RUN where (tenant_id, service, job, entity_ref, tick, trigger, attempt) in
                    (select tenant_id, service, job, entity_ref, tick, trigger, attempt from $s.$JOB_RUN
                     where status <> 'IN_PROGRESS' and started_at < v_cutoff limit batch_size);
                get diagnostics runs = row_count;
                pauses := 0;
                if runs < batch_size then
                    delete from $s.$JOB_PAUSE where coalesce(ended_at, paused_until) < v_cutoff
                        and (tenant_id, service, job, entity_ref, paused_at) in
                        (select tenant_id, service, job, entity_ref, paused_at from $s.$JOB_PAUSE
                         where coalesce(ended_at, paused_until) < v_cutoff limit batch_size - runs);
                    get diagnostics pauses = row_count;
                end if;
                return runs + pauses;
            end
            $$;
            grant create on schema $s to $sys;
            alter function $s.$PURGE_EXPIRED(integer) owner to $sys;
            revoke all on function $s.$PURGE_EXPIRED(integer) from public;
            grant execute on function $s.$PURGE_EXPIRED(integer) to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }

    /** The window for the job runtime's start-up check; the table itself is not granted to the runtime login. */
    private fun retentionWindowFunction(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create function $s.$RETENTION_WINDOW() returns interval
            language sql stable security definer set search_path = pg_catalog, $s, pg_temp as $$
                select r.job_window from $s.$JOB_RETENTION r where r.id
            $$;
            grant create on schema $s to $sys;
            alter function $s.$RETENTION_WINDOW() owner to $sys;
            revoke all on function $s.$RETENTION_WINDOW() from public;
            grant execute on function $s.$RETENTION_WINDOW() to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
