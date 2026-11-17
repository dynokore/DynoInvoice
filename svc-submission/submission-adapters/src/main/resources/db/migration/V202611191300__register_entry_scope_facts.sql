SET lock_timeout = '5s';

alter table submission.obligation_register_entry
    add column scope_verdict text check (scope_verdict in ('SUBMITTABLE', 'NOT_SUBMITTABLE', 'PRE_OBLIGATION')),
    add column not_submittable_reason text
        check (not_submittable_reason in ('BUYER_REJECTED', 'ISSUANCE_REFUSED', 'NETTED_INTO_REVISION')),
    add column preceding_doc_uuid text,
    add constraint obligation_register_entry_reason_needs_not_submittable
        check (not_submittable_reason is null or scope_verdict = 'NOT_SUBMITTABLE');

comment on column submission.obligation_register_entry.scope_verdict is 'class: operational';
comment on column submission.obligation_register_entry.not_submittable_reason is 'class: operational';
comment on column submission.obligation_register_entry.preceding_doc_uuid is 'class: operational';
