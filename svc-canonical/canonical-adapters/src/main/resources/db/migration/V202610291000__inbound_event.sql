SET lock_timeout = '5s';

-- V202610291000__inbound_event.sql (INV-T-6700; INV-REQ-429, INV-REQ-430, INV-REQ-569). One row per recorded purchase
-- version (a network receipt or the buyer's AP posting): the correlation index of the previous task's keys. Append-only:
-- the runtime login holds INSERT and SELECT only. The three fallback columns are null together exactly when
-- InboundKeys.fallbackKey is null for the event. No foreign key to document: it is partitioned and the table is a
-- correlation index of its own.
create table canonical.inbound_event (
    tenant_id uuid not null,
    entity_id uuid not null,
    doc_uuid uuid not null,
    version int not null check (version >= 1),
    event_kind text not null check (event_kind in ('RECEIPT', 'POSTING')),
    document_function text not null
        check (document_function in ('INVOICE', 'CREDIT_NOTE', 'DEBIT_NOTE', 'BILL_FOR_PAYMENT')),
    supplier_key varchar(81),
    document_number varchar(100),
    document_year smallint,
    supplier_endpoint_id varchar(256),
    supplier_doc_uuid uuid,
    buyer_disposition text check (buyer_disposition in ('PENDING', 'ACCEPTED', 'REJECTED')),
    primary key (doc_uuid, version),
    constraint inbound_event_fallback_columns_together check (
        (supplier_key is null and document_number is null and document_year is null)
        or (supplier_key is not null and document_number is not null and document_year is not null)),
    constraint inbound_event_receipt_fields check (
        (event_kind = 'RECEIPT' and supplier_endpoint_id is not null and buyer_disposition is not null)
        or (event_kind = 'POSTING' and supplier_endpoint_id is null and buyer_disposition is null
            and supplier_doc_uuid is null))
);
create index inbound_event_fallback_key on canonical.inbound_event
    (tenant_id, entity_id, document_function, supplier_key, document_number, document_year);
create index inbound_event_uuid_key on canonical.inbound_event
    (tenant_id, entity_id, supplier_endpoint_id, supplier_doc_uuid);

ALTER TABLE canonical.inbound_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE canonical.inbound_event FORCE ROW LEVEL SECURITY;
CREATE POLICY inbound_event_tenant_isolation ON canonical.inbound_event
    USING      (tenant_id = (select canonical.bound_tenant()))
    WITH CHECK (tenant_id = (select canonical.bound_tenant()));
grant select, insert on canonical.inbound_event to canonical_runtime;
