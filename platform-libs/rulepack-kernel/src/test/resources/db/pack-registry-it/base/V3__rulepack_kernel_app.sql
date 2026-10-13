-- The runtime login a pack holder's service uses: INSERT and SELECT only, NOSUPERUSER and NOBYPASSRLS, owning no
-- table, so every assertion of the test is a real row-level-security decision (S2-05).
CREATE ROLE rulepack_kernel_app LOGIN PASSWORD 'rulepack_kernel_app_pw' NOSUPERUSER NOBYPASSRLS;
GRANT USAGE ON SCHEMA rulepack_test TO rulepack_kernel_app;
GRANT INSERT, SELECT ON rulepack_test.rule_pack_version TO rulepack_kernel_app;
