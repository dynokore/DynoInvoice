-- V202611081010__outbox_testmode.sql (INV-T-9238; INV-Q-12635, INV-REQ-829)
-- Generated: outbox-and-queue OutboxSchema.testModeUpgradeDdl("controlplane") of the BOM 0.175.0 train: the producer half
-- of testmode needs the column on an outbox created before it existed. GeneratedMigrationsTest compares this file.
alter table controlplane.outbox add column if not exists testmode boolean not null default false;
