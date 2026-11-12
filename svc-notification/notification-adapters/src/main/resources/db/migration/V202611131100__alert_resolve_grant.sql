-- V202611131100__alert_resolve_grant.sql (INV-T-20912, INV-REQ-1080; coordinator ruling INV-Q-20864 A, case 5).
-- The resolve of a pack staleness alert sets state and clears snoozed_until (the CHECK alert_snoozed_until_only_snoozed
-- forces the clear when the state leaves SNOOZED). A column grant is the least privilege that needs: no other column
-- and no delete. Creates and alters no object.
grant update (state, snoozed_until) on notification.alert to notification_runtime;
