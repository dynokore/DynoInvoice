package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.IdpGroupController
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.idp.DirectoryGroupMembers
import com.dynokore.gst.access.contract.model.IdpGroupMappingRequest
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The synchronisation scheduler and its claim function `access.claim_idp_sync_tenants()` (INV-Q-8843 A; ARC 4.3
 * step 1a,
 * CS-19): the tenants with an ACTIVE mapping and nothing else, executable by `access_runtime` only, and each claimed
 * tenant bound while the use case runs for it.
 */
class IdpGroupSyncSchedulerTest {
    private val migration =
        File("../access-adapters/src/main/resources/db/migration/V202610240900__create_claim_idp_sync_tenants.sql")

    private fun insertMapping(
        tenant: UUID,
        group: String,
        status: String,
    ) = BoundaryFixture.asSuperuser { connection ->
        val now = Instant.now()
        connection
            .prepareStatement(
                "INSERT INTO access.idp_group_mapping (id, tenant_id, mapping_ref, idp_group, role, scope_level," +
                    " scope_ref, valid_from, status, created_at, created_by, updated_at, updated_by, version)" +
                    " VALUES (?, ?, ?, ?, 'tax.operator', 'TENANT', ?, current_date, ?, ?, 'test', ?, 'test', 0)",
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.setString(3, "MAP-" + UUID.randomUUID().toString().take(8))
                it.setString(4, group)
                it.setString(5, tenant.toString())
                it.setString(6, status)
                it.setObject(7, java.sql.Timestamp.from(now))
                it.setObject(8, java.sql.Timestamp.from(now))
                it.executeUpdate()
            }
    }

    private fun clearMappings() =
        BoundaryFixture.asSuperuser { it.createStatement().execute("DELETE FROM access.idp_group_mapping") }

    private fun claimedAsRuntime(): Set<UUID> =
        BoundaryFixture.asRuntime { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT access.claim_idp_sync_tenants()").use { rows ->
                    generateSequence { if (rows.next()) rows.getObject(1, UUID::class.java) else null }.toSet()
                }
            }
        }

    @Test
    fun `the claim returns the tenants with an ACTIVE mapping, A and B, and not C which holds only a removed one`() {
        clearMappings()
        val (a, b, c) = Triple(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
        insertMapping(a, "g1", "ACTIVE")
        insertMapping(a, "g2", "ACTIVE")
        insertMapping(b, "g1", "ACTIVE")
        insertMapping(c, "g1", "REMOVED")
        assertThat(claimedAsRuntime()).isEqualTo(setOf(a, b))
    }

    @Test
    fun `a login other than access_runtime gets a permission error`() {
        BoundaryFixture.asSuperuser { connection ->
            connection.createStatement().execute("DROP ROLE IF EXISTS claim_other")
            connection.createStatement().execute("CREATE ROLE claim_other LOGIN PASSWORD 'other-pw'")
        }
        val url = BoundaryFixture.asSuperuser { it.metaData.url }
        DriverManager.getConnection(url, "claim_other", "other-pw").use { connection ->
            assertThatThrownBy { connection.createStatement().executeQuery("SELECT access.claim_idp_sync_tenants()") }
                .isInstanceOf(SQLException::class.java)
                .satisfies({ assertThat((it as SQLException).sqlState).isEqualTo("42501") })
        }
    }

    @Test
    fun `the function is SECURITY DEFINER, owned by access_sys, fixed search_path, EXECUTE for access_runtime only`() {
        BoundaryFixture.asSuperuser { connection ->
            connection
                .createStatement()
                .executeQuery(
                    "SELECT p.prosecdef, pg_get_userbyid(p.proowner), p.proconfig::text," +
                        " has_function_privilege('public', p.oid, 'EXECUTE')," +
                        " has_function_privilege('access_runtime', p.oid, 'EXECUTE')" +
                        " FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace" +
                        " WHERE n.nspname = 'access' AND p.proname = 'claim_idp_sync_tenants'",
                ).use { rows ->
                    assertThat(rows.next()).isTrue()
                    assertThat(rows.getBoolean(1)).isTrue()
                    assertThat(rows.getString(2)).isEqualTo("access_sys")
                    assertThat(rows.getString(3)).isEqualTo("{\"search_path=pg_catalog, access, pg_temp\"}")
                    assertThat(rows.getBoolean(4)).isFalse()
                    assertThat(rows.getBoolean(5)).isTrue()
                }
        }
    }

    /** The findings of a claim migration text: what the ruling INV-Q-8843 A forbids or requires. */
    private fun findings(sql: String): List<String> {
        val flat = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        return buildList {
            if (!Regex("""(?i)\bSECURITY\s+DEFINER\b""").containsMatchIn(flat)) add("not SECURITY DEFINER")
            if (!Regex("""(?i)SET\s+search_path\s*=\s*pg_catalog,\s*access,\s*pg_temp\b""").containsMatchIn(flat)) {
                add("search_path is not pg_catalog, access, pg_temp")
            }
            if (!Regex("""(?i)REVOKE\s+ALL\s+ON\s+FUNCTION\s+access\.claim_idp_sync_tenants\(\)\s+FROM\s+PUBLIC""")
                    .containsMatchIn(flat)
            ) {
                add("EXECUTE is left to PUBLIC")
            }
            val grant =
                """(?i)GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+access\.claim_idp_sync_tenants\(\)""" +
                    """\s+TO\s+access_runtime\s*;"""
            if (!Regex(grant).containsMatchIn(flat)) {
                add("EXECUTE is not granted to access_runtime")
            }
            if (!Regex("""(?i)OWNER\s+TO\s+access_sys\b""").containsMatchIn(flat)) add("not owned by access_sys")
            if (!Regex("""(?i)WHERE\s+m\.status\s*=\s*'ACTIVE'""").containsMatchIn(flat)) {
                add("the status filter is dropped")
            }
            if (Regex("""(?i)USING\s*\(\s*true\s*\)""").containsMatchIn(flat)) add("a USING (true) policy")
            if (Regex("""(?i)BYPASSRLS""").containsMatchIn(flat)) add("BYPASSRLS")
            if (Regex("""(?i)CREATE\s+TABLE""").containsMatchIn(flat)) add("a table is added")
        }
    }

    @Test
    fun `the migration passes the claim rules and each probe breaks one of them`() {
        val sql = migration.readText()
        assertThat(findings(sql)).isEmpty()
        assertThat(findings(sql.replace("SECURITY DEFINER", ""))).contains("not SECURITY DEFINER")
        assertThat(findings(sql.replace("pg_catalog, access, pg_temp", "public, pg_catalog")))
            .contains("search_path is not pg_catalog, access, pg_temp")
        assertThat(findings(sql.replace("REVOKE ALL ON FUNCTION access.claim_idp_sync_tenants() FROM PUBLIC;", "")))
            .contains("EXECUTE is left to PUBLIC")
        assertThat(findings(sql.replace("WHERE m.status = 'ACTIVE'", ""))).contains("the status filter is dropped")
        assertThat(findings(sql + "\nCREATE POLICY p ON access.idp_group_mapping USING (true);"))
            .contains("a USING (true) policy")
        assertThat(findings(sql + "\nALTER ROLE access_runtime BYPASSRLS;")).contains("BYPASSRLS")
        assertThat(findings(sql.replace("TO access_runtime;", "TO PUBLIC;")))
            .contains("EXECUTE is not granted to access_runtime")
    }

    /** The members of a group, recording the tenant that was bound when they were asked for. */
    private class RecordingDirectory : DirectoryGroupMembers {
        lateinit var tenants: TenantContext
        val asked = CopyOnWriteArrayList<Pair<String, TenantId>>()
        val groups = HashMap<String, Set<IdentityRef>>()

        override fun members(idpGroup: String): Set<IdentityRef> {
            asked += idpGroup to tenants.current()
            return groups[idpGroup].orEmpty()
        }
    }

    @Test
    fun `the scheduler binds each claimed tenant while its use case runs and removes the grant of a leaver`() {
        clearMappings()
        val directory = RecordingDirectory()
        BoundaryFixture
            .runner()
            .withBean(DirectoryGroupMembers::class.java, { directory })
            .run { context ->
                context.startupFailure?.let { throw it }
                val tenants = context.getBean(TenantContext::class.java)
                directory.tenants = tenants
                val (a, b) = TenantId(UUID.randomUUID()) to TenantId(UUID.randomUUID())
                listOf(a to "grp-a", b to "grp-b").forEach { (tenant, group) ->
                    Seeds.grant(context, tenant, SeedSpec("user:admin", Role.TENANT_ADMIN))
                    directory.groups[group] = setOf(IdentityRef("IDN-STAYS"), IdentityRef("IDN-LEAVES"))
                    tenants.bind(tenant, "user:admin") {
                        context
                            .getBean(IdpGroupController::class.java)
                            .mapIdpGroup(
                                UUID.randomUUID(),
                                IdpGroupMappingRequest(group, "tax.operator", "tenant:$tenant", null, null),
                            )
                    }
                }
                directory.asked.clear()
                directory.groups["grp-a"] = setOf(IdentityRef("IDN-STAYS"))
                directory.groups["grp-b"] = setOf(IdentityRef("IDN-STAYS"))
                val failed = context.getBean(IdpGroupSyncScheduler::class.java).runOnce()
                assertThat(failed).isEqualTo(0)
                // the tenant bound while the use case read a group is the claimed tenant of that group, on every call
                assertThat(directory.asked).containsExactlyInAnyOrder("grp-a" to a, "grp-b" to b)
                val repository = context.getBean(GrantRepository::class.java)
                val tx = context.getBean(TransactionRunner::class.java)
                listOf(a, b).forEach { tenant ->
                    val leaver =
                        tenants.bind(tenant, "user:admin") {
                            tx.inTransaction { repository.findByIdentity(IdentityRef("IDN-LEAVES")) }
                        }
                    assertThat(leaver.single().status).isEqualTo(GrantStatus.REVOKED)
                }
            }
    }
}
