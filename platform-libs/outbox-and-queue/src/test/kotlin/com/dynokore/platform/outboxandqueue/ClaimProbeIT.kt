package com.dynokore.platform.outboxandqueue

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** TEMPORARY probes of the claim catalogue check (INV-T-5304 pack); reverted after the pipeline run. */
@Tag("integration")
class ClaimProbeIT {
    @Test
    fun `probes`() {
        OutboxHarness("postgres:17").use { h ->
            val f = h.fixture
            listOf("probe_ok", "probe_col", "probe_path", "probe_public", "probe_other").forEach { f.createService(it) }
            val sys = OutboxPostgresFixture.SYS
            f.runAsAdmin(
                """
                drop function probe_col.outbox_claim_page(bigint, integer, boolean);
                create function probe_col.outbox_claim_page(after_seq bigint, page_size integer, unpublished_only boolean)
                  returns table(seq bigint, tenant_id uuid, published_at timestamptz, data jsonb)
                  language sql security definer set search_path = pg_catalog, probe_col, pg_temp
                  as ${'$'}${'$'} select null::bigint, null::uuid, null::timestamptz, null::jsonb ${'$'}${'$'};
                alter function probe_col.outbox_claim_page(bigint, integer, boolean) owner to $sys;
                alter function probe_path.outbox_claim_pending() reset search_path;
                grant execute on function probe_public.outbox_claim_pending() to public;
                create schema other_schema;
                create function other_schema.outbox_claim_pending() returns bigint language sql as 'select 1';
                drop function probe_other.outbox_claim_pending();
                """.trimIndent(),
            )
            assertEquals(emptyList<String>(), ClaimContract.violations(f, "probe_ok"))
            val col = ClaimContract.violations(f, "probe_col")
            val path = ClaimContract.violations(f, "probe_path")
            val pub = ClaimContract.violations(f, "probe_public")
            val other = ClaimContract.violations(f, "probe_other")
            println("PROBE col=$col path=$path public=$pub other=$other")
            assertTrue(col.any { "result" in it }, "col $col")
            assertTrue(path.any { "config" in it }, "path $path")
            assertTrue(pub.any { "PUBLIC" in it }, "public $pub")
            assertTrue(other.any { "missing" in it }, "other $other")
        }
    }
}
