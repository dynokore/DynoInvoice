-- The job-retention row (coordinator ruling INV-Q-5113): written at every migration of a service whose schema has the
-- job tables, from service-runtime.jobs.retention. The migration runner sets the placeholders jobSchema (validated by
-- SqlIdentifier.require) and jobRetentionSeconds (the digits of a positive Duration's seconds). A schema without
-- job_retention writes nothing. Run as the <svc>_migrate login, like the migrations.
do $$
begin
    if to_regclass('${jobSchema}.job_retention') is not null then
        insert into ${jobSchema}.job_retention (id, job_window, updated_at)
        values (true, ${jobRetentionSeconds}::bigint * interval '1 second', now())
        on conflict (id) do update set job_window = excluded.job_window, updated_at = excluded.updated_at;
    end if;
end
$$;
