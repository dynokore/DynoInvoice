-- V202611081000__confirmed_by_records_the_user.sql (INV-T-8118; INV-Q-10647: the audit record holds the user ID, never
-- a role, because several users may hold the same role). A confirmation now stores only confirmed_by. confirmed_role
-- stays as a nullable column: rows stored before this migration keep their value, and new rows leave it NULL.

ALTER TABLE masterdata.identity_triple ALTER COLUMN confirmed_role DROP NOT NULL;

-- The generated name of the table CHECK of V202610210900, read from pg_constraint on a migrated database.
ALTER TABLE masterdata.identity_triple_change DROP CONSTRAINT identity_triple_change_check;
ALTER TABLE masterdata.identity_triple_change
    ADD CONSTRAINT identity_triple_change_confirmed_state_check
    CHECK ((state = 'CONFIRMED') = (confirmed_by IS NOT NULL));
