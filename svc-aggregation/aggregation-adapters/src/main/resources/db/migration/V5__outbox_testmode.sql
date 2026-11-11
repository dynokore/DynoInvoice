-- V5__outbox_testmode.sql (INV-T-14462, forced by the platform-libs-bom 0.171.0 pin; G-PIN): outbox-and-queue 6.17.0 writes the
-- `testmode` column (INV-Q-12635, INV-REQ-829). This is OutboxSchema.testModeUpgradeDdl("aggregation") of outbox-and-queue 6.17.0.
-- Run only by the migration Job as aggregation_migrate.
alter table aggregation.outbox add column if not exists testmode boolean not null default false;
