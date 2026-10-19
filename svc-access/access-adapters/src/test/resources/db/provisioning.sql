-- Roles that precede the migrations (INV-T-4934, INV-Q-4457: an init script may only create roles and grant to them).
-- In production the installer creates the logins; access_runtime and access_migrate are the two logins, access_sys the
-- NOLOGIN owner of the capability functions (STK-16, TPL-12).
CREATE ROLE access_sys NOLOGIN;
CREATE ROLE access_migrate NOLOGIN;
CREATE ROLE access_runtime NOLOGIN;
