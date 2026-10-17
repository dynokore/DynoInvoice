package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancyjooq.TenancyProbe

/**
 * The catalogue contract of the claim functions of [schema] (INV-Q-5612), the offset and source-seen routines (INV-Q-5873)
 * and the triage page function (INV-Q-5875): result columns, SECURITY DEFINER, the
 * fixed search_path, no PUBLIC entry in the ACL, owner the sys role. Names are compared schema-qualified, so a
 * function of the same name in another schema is no answer for [schema].
 */
object ClaimContract {
    private const val PAGE_RESULT = "TABLE(seq bigint, tenant_id uuid, published_at timestamp with time zone)"
    private const val TRIAGE_RESULT =
        "TABLE(id bigint, consumer text, topic text, log_offset bigint, tenant_id uuid, reason text, status text, " +
            "recorded_at timestamp with time zone, resolved_at timestamp with time zone, note text, replayed_to text)"

    /** What deviates from the contract; empty when the catalogue holds it. */
    fun violations(
        fixture: OutboxPostgresFixture,
        schema: String,
    ): List<String> {
        val expected =
            mapOf(
                OutboxSchema.OUTBOX_CLAIM_PAGE to PAGE_RESULT,
                OutboxSchema.OUTBOX_CLAIM_PENDING to "bigint",
                OutboxSchema.CONSUMER_OFFSET_LOCK to "bigint",
                OutboxSchema.CONSUMER_OFFSET_ADVANCE to "void",
                OutboxSchema.CONSUMER_SOURCE_SEEN_RECORD to "void",
                OutboxSchema.DEAD_LETTER_TRIAGE_PAGE to TRIAGE_RESULT,
            )
        val rows =
            TenancyProbe
                .connect(fixture.jdbcUrl(), OutboxPostgresFixture.APP, OutboxPostgresFixture.APP_PASSWORD)
                .unbound {
                    fetchRows(
                        "select p.proname, pg_get_function_result(p.oid) as result, p.prosecdef, " +
                            "array_to_string(p.proconfig, ';') as config, " +
                            "exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0) as public_grant, " +
                            "pg_get_userbyid(p.proowner) as owner " +
                            "from pg_proc p where p.pronamespace = to_regnamespace(?) and p.proname in (" +
                            expected.keys.joinToString { "?" } +
                            ")",
                        schema,
                        *expected.keys.toTypedArray(),
                    )
                }.associateBy { it["proname"] }
        return expected.flatMap { (name, result) ->
            val row = rows[name] ?: return@flatMap listOf("$schema.$name is missing")
            listOfNotNull(
                "$name result ${row["result"]}".takeIf { row["result"] != result },
                "$name is not security definer".takeIf { row["prosecdef"] != true },
                "$name config ${row["config"]}".takeIf { row["config"] != "search_path=pg_catalog, $schema, pg_temp" },
                "$name is granted to PUBLIC".takeIf { row["public_grant"] != false },
                "$name owner ${row["owner"]}".takeIf { row["owner"] != OutboxPostgresFixture.SYS },
            )
        }
    }
}
