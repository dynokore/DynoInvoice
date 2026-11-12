-- Test fixture only (INV-T-20564, INV-Q-16901 adaptation). The pinned service-runtime refuses to start when the
-- job-retention row is missing (JobRetention.check, INV-Q-4346). In production the migration runner's afterMigrate
-- callback writes the row from service-runtime.jobs.retention (P30D, application.yaml); the test database is migrated by
-- Flyway directly, so this callback writes the same value.
insert into submission.job_retention (id, job_window) values (true, interval '30 days')
on conflict (id) do update set job_window = excluded.job_window, updated_at = now();
