package com.dynokore.gst.access.bootstrap

import java.sql.Connection

/**
 * Test isolation for the rows of one tenant in `access.role_grant` and `access.identity` (INV-AC-24245).
 *
 * The classes of one Gradle fork share one database, so a class that leaves a platform-tenant row breaks the
 * precondition of [PlatformGrantWriteSurfaceTest]. [begin] reads both counts before the first test of a class,
 * [end] reads them after the last one and fails when they differ. [delete] removes exactly the rows a class
 * inserted, by the identity refs and grant refs it generated, compared by equality.
 */
class PlatformRowBaseline(
    private val tenant: String = PLATFORM,
) {
    private var before: Counts? = null

    data class Counts(
        val grants: Int,
        val identities: Int,
    )

    fun begin() {
        before = counts(tenant)
    }

    fun end() {
        val start = checkNotNull(before) { "begin() was not called" }
        val after = counts(tenant)
        check(after == start) {
            "tenant $tenant: role_grant/identity rows before the class $start, after it $after: " +
                "the class left rows behind"
        }
    }

    companion object {
        const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"

        fun counts(tenant: String): Counts =
            BoundaryFixture.asSuperuser { c ->
                Counts(count(c, "access.role_grant", tenant), count(c, "access.identity", tenant))
            }

        /** Deletes the grants with these `grant_ref`s and the identities with these `identity_ref`s of [tenant], nothing else. */
        fun delete(
            tenant: String,
            grantRefs: Collection<String>,
            identityRefs: Collection<String>,
        ) {
            BoundaryFixture.asSuperuser { c ->
                c.autoCommit = true
                remove(c, "access.role_grant", "grant_ref", tenant, grantRefs)
                remove(c, "access.identity", "identity_ref", tenant, identityRefs)
            }
        }

        private fun count(
            c: Connection,
            table: String,
            tenant: String,
        ): Int =
            c.prepareStatement("select count(*) from $table where tenant_id = ?::uuid").use { s ->
                s.setString(1, tenant)
                s.executeQuery().use { r ->
                    r.next()
                    r.getInt(1)
                }
            }

        private fun remove(
            c: Connection,
            table: String,
            column: String,
            tenant: String,
            refs: Collection<String>,
        ) {
            if (refs.isEmpty()) return
            c.prepareStatement("delete from $table where tenant_id = ?::uuid and $column = any (?)").use { s ->
                s.setString(1, tenant)
                s.setArray(2, c.createArrayOf("text", refs.toTypedArray()))
                s.executeUpdate()
            }
        }
    }
}
