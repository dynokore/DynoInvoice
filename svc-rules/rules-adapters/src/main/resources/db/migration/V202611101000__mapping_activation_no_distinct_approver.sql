-- V202611101000__mapping_activation_no_distinct_approver.sql (INV-T-19527; INV-REQ-992, INV-REQ-995, INV-REQ-996).
-- The installation fact stated at the activation of a mapping pack is stored with the activation: true when no tax
-- approver distinct from the person who runs the server was recorded for the installation at that time. Expand only.
-- Null until the pack is activated; a pack never activated reads false in the answers.
alter table rules.pack_registry add column no_distinct_approver boolean null;
alter table rules.pack_registry add constraint pack_registry_no_distinct_approver_activated
    check (no_distinct_approver is null or status in ('ARMED', 'SUPERSEDED', 'ABORTED'));
