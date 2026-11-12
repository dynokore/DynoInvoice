SET lock_timeout = '5s';

-- INV-T-20797, INV-REQ-570: the initial filing due date carried by PeriodRecordsChanged is stored with version 0, so
-- every PeriodDueDateChanged event (versions start at 1) replaces it. A negative version stays refused.
alter table submission.period_due_date drop constraint period_due_date_due_date_version_check;
alter table submission.period_due_date add constraint period_due_date_due_date_version_check check (due_date_version >= 0);

comment on column submission.period_due_date.due_date_version is
    'Version 0 is the initial value carried by PeriodRecordsChanged; versions from 1 come from PeriodDueDateChanged.';
