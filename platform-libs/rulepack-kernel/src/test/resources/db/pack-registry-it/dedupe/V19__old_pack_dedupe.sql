-- The holder removes the duplicate platform-wide row before retrying the upgrade.
DELETE FROM rulepack_test.old_pack WHERE ctid = (SELECT max(ctid) FROM rulepack_test.old_pack);
