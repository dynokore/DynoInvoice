-- V202611151100__outbox_testmode.sql (INV-T-20654 pin adaptation; INV-Q-12635, INV-REQ-829)
-- Same statement as svc-control-plane V202611081010 and svc-access V202611021300: the producer half of testmode
-- (OutboxWriter of outbox-and-queue 6.22.0) inserts the column, so an outbox created before it existed needs it.
alter table canonical.outbox add column if not exists testmode boolean not null default false;
