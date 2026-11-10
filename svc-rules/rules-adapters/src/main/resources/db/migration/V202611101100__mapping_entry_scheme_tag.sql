-- INV-T-16850 (INV-REQ-2444): the scheme tag of a draft mapping entry has the pattern of the `tag` of the scenario
-- pack schema (scenario-pack-handlers.schema.json), `^[A-Z][A-Z0-9_]{1,39}$`: 2 to 40 characters, upper case first.
-- A NULL tag stays valid (an entry without a tag); the column itself exists since V202611091020.
alter table rules.pack_entry_source add constraint pack_entry_source_scheme_tag_pattern
    check (scheme_tag is null or scheme_tag ~ '^[A-Z][A-Z0-9_]{1,39}$');
