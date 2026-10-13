-- The runtime login's table grants: owning no table, NOSUPERUSER and NOBYPASSRLS, so every assertion of the test is a
-- real row-level-security decision (S2-05). UPDATE and DELETE are granted here only so the test proves the policy, not
-- the missing grant, refuses a change to a platform row; a real holder grants INSERT and SELECT only (rows are
-- immutable, INV-ADR-263). rulepack_kernel_other may read the schema but has no EXECUTE on the function.
GRANT USAGE ON SCHEMA rulepack_test TO rulepack_kernel_app, rulepack_kernel_other;
GRANT INSERT, SELECT, UPDATE, DELETE ON rulepack_test.rule_pack_version TO rulepack_kernel_app;
GRANT SELECT ON rulepack_test.rule_pack_version TO rulepack_kernel_other;
