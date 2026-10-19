package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier

/** The grants, the sys role's access and the claim views of [OutboxSchema.ddl] (split out of that object). */
internal object OutboxAccessSql {
    const val DEAD_LETTER_COLUMNS =
        "id, consumer, topic, log_offset, tenant_id, reason, recorded_at, status, resolved_at, note, replayed_to"

    fun runtimeGrants(
        schema: String,
        runtimeRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        return """
            grant usage on schema $s to $runtime;
            grant select, insert, update (published_at) on $s.${OutboxSchema.OUTBOX} to $runtime;
            grant select, insert, update on $s.${OutboxSchema.OUTBOX_SEQUENCE}, $s.${OutboxSchema.CONSUMER_SEQUENCE}, $s.${OutboxSchema.CONSUMER_OFFSET} to $runtime;
            grant select, insert on $s.${OutboxSchema.INBOX} to $runtime;
            grant select on $s.${OutboxSchema.DEAD_LETTER} to $runtime;
            create policy ${OutboxSchema.DEAD_LETTER}_no_platform_tenant on $s.${OutboxSchema.DEAD_LETTER} as restrictive for select to $runtime
                using (tenant_id <> '${OutboxSchema.PLATFORM_TENANT}'::uuid);
            grant select, insert, update on $s.${OutboxSchema.CONSUMER_REPLAY}, $s.${OutboxSchema.CONSUMER_REPLAY_MARKER}, $s.${OutboxSchema.CONSUMER_SOURCE_SEEN}
                to $runtime;
            """.trimIndent()
    }

    /**
     * The cross-tenant access, all of it held by the sys role and reached by the runtime login only through the views
     * and functions the sys role owns (INV-Q-3953, INV-REQ-1205 b3): `SELECT` and `DELETE` policies `TO <sys>` with
     * column grants limited to what the views and functions read, and restrictive policies that keep an unpublished
     * outbox row and an open dead letter out of every delete.
     *
     * `INSERT` on [OutboxSchema.OUTBOX] (coordinator ruling INV-Q-4254 A) is for a sys-owned definer that writes a
     * platform event
     * with the change it records (config-resolver's `write_installation_setting`). No sys policy covers it: the
     * table's tenant policy alone admits the row, so the sys role inserts only under the bound tenant, the platform
     * tenant for a platform event.
     */
    fun sysAccess(
        schema: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            grant usage on schema $s to $sys;
            create policy ${OutboxSchema.OUTBOX}_sys_select on $s.${OutboxSchema.OUTBOX} for select to $sys using (true);
            create policy ${OutboxSchema.OUTBOX}_sys_purge on $s.${OutboxSchema.OUTBOX} for delete to $sys using (published_at is not null);
            create policy ${OutboxSchema.OUTBOX}_sys_purge_published on $s.${OutboxSchema.OUTBOX} as restrictive for delete to $sys
                using (published_at is not null);
            grant select (seq, tenant_id, published_at) on $s.${OutboxSchema.OUTBOX} to $sys;
            grant delete on $s.${OutboxSchema.OUTBOX} to $sys;
            grant insert on $s.${OutboxSchema.OUTBOX} to $sys;
            create policy ${OutboxSchema.INBOX}_sys_select on $s.${OutboxSchema.INBOX} for select to $sys using (true);
            create policy ${OutboxSchema.INBOX}_sys_purge on $s.${OutboxSchema.INBOX} for delete to $sys using (true);
            grant select (consumer, event_id, received_at) on $s.${OutboxSchema.INBOX} to $sys;
            grant delete on $s.${OutboxSchema.INBOX} to $sys;
            create policy ${OutboxSchema.CONSUMER_SEQUENCE}_sys_select on $s.${OutboxSchema.CONSUMER_SEQUENCE} for select to $sys using (true);
            create policy ${OutboxSchema.CONSUMER_SEQUENCE}_sys_full_sync on $s.${OutboxSchema.CONSUMER_SEQUENCE} for delete to $sys using (true);
            grant select (consumer, source, tenant_id) on $s.${OutboxSchema.CONSUMER_SEQUENCE} to $sys;
            grant delete on $s.${OutboxSchema.CONSUMER_SEQUENCE} to $sys;
            create policy ${OutboxSchema.DEAD_LETTER}_sys_select on $s.${OutboxSchema.DEAD_LETTER} for select to $sys using (true);
            create policy ${OutboxSchema.DEAD_LETTER}_sys_insert on $s.${OutboxSchema.DEAD_LETTER} for insert to $sys with check (true);
            create policy ${OutboxSchema.DEAD_LETTER}_sys_update on $s.${OutboxSchema.DEAD_LETTER} for update to $sys using (true) with check (true);
            create policy ${OutboxSchema.DEAD_LETTER}_sys_purge on $s.${OutboxSchema.DEAD_LETTER} for delete to $sys using (resolved_at is not null);
            create policy ${OutboxSchema.DEAD_LETTER}_sys_purge_triaged on $s.${OutboxSchema.DEAD_LETTER} as restrictive for delete to $sys
                using (resolved_at is not null and status <> 'OPEN');
            grant select ($DEAD_LETTER_COLUMNS) on $s.${OutboxSchema.DEAD_LETTER} to $sys;
            grant insert on $s.${OutboxSchema.DEAD_LETTER} to $sys;
            grant update (status, resolved_at, note, replayed_to) on $s.${OutboxSchema.DEAD_LETTER} to $sys;
            grant delete on $s.${OutboxSchema.DEAD_LETTER} to $sys;
            grant select, insert, update on $s.${OutboxSchema.CONSUMER_OFFSET} to $sys;
            grant insert on $s.${OutboxSchema.CONSUMER_SOURCE_SEEN} to $sys;
            """.trimIndent()
    }

    /**
     * The two claim views, handed to the sys role, `SELECT` for the runtime login only. `ALTER ... OWNER` needs the new
     * owner to hold `CREATE` on the schema, so the sys role holds it for the hand-over only (as in every function
     * below).
     */
    fun views(
        schema: String,
        runtimeRole: String,
        sysRole: String,
    ): String {
        val s = SqlIdentifier.require(schema, "schema")
        val runtime = SqlIdentifier.require(runtimeRole, "runtime role")
        val sys = SqlIdentifier.require(sysRole, "sys role")
        return """
            create view $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} as select seq, tenant_id, published_at from $s.${OutboxSchema.OUTBOX};
            create view $s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW} as select $DEAD_LETTER_COLUMNS from $s.${OutboxSchema.DEAD_LETTER};
            grant create on schema $s to $sys;
            alter view $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} owner to $sys;
            revoke all on $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} from public;
            grant select on $s.${OutboxSchema.OUTBOX_CLAIM_VIEW} to $runtime;
            alter view $s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW} owner to $sys;
            revoke all on $s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW} from public;
            grant select on $s.${OutboxSchema.DEAD_LETTER_TRIAGE_VIEW} to $runtime;
            revoke create on schema $s from $sys;
            """.trimIndent()
    }
}
