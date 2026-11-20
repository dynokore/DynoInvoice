SET lock_timeout = '5s';

-- INV-T-25354, INV-REQ-558 behavior 6: a credit note netted into a derived version of its original is NOT_SUBMITTABLE
-- with the reason NETTED_BY_SYSTEM. The check constraint of V202611191300 (the default name of a column check) is
-- dropped and added again with the four reasons, no other value.
alter table submission.obligation_register_entry
    drop constraint obligation_register_entry_not_submittable_reason_check,
    add constraint obligation_register_entry_not_submittable_reason_check
        check (not_submittable_reason in ('BUYER_REJECTED', 'ISSUANCE_REFUSED', 'NETTED_INTO_REVISION', 'NETTED_BY_SYSTEM'));

-- The per-credit-note mark `netRefused`: canonical-svc refused a net derivation that named the credit note, so it is
-- never netted again and the correction path submits it as posted. Written by the runtime role with the existing
-- table grant; no new privilege.
alter table submission.obligation_register_entry
    add column net_refused boolean not null default false;

comment on column submission.obligation_register_entry.net_refused is 'class: operational';
