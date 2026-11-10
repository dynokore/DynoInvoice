-- V202611110900__outbox_testmode.sql (INV-T-19600; INV-Q-12635, INV-REQ-829)
-- Generated: outbox-and-queue OutboxSchema.testModeUpgradeDdl("ingestion") of the BOM 0.193.0 train: the producer half
-- of testmode needs the column on an outbox created before it existed.
alter table ingestion.outbox add column if not exists testmode boolean not null default false;
