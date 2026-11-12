SET lock_timeout = '5s';

-- INV-T-20657, INV-REQ-940 (coordinator ruling, owner 2026-10-14, option A): the failure class of an attempt is the
-- catalogue code its outcome classifier assigned, and the classifier has two fallback codes (X-998, X-999) that are not
-- catalogue codes. The check of V202611131300 is widened by those two exact values; no column is added, no row is
-- rewritten and the comment is unchanged.
alter table submission.submission_attempt
    drop constraint submission_attempt_failure_class_check,
    add constraint submission_attempt_failure_class_check
        check (failure_class ~ '^[A-Z]{3}-[0-9]{3}$' or failure_class in ('X-998', 'X-999'));
