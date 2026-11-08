-- V202611091000__add_authority_version.sql (INV-T-9061; INV-Q-9047 A, INV-Q-8972 A)
-- The authority version each record was last changed at, stored with the row so that the replay of AuthorityChanged
-- re-emits it without drawing a new sequence value. Nullable: a PENDING grant and an ACTIVE principal have had no live
-- event. The live path sets it in the command transaction (decorators of the publishers in access-adapters).
SET lock_timeout = '5s';

ALTER TABLE access.role_grant
    ADD COLUMN authority_version bigint CHECK (authority_version IS NULL OR authority_version >= 1);
ALTER TABLE access.consent
    ADD COLUMN authority_version bigint CHECK (authority_version IS NULL OR authority_version >= 1);
ALTER TABLE access.service_principal
    ADD COLUMN authority_version bigint CHECK (authority_version IS NULL OR authority_version >= 1);

-- Forced row-level security applies to the migration role: the backfill runs under a policy for that role that exists
-- only inside this migration (created and dropped below); no table's forced row-level security is changed.
CREATE POLICY authority_version_backfill ON access.role_grant FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY authority_version_backfill ON access.consent FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);
CREATE POLICY authority_version_backfill ON access.service_principal FOR ALL TO CURRENT_USER USING (true) WITH CHECK (true);

-- One pass over the rows that had a live event, oldest change first: one sequence value per row.
DO $backfill$
DECLARE
    r record;
    v bigint;
BEGIN
    FOR r IN
        SELECT k, id
        FROM (
            SELECT 'G' AS k, id, updated_at FROM access.role_grant WHERE status IN ('ACTIVE', 'REVOKED')
            UNION ALL
            SELECT 'C', id, updated_at FROM access.consent
            UNION ALL
            SELECT 'P', id, updated_at FROM access.service_principal WHERE status = 'REVOKED'
        ) rows
        ORDER BY updated_at, id
    LOOP
        v := nextval('access.authority_version_seq');
        IF r.k = 'G' THEN
            UPDATE access.role_grant SET authority_version = v WHERE id = r.id;
        ELSIF r.k = 'C' THEN
            UPDATE access.consent SET authority_version = v WHERE id = r.id;
        ELSE
            UPDATE access.service_principal SET authority_version = v WHERE id = r.id;
        END IF;
    END LOOP;
END
$backfill$;

DROP POLICY authority_version_backfill ON access.role_grant;
DROP POLICY authority_version_backfill ON access.consent;
DROP POLICY authority_version_backfill ON access.service_principal;
