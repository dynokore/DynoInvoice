SET lock_timeout = '5s';

-- INV-T-7319, INV-REQ-571, INV-REQ-548: at most one version of a Doc UUID is in flight (INV-AC-610). The four states are
-- the ones of InFlight.NAMES; a test compares the predicate of this index with that set by equality.
create unique index submission_in_flight_uq on submission.submission (doc_uuid)
    where state in ('QUEUED', 'SENT', 'SENT_UNCONFIRMED', 'RETRY');
