-- V202611061030__outbox_testmode.sql (INV-T-4946; INV-Q-12635, INV-REQ-829)
-- Generated: outbox-and-queue OutboxSchema.testModeUpgradeDdl("masterdata") of the BOM 0.164.0 train: the producer half
-- of testmode needs the column on an outbox created before it existed. GeneratedMigrationsTest compares this file.
alter table masterdata.outbox add column if not exists testmode boolean not null default false;
