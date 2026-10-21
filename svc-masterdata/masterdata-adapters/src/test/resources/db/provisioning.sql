-- Roles that precede the migrations (INV-T-4942, INV-Q-4457: an init script may only create roles and grant to them).
-- In production the installer creates the logins; masterdata_runtime and masterdata_migrate are the two logins, masterdata_sys the
-- NOLOGIN owner of the capability functions (STK-16, TPL-12).
CREATE ROLE masterdata_sys NOLOGIN;
CREATE ROLE masterdata_migrate NOLOGIN;
CREATE ROLE masterdata_runtime NOLOGIN;
