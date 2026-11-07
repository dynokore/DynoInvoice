-- outbox-and-queue 6.10.0 added the outbox column testmode; this is OutboxSchema.testModeUpgradeDdl("evidence").
alter table evidence.outbox add column if not exists testmode boolean not null default false;
