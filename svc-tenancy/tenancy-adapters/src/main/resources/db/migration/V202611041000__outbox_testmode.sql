-- V202611041000: adds the testmode column the pinned outbox-and-queue (6.10.0 and later) writes to an outbox created before it
-- existed (INV-Q-16781 A, INV-T-12640). The statement is OutboxSchema.testModeUpgradeDdl("tenancy"); the create migration
-- V202610190930 stays unchanged.
SET lock_timeout = '5s';

alter table tenancy.outbox add column if not exists testmode boolean not null default false;
