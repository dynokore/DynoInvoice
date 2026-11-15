-- INV-T-4155: the operator session of a console session (owner ruling INV-Q-4119, "keeps the token server-side in
-- the session"; INV-REQ-862 b4; OWASP ASVS V3 and the Session Management Cheat Sheet: server-side session state).
-- The earlier text "never ... the end-user token" in V1__create_console_session.sql concerns the sign-in token of the
-- console user, which is still never stored. The column added here holds a different credential: the short-lived
-- (at most 15 minutes) operator token that access-svc issues for one tenant and one case, a bearer credential kept
-- only as long as the row.
-- No table and no schema is added: console_session stays platform-scoped (owner answer on INV-T-14458) and is reached
-- only by JooqSessionStore and SessionSweeper. The four columns are all null or all set.
alter table console_bff.console_session
    add column operator_token        text,
    add column operator_tenant_id    uuid,
    add column operator_expires_at   timestamptz,
    add column operator_content_mode text,
    add constraint console_session_operator_token_expiry_ck
        check ((operator_token is null) = (operator_expires_at is null)),
    add constraint console_session_operator_token_tenant_ck
        check ((operator_token is null) = (operator_tenant_id is null)),
    add constraint console_session_operator_token_mode_ck
        check ((operator_token is null) = (operator_content_mode is null)),
    add constraint console_session_operator_token_len_ck
        check (operator_token is null or length(operator_token) <= 8192);

comment on column console_bff.console_session.operator_token is
    'short-lived operator token of access-svc (at most 15 minutes), server-side only, never logged or returned';
