SET lock_timeout = '5s';

-- V202610311000__inbound_event_document_function_six.sql (INV-T-13672; INV-REQ-371; owner answer INV-Q-6507, option A).
-- The caller's documentFunction has six values; the check of canonical.inbound_event.document_function allowed four.
-- The inline check of V202610291000 is named inbound_event_document_function_check by PostgreSQL; the new check keeps
-- that name. A migration that has run is never edited, so the constraint is replaced here.
alter table canonical.inbound_event
    drop constraint inbound_event_document_function_check,
    add constraint inbound_event_document_function_check check (document_function in
        ('INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE', 'BILL_FOR_PAYMENT', 'ADJUSTMENT', 'NOT_A_BILL'));
