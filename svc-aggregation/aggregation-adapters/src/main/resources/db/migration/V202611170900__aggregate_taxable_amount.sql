-- The taxable amount of a PCP transaction and of an aggregate line (INV-T-23684, INV-REQ-437): the tax-exclusive
-- amount on which the claimable input tax is claimed (EN 16931 BT-116). NULL = not stated. Expand-only: two nullable
-- columns, no default, no index, no backfill; the existing table grants cover them.
alter table aggregation.aggregate_transaction add column taxable_amount text;
alter table aggregation.aggregate_line add column taxable_amount text;
