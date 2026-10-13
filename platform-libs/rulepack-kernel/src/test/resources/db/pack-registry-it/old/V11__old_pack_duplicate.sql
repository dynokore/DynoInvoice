-- The old constraint admits the same platform-wide (NULL-tenant) version twice.
INSERT INTO rulepack_test.old_pack (id, pack_id, pack_type, version, tenant_id, effective_from, min_engine_version,
                                   manifest, checksums, signature, contents, trusted_anchor)
VALUES (gen_random_uuid(), 'p', 'Scope', '1.0.0', NULL, now(), '1', '{}', '{}', '\x01', '{}', 'a');
INSERT INTO rulepack_test.old_pack (id, pack_id, pack_type, version, tenant_id, effective_from, min_engine_version,
                                   manifest, checksums, signature, contents, trusted_anchor)
VALUES (gen_random_uuid(), 'p', 'Scope', '1.0.0', NULL, now(), '1', '{}', '{}', '\x01', '{}', 'a');
