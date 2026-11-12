SET lock_timeout = '5s';

-- INV-T-20480, INV-REQ-570, INV-AC-20479: the transaction date and the reason code of a register entry, for the
-- period readiness read. Expand step: both are nullable with no default; nothing is dropped or altered. The writer of
-- transaction_date is the CanonicalVersionCreated handler; the writer of reason_code is a later slice.
alter table submission.obligation_register_entry add column transaction_date date;
alter table submission.obligation_register_entry add column reason_code text;
comment on column submission.obligation_register_entry.transaction_date is 'class: operational';
comment on column submission.obligation_register_entry.reason_code is 'class: operational';
