package com.dynokore.gst.submission.adapters

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** INV-AC-24517: the catalogue of the database after the migration `held_credit_notes` (INV-T-24519). */
class HeldCreditNotesMigrationTest {
    private fun strings(sql: String): List<String> =
        SubmissionDb.superuser().use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private fun index(table: String): List<String> =
        strings(
            "select regexp_replace(pg_get_indexdef(i.indexrelid), '^.* USING ', '') from pg_index i " +
                "where i.indrelid = 'submission.$table'::regclass and not i.indisprimary and not i.indisunique " +
                "order by 1",
        )

    @Test
    fun `INV-AC-24517 held_tenants is a security definer function of submission_sys`() {
        strings(
            "select p.prosecdef || '|' || pg_get_userbyid(p.proowner) || '|' || array_to_string(p.proconfig, ',') " +
                "|| '|' || has_function_privilege('submission_runtime', p.oid, 'execute') " +
                "|| '|' || has_function_privilege('public', p.oid, 'execute') " +
                "|| '|' || pg_get_function_result(p.oid) " +
                "from pg_proc p join pg_namespace n on n.oid = p.pronamespace " +
                "where n.nspname = 'submission' and p.proname = 'held_tenants'",
        ) shouldBe
            listOf(
                "true|submission_sys|search_path=pg_catalog, submission, pg_temp|true|false|TABLE(held_tenant uuid)",
            )
    }

    @Test
    fun `INV-AC-24517 both indexes carry their partial predicates`() {
        index("submission").filter { "submission_held_idx" in it || "AWAITING_PRECEDING" in it } shouldBe
            listOf("btree (tenant_id, doc_uuid) WHERE (state = 'AWAITING_PRECEDING'::text)")
        index("obligation_register_entry").filter { "preceding_doc_uuid" in it } shouldBe
            listOf("btree (tenant_id, preceding_doc_uuid) WHERE (preceding_doc_uuid IS NOT NULL)")
    }
}
