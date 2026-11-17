package com.dynokore.gst.access.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** The self-check of [PlatformRowBaseline] on scratch tenants, never on the platform tenant (INV-AC-24245). */
class PlatformRowBaselineTest {
    private fun insert(
        tenant: String,
        tag: String,
    ) = BoundaryFixture.asSuperuser { c ->
        c.createStatement().use {
            it.execute(
                "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                    "updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', 'user:$tag', 'STAFF', " +
                    "now(), 's', now(), 's', 0)",
            )
            it.execute(
                "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                    "scope_ref, valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, " +
                    "version) values (gen_random_uuid(), '$tenant', 'GRT-$tag', 'user:$tag', 'auditor', 'TENANT', " +
                    "'$tenant', now(), 'ACTIVE', 'BY_HAND', now(), 's', now(), 's', 0)",
            )
        }
    }

    private fun remove(
        tenant: String,
        tag: String,
    ) = PlatformRowBaseline.delete(tenant, listOf("GRT-$tag"), listOf("user:$tag"))

    @Test
    fun `INV-AC-24245 the helper fails the class when a row of the tenant is left behind`() {
        val tenant = UUID.randomUUID().toString()
        val tag = "left-${UUID.randomUUID()}"
        val baseline = PlatformRowBaseline(tenant)
        baseline.begin()
        insert(tenant, tag)
        try {
            assertThatThrownBy { baseline.end() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("left rows behind")
        } finally {
            remove(tenant, tag)
        }
        assertThat(PlatformRowBaseline.counts(tenant)).isEqualTo(PlatformRowBaseline.Counts(0, 0))
    }

    @Test
    fun `INV-AC-24245 the helper passes when the row is deleted by its refs`() {
        val tenant = UUID.randomUUID().toString()
        val tag = "gone-${UUID.randomUUID()}"
        val baseline = PlatformRowBaseline(tenant)
        baseline.begin()
        insert(tenant, tag)
        assertThat(PlatformRowBaseline.counts(tenant)).isEqualTo(PlatformRowBaseline.Counts(1, 1))
        remove(tenant, tag)
        baseline.end()
    }

    @Test
    fun `INV-AC-24245 a row under a look-alike tenant id does not change the platform baseline`() {
        val lookAlike = PlatformRowBaseline.PLATFORM.dropLast(1) + "e"
        val tag = "alike-${UUID.randomUUID()}"
        val baseline = PlatformRowBaseline()
        baseline.begin()
        insert(lookAlike, tag)
        try {
            baseline.end()
        } finally {
            remove(lookAlike, tag)
        }
    }

    @Test
    fun `INV-AC-24245 delete removes only the named refs`() {
        val tenant = UUID.randomUUID().toString()
        val kept = "kept-${UUID.randomUUID()}"
        val dropped = "dropped-${UUID.randomUUID()}"
        insert(tenant, kept)
        insert(tenant, dropped)
        try {
            remove(tenant, dropped)
            assertThat(PlatformRowBaseline.counts(tenant)).isEqualTo(PlatformRowBaseline.Counts(1, 1))
        } finally {
            remove(tenant, kept)
        }
    }
}
