-- V202611011100__outbox_testmode.sql (INV-T-9880). OutboxSchema.testModeUpgradeDdl("rules") of outbox-and-queue 6.10.0+
-- (INV-Q-12635, INV-REQ-829): the producing service's own migration adds the testmode column the outbox writer now sets.
alter table rules.outbox add column if not exists testmode boolean not null default false;
