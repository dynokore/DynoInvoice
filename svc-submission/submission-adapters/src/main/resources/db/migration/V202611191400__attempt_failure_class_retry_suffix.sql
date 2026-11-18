SET lock_timeout = '5s';

-- INV-T-24285, INV-REQ-940 (coordinator ruling, delegated HIGH, option A): the failure class of a rejected attempt is the
-- first rejection reason code of IRAS, which may carry a retry suffix (SUB-004-R1). The check of V202611151300 is
-- widened by that one optional suffix shape; everything it refused before, other than that shape, is still refused.
-- No column is added and no row is rewritten.
alter table submission.submission_attempt
    drop constraint submission_attempt_failure_class_check,
    add constraint submission_attempt_failure_class_check
        check (failure_class ~ '^[A-Z]{3}-[0-9]{3}(-R[0-9]+)?$' or failure_class in ('X-998', 'X-999'));
