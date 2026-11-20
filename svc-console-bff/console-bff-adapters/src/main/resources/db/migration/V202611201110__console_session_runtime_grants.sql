-- V202611201110__console_session_runtime_grants.sql (INV-T-25571; least privilege, NIST SP 800-53 AC-6).
-- The production provisioner already gives these privileges by default on every table of the migration role; this
-- file makes them explicit for console_session, the only console_bff table whose migrations grant nothing, so that
-- the test fixture needs no default table privilege.
grant select, insert, update, delete on console_bff.console_session to console_bff_runtime;
