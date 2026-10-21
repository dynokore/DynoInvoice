-- V4__pack_artefact_grants.sql (TPL-12; INV-T-7525). The runtime login reads and inserts holder copies and nothing
-- else: rows are immutable (INV-ADR-263), so no UPDATE, DELETE or TRUNCATE and no grant option. The kernel DDL of V3
-- grants EXECUTE on its platform write function and INSERT to the sys role, never a table right to the runtime login.
GRANT SELECT, INSERT ON rules.pack_artefact TO rules_runtime;
