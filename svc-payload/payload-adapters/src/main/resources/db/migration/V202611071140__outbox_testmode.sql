alter table payload.outbox add column if not exists testmode boolean not null default false;
