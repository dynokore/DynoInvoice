-- INV-T-14458: the console session table (OWASP ASVS V3.2, V3.3). The schema `console` is created by provisioning,
-- owned by console_migrate (INV-Q-17448 schema ruling); this file creates the table and nothing else.
-- Only SHA-256 hashes are stored: never the cookie value, the CSRF token or the end-user token.
-- No tenant row-level-security policy: the lookup key is the hash and the tenant is read from the row.
create table console.console_session (
    session_id_hash   char(64)    primary key,
    tenant_id         uuid        not null,
    principal_id      text        not null,
    opened_at         timestamptz not null,
    last_seen_at      timestamptz not null,
    authority_version bigint      not null,
    csrf_token_hash   char(64)    not null,
    check (session_id_hash ~ '^[0-9a-f]{64}$'),
    check (csrf_token_hash ~ '^[0-9a-f]{64}$'),
    check (last_seen_at >= opened_at)
);

create index console_session_last_seen_idx on console.console_session (last_seen_at);
