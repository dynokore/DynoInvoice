SET lock_timeout = '5s';

-- INV-T-16323, INV-REQ-975 (INV-Q-16290 A): the named list facts of a document version, held on the register entry so the
-- list needs no cross-service query. The eight columns live and die with the entry row: no copy, no purge job, so the
-- retention is the entry's retention. Classification is the one of ListFactsPolicy.FIELDS.
alter table submission.obligation_register_entry
    add column document_number text,           -- NONE
    add column document_type_code text,        -- NONE
    add column direction text check (direction in ('SALE', 'PURCHASE')), -- NONE
    add column counterparty_name text,         -- IDENTIFYING
    add column counterparty_uen text,          -- IDENTIFYING
    add column taxable_amount numeric(19, 2),  -- NONE
    add column gst_amount numeric(19, 2),      -- NONE
    add column category_codes text[],          -- NONE
    add constraint list_facts_all_or_none check (
        (document_number is null and document_type_code is null and direction is null
            and counterparty_name is null and counterparty_uen is null and taxable_amount is null
            and gst_amount is null and category_codes is null)
        or
        (document_number is not null and document_type_code is not null and direction is not null
            and counterparty_name is not null and counterparty_uen is not null and taxable_amount is not null
            and gst_amount is not null and category_codes is not null));
