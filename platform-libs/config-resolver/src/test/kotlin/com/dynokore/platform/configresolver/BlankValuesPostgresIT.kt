package com.dynokore.platform.configresolver

import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/**
 * INV-T-4616 (coordinator rulings INV-Q-4614 (A), INV-Q-4622 (A)): every blank check of config-resolver's functions
 * and CHECKs uses tenancy-kernel's shared definition. A whitespace-only value (ISO controls and the caseId whitespace
 * set) is refused through each function argument, each `app.actor` read and each CHECK (by a direct insert as the
 * owner), with the function's own error and nothing written; an actor holding a control character is refused the same
 * way; a value with a visible character is stored unchanged. PostgreSQL 16, 17 and 18.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlankValuesPostgresIT {
    private val harnesses = mutableMapOf<String, WriteHarness>()

    @AfterAll
    fun stop() = harnesses.values.forEach { it.close() }

    private fun harness(image: String) = harnesses.getOrPut(image) { WriteHarness(image) }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `write_installation_setting refuses a blank or control actor, reason and staged id`(image: String) {
        val h = harness(image)
        val missed = mutableListOf<String>()

        fun probe(
            name: String,
            state: String,
            message: String,
            call: () -> Unit,
        ) {
            val before = h.stored()
            val refused = runCatching { h.refusal(call) }.getOrNull()
            if (refused == null || refused.sqlState != state || message !in refused.message) {
                missed += "$name: ${refused?.message ?: "accepted"}"
            }
            assertEquals(before, h.stored(), "$name wrote rows")
        }
        NOT_ACTORS.forEach { (name, actor) ->
            probe("actor $name", "42501", "app.actor is not set") { h.callInstallation(h.staged(), actor = actor) }
        }
        BLANKS.forEach { (name, value) ->
            probe("reason $name", "22023", "are required") { h.callInstallation(h.staged().copy(reason = value)) }
            listOf("instance_id", "correlation_id", "causation_id").forEach { field ->
                probe("$field $name", "22023", "needs an instance, correlation and causation") {
                    val args = h.staged()
                    h.callInstallation(args.copy(audit = args.audit.deepCopy().put(field, value)))
                }
            }
        }
        assertEquals(emptyList<String>(), missed)

        VISIBLE_ACTORS.forEach { actor ->
            val id = h.callInstallation(h.staged(actor), actor = actor).single()
            assertEquals(actor, h.text("select set_by from svc.config_installation_setting where id = '$id'"))
        }
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `the purges refuse a blank or control actor and delete nothing`(image: String) {
        val h = harness(image)
        val tenant = UUID.randomUUID().toString()
        h.ownerProbe.inTenant(TenantId(UUID.fromString(tenant))) {
            fetchValue(
                "insert into svc.config_resolved_value (id, tenant_id, subject_type, subject_ref, vp_key, " +
                    "transaction_date, option_keys, source, resolved_at) values (gen_random_uuid(), ?::uuid, " +
                    "'submission', 'doc-1/v1', 'VP-11', current_date, array['A'], 'DEFAULT', now()) returning id",
                tenant,
            )
        }
        val purges =
            listOf(
                "select svc.config_purge_subject('submission', 'doc-1/v1')",
                "select * from svc.config_purge_tenant('$tenant')",
            )
        val missed =
            purges.flatMap { sql ->
                NOT_ACTORS.mapNotNull { (name, actor) ->
                    val refused = runCatching { h.refusal { h.raw(tenant, actor) { fetchValue(sql) } } }.getOrNull()
                    val ok = refused != null && refused.sqlState == "42501" && "app.actor is not set" in refused.message
                    "$sql, actor $name".takeUnless { ok }
                }
            }
        assertEquals(emptyList<String>(), missed)
        val own = TenantId(UUID.fromString(tenant))
        assertEquals(1L, h.count("select count(*) from svc.config_resolved_value where tenant_id = '$tenant'", own))
        h.raw(tenant, "CASE 5521") { fetchValue(purges[0]) }
        assertEquals(0L, h.count("select count(*) from svc.config_resolved_value where tenant_id = '$tenant'", own))
    }

    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:16", "postgres:17", "postgres:18"])
    fun `every CHECK refuses a blank value, and an actor CHECK a control character, by a direct owner insert`(
        image: String,
    ) {
        val h = harness(image)
        val tenant = UUID.randomUUID().toString()
        val missed = mutableListOf<String>()
        INSERTS.forEach { insert ->
            insert.columns.forEach { (column, actor) ->
                (if (actor) NOT_ACTORS else BLANKS).forEach { (name, value) ->
                    val before = counts(h, tenant)
                    val refused = runCatching { h.refusal { insert.run(h, tenant, column, value) } }.getOrNull()
                    if (refused == null || refused.sqlState != "23514") {
                        missed += "${insert.table}.$column $name"
                    }
                    assertEquals(before, counts(h, tenant), "${insert.table}.$column $name wrote a row")
                }
                val accepted = if (actor) VISIBLE_ACTORS else VISIBLE_ACTORS + " \t x \n "
                accepted.forEach { value ->
                    val id = insert.run(h, tenant, column, value)
                    val stored =
                        h.ownerQuery(tenant, "select $column from svc.${insert.table} where id = '$id'")
                    assertEquals(value, stored, "${insert.table}.$column stored")
                }
            }
        }
        assertEquals(emptyList<String>(), missed)
        // The nullable acknowledgement reason stays optional.
        INSERTS.single { it.table == "config_relaxation_acknowledgement" }.run(h, tenant, "reason", null)
    }

    /** The rows of each table that [tenant]'s binding sees (forced RLS; the installation table has no tenant). */
    private fun counts(
        h: WriteHarness,
        tenant: String,
    ) = INSERTS.map { h.count("select count(*) from svc.${it.table}", TenantId(UUID.fromString(tenant))) }

    /** One owner insert of [table] with every probed column valid but [column]; returns the new row's id. */
    private class Insert(
        val table: String,
        val sql: String,
        /** The probed columns in parameter order, true for an actor column. */
        val columns: List<Pair<String, Boolean>>,
        /** True when the second parameter is the row's tenant. */
        val tenanted: Boolean = true,
    ) {
        fun run(
            h: WriteHarness,
            tenant: String,
            column: String,
            value: String?,
        ): UUID {
            val id = UUID.randomUUID()
            val binds = mutableListOf<Any?>(id.toString())
            if (tenanted) binds += tenant
            columns.forEach { (name, _) -> binds += if (name == column) value else "v-$name" }
            // The row's tenant is bound as the migration login's own, so forced RLS passes the insert.
            h.ownerProbe.inTenant(
                TenantId(UUID.fromString(tenant)),
            ) { fetchValue("$sql returning id", *binds.toTypedArray()) }
            return id
        }
    }

    private fun WriteHarness.ownerQuery(
        tenant: String,
        sql: String,
    ): String? = ownerProbe.inTenant(TenantId(UUID.fromString(tenant))) { fetchValue(sql) } as String?

    private companion object {
        /** Whitespace-only values: ISO controls and the caseId whitespace set (INV-Q-4614), alone and mixed. */
        val BLANKS =
            listOf(
                "tab" to "\t",
                "newline" to "\n",
                "CR" to "\r",
                "U+0085" to "\u0085",
                "U+00A0" to "\u00A0",
                "U+1680" to "\u1680",
                "U+2000" to "\u2000",
                "U+200A" to "\u200A",
                "U+2028" to "\u2028",
                "U+2029" to "\u2029",
                "U+202F" to "\u202F",
                "U+205F" to "\u205F",
                "U+3000" to "\u3000",
                "U+FEFF" to "\uFEFF",
                "U+0001" to "\u0001",
                "U+009F" to "\u009F",
                "mix" to " \u00A0\t\u3000\r\n\uFEFF",
            )

        /** Blank values and actors holding a control character next to visible ones (INV-Q-4622). */
        val NOT_ACTORS =
            BLANKS +
                listOf(
                    "a TAB b" to "a\tb",
                    "LF x" to "\nx",
                    "x CR" to "x\r",
                    "x U+0085" to "x\u0085",
                    "ESC [31mx" to "\u001B[31mx",
                    "x U+009B" to "x\u009B",
                )

        /** Actors with a visible character, stored unchanged. */
        val VISIBLE_ACTORS = listOf("x\u200By", "CASE 5521", "a".repeat(254) + "z")

        val INSERTS =
            listOf(
                Insert(
                    "config_setting",
                    "insert into svc.config_setting (id, tenant_id, vp_key, scope_level, scope_id, option_keys, " +
                        "source, effective_from, set_by, reason, recorded_at) values (?::uuid, ?::uuid, 'VP-11', " +
                        "'TENANT', ?, array['A'], 'OVERRIDE', current_date, ?, ?, now())",
                    listOf("scope_id" to false, "set_by" to true, "reason" to false),
                ),
                Insert(
                    "config_installation_setting",
                    "insert into svc.config_installation_setting (id, vp_key, option_keys, effective_from, set_by, " +
                        "reason, recorded_at) values (?::uuid, 'VP-11', array['A'], current_date, ?, ?, now())",
                    listOf("set_by" to true, "reason" to false),
                    tenanted = false,
                ),
                Insert(
                    "config_resolved_value",
                    "insert into svc.config_resolved_value (id, tenant_id, subject_type, subject_ref, vp_key, " +
                        "transaction_date, option_keys, source, resolved_at) values (?::uuid, ?::uuid, " +
                        "'submission', ?, 'VP-11', current_date, array['A'], 'DEFAULT', now())",
                    listOf("subject_ref" to false),
                ),
                Insert(
                    "config_relaxation_acknowledgement",
                    "insert into svc.config_relaxation_acknowledgement (id, tenant_id, relaxation_id, vp_key, " +
                        "scope_level, scope_id, option_keys, role, principal, effective_from, reason, " +
                        "acknowledged_at) values (?::uuid, ?::uuid, gen_random_uuid(), 'VP-11', 'TENANT', ?, " +
                        "array['A'], ?, ?, current_date, ?, now())",
                    listOf("scope_id" to false, "role" to false, "principal" to true, "reason" to false),
                ),
            )
    }
}
