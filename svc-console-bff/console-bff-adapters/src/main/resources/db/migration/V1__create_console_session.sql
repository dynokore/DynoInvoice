-- INV-T-14458: the console session table (OWASP ASVS V3.2, V3.3). The schema `console_bff` is created by provisioning,
-- owned by console_bff_migrate (INV-Q-17448 schema ruling); this file creates the table and nothing else.
-- Only SHA-256 hashes are stored: never the cookie value, the CSRF token or the end-user token.
-- Platform-scoped: the lookup key is the hash and the tenant is read from the row (owner answer of 2026-11-11, INV-T-14458).
-- The column is bound_tenant_id, not tenant_id: tenancy-jooq refuses a platform-scoped table that has a tenant_id column.
create table console_bff.console_session (
    session_id_hash   char(64)    primary key,
    bound_tenant_id   uuid        not null,
    principal_id      text        not null,
    opened_at         timestamptz not null,
    last_seen_at      timestamptz not null,
    authority_version bigint      not null,
    csrf_token_hash   char(64)    not null,
    check (session_id_hash ~ '^[0-9a-f]{64}$'),
    check (csrf_token_hash ~ '^[0-9a-f]{64}$'),
    check (last_seen_at >= opened_at)
);

create index console_session_last_seen_idx on console_bff.console_session (last_seen_at);

comment on table console_bff.console_session is 'platform-scoped: no tenant data';
