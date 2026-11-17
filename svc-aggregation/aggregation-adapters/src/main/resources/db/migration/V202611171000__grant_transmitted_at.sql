-- V202611171000 (INV-T-22476; INV-REQ-439): the SubmissionDespatched consumer writes transmitted_at, once, through the
-- runtime login. The grant is on that one column, never on a content column.
grant update (transmitted_at) on aggregation.aggregate to aggregation_runtime;
