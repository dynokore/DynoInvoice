-- V202611141000__outbox_testmode.sql (INV-T-22427; INV-Q-12635, INV-REQ-829, INV-Q-16901 pin-forced)
-- Generated: outbox-and-queue OutboxSchema.testModeUpgradeDdl("notification") of the BOM 0.214.0 train: the producer
-- half of testmode needs the column on an outbox created before it existed.
alter table notification.outbox add column if not exists testmode boolean not null default false;
