package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.applyMutated
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.clone
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.connect
import com.dynokore.gst.access.bootstrap.PlatformGrantSeedFixture.policyFindings
import com.dynokore.platform.outboxandqueue.ReplayStateSource
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.Types
import java.util.UUID

/**
 * The tenant list of the full sync of AuthorityChanged (INV-T-9061, INV-Q-9047 A): the function
 * `access.replay_authority_tenants()`, its definer pinning, the gate of [PlatformGrantSeedFixture.policyFindings] over
 * a mutated copy of its migration, and the one source bean of the started context.
 */
class ReplayAuthorityTenantsTest {
    private val file = "V202611091100__create_replay_authority_tenants.sql"
    private val migration = File("../access-adapters/src/main/resources/db/migration/$file")
    private val shipped: String get() = migration.readText()

    private fun exec(
        c: Connection,
        sql: String,
    ) = c.createStatement().use { it.execute(sql) }

    private fun grant(
        c: Connection,
        tenant: UUID,
        tag: String,
    ) {
        exec(
            c,
            "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                "updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', 'user:$tag', 'STAFF', " +
                "now(), 's', now(), 's', 0) on conflict do nothing",
        )
        exec(
            c,
            "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, scope_ref, " +
                "valid_from, status, grant_source, created_at, created_by, updated_at, updated_by, version) values " +
                "(gen_random_uuid(), '$tenant', 'GRT-$tag-${UUID.randomUUID()}', 'user:$tag', 'auditor', 'TENANT', " +
                "'$tenant', now(), 'ACTIVE', 'BY_HAND', now(), 's', now(), 's', 0)",
        )
    }

    private fun consent(
        c: Connection,
        tenant: UUID,
    ) = exec(
        c,
        "insert into access.consent (id, tenant_id, consent_ref, client_entity_ref, agent_ref, valid_from, " +
            "authorising_person_email, status, handover_statement_ref, created_at, created_by, updated_at, " +
            "updated_by, version) values (gen_random_uuid(), '$tenant', 'CNS-${UUID.randomUUID()}', 'CLI-1', " +
            "'AGT-1', date '2026-01-01', 'a@b.example', 'LIVE', null, now(), 's', now(), 's', 0)",
    )

    private fun principal(
        c: Connection,
        tenant: UUID,
    ) = exec(
        c,
        "insert into access.service_principal (id, tenant_id, principal_ref, principal_type, scope, status, " +
            "created_at, created_by, updated_at, updated_by, version) values (gen_random_uuid(), '$tenant', " +
            "'PRN-${UUID.randomUUID()}', 'INGEST', 'tenant:x', 'ACTIVE', now(), 's', now(), 's', 0)",
    )

    private fun listedAsRuntime(): List<UUID> =
        BoundaryFixture.asRuntime { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT access.replay_authority_tenants()").use { rows ->
                    generateSequence { if (rows.next()) rows.getObject(1, UUID::class.java) else null }.toList()
                }
            }
        }

    @Test
    fun `INV-AC-9051 the tenant list holds the tenants of grants, consents and principals and no other`() {
        val platform = UUID.fromString(PlatformGrantSeedFixture.PLATFORM)
        val ids = List(5) { UUID.randomUUID() }
        val (t1, t2, t3) = ids
        val t4 = ids[3]
        val t5 = ids[4]
        BoundaryFixture.asSuperuser { c ->
            grant(c, t1, "one")
            consent(c, t2)
            principal(c, t3)
            grant(c, t4, "four-a")
            grant(c, t4, "four-b")
            consent(c, t4)
            principal(c, t4)
            grant(c, platform, "seed")
            // t5 holds an identity-less nothing: no row in any of the three tables
        }
        val expected =
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select tenant_id from access.role_grant union select tenant_id from access.consent " +
                                "union select tenant_id from access.service_principal",
                        ).use { r ->
                            generateSequence { if (r.next()) r.getObject(1, UUID::class.java) else null }.toSet()
                        }
                }
            }
        assertThat(expected).contains(t1, t2, t3, t4, platform).doesNotContain(t5)
        assertThat(listedAsRuntime()).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(listedAsRuntime().map { it.toString() }).isSorted() // ORDER BY of the function
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val source = context.getBean(ReplayStateSource::class.java)
            val platformBinding = context.getBean(PlatformTenantBinding::class.java)
            val listed = platformBinding.bindPlatform("svc:access-svc/job:replay") { source.tenants() }
            assertThat(listed.map { it.value }).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

    @Test
    fun `INV-AC-9051 the tenant list function is a pinned definer for the runtime login only`() {
        BoundaryFixture.asSuperuser { c ->
            exec(c, "DROP ROLE IF EXISTS replay_other")
            exec(c, "CREATE ROLE replay_other LOGIN PASSWORD 'other-pw'")
            c.createStatement().use { s ->
                s
                    .executeQuery(
                        "SELECT p.prosecdef, p.proconfig::text, pg_get_userbyid(p.proowner), " +
                            "has_function_privilege('access_runtime', p.oid, 'EXECUTE'), " +
                            "has_function_privilege('public', p.oid, 'EXECUTE'), " +
                            "has_function_privilege('replay_other', p.oid, 'EXECUTE'), " +
                            "p.prorettype = 'uuid'::regtype, p.proretset, p.proargmodes is null " +
                            "FROM pg_proc p WHERE p.pronamespace = 'access'::regnamespace " +
                            "AND p.proname = 'replay_authority_tenants'",
                    ).use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getBoolean(1)).isTrue()
                        assertThat(r.getString(2)).isEqualTo("{\"search_path=pg_catalog, access, pg_temp\"}")
                        assertThat(r.getString(3)).isEqualTo("access_sys")
                        assertThat(r.getBoolean(4)).isTrue()
                        assertThat(r.getBoolean(5)).isFalse()
                        assertThat(r.getBoolean(6)).isFalse()
                        assertThat(r.getBoolean(7)).isTrue()
                        assertThat(r.getBoolean(8)).isTrue()
                        assertThat(r.getBoolean(9)).isTrue()
                    }
            }
            exec(c, "DROP ROLE replay_other")
        }
        BoundaryFixture.asRuntime { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT * FROM access.replay_authority_tenants()").use {
                    assertThat(it.metaData.columnCount).isEqualTo(1)
                    assertThat(it.metaData.getColumnType(1)).isEqualTo(Types.OTHER)
                    assertThat(it.metaData.getColumnTypeName(1)).isEqualTo("uuid")
                }
            }
        }
    }

    @Test
    fun `INV-AC-9051 the tenant list migration text passes the static check and each probe breaks one rule`() {
        assertThat(findings(shipped)).isEmpty()
        assertThat(findings(shipped.replace("pg_catalog, access, pg_temp", "public, pg_catalog")))
            .contains("search_path is not pg_catalog, access, pg_temp")
        assertThat(findings(shipped.replace("SECURITY DEFINER", ""))).contains("not SECURITY DEFINER")
        assertThat(findings(shipped.replace("TO access_runtime;", "TO PUBLIC;")))
            .contains("EXECUTE is not granted to access_runtime")
        assertThat(findings(shipped + "\nALTER ROLE access_runtime BYPASSRLS;")).contains("BYPASSRLS")
        assertThat(findings(shipped + "\nCREATE POLICY p ON access.consent FOR SELECT TO access_sys USING (true);"))
            .contains("a policy other than the ruled one")
    }

    /** The findings of the migration text: what the ruling requires or forbids; comments are not read. */
    private fun findings(sql: String): List<String> {
        val flat = sql.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
        val fn = """access\.replay_authority_tenants\(\)"""
        return buildList {
            if (!Regex("""(?i)\bSECURITY\s+DEFINER\b""").containsMatchIn(flat)) add("not SECURITY DEFINER")
            if (!Regex("""(?i)SET\s+search_path\s*=\s*pg_catalog,\s*access,\s*pg_temp\b""").containsMatchIn(flat)) {
                add("search_path is not pg_catalog, access, pg_temp")
            }
            if (!Regex("""(?i)REVOKE\s+ALL\s+ON\s+FUNCTION\s+$fn\s+FROM\s+PUBLIC""").containsMatchIn(flat)) {
                add("EXECUTE is left to PUBLIC")
            }
            if (!Regex(
                    """(?i)GRANT\s+EXECUTE\s+ON\s+FUNCTION\s+$fn\s+TO\s+access_runtime\s*;""",
                ).containsMatchIn(flat)
            ) {
                add("EXECUTE is not granted to access_runtime")
            }
            if (!Regex("""(?i)OWNER\s+TO\s+access_sys\b""").containsMatchIn(flat)) add("not owned by access_sys")
            val policies = Regex("""(?i)CREATE\s+POLICY\s+(\w+)\s+ON\s+(\S+)""").findAll(flat).map { it.groupValues }
            if (policies.any { it[1] != "role_grant_sys_replay_tenants" || it[2] != "access.role_grant" }) {
                add("a policy other than the ruled one")
            }
            if (Regex("""(?i)BYPASSRLS""").containsMatchIn(flat)) add("BYPASSRLS")
            if (Regex("""(?i)FORCE\s+ROW\s+LEVEL""").containsMatchIn(flat)) add("a FORCE ROW LEVEL SECURITY change")
            if (Regex("""(?i)CREATE\s+TABLE""").containsMatchIn(flat)) add("a table is added")
        }
    }

    private fun gate(mutated: String): List<String> {
        val db = clone()
        applyMutated(db, file, mutated)
        return connect(db).use(::policyFindings)
    }

    private val policy =
        "CREATE POLICY role_grant_sys_replay_tenants ON access.role_grant FOR SELECT TO access_sys USING (true);"

    @Test
    fun `INV-AC-9051 the gate gives an empty list for the shipped migrations`() {
        assertThat(shipped).contains(policy)
        assertThat(gate(shipped)).isEmpty()
    }

    @Test
    fun `INV-AC-9051 the gate reports a second definer, also under the singular and the v2 name`() {
        listOf("replay_authority_tenants_v2", "replay_authority_tenant").forEach { name ->
            assertThat(gate(shipped.replace("access.replay_authority_tenants()", "access.$name()")))
                .contains("a definer function was added")
        }
    }

    @Test
    fun `INV-AC-9051 the gate reports the policy changed in role, command, qualification, name or table`() {
        val variants =
            listOf(
                policy.replace("TO access_sys", "TO access_runtime"),
                policy.replace("FOR SELECT", "FOR ALL"),
                policy.replace("FOR SELECT TO access_sys USING (true)", "FOR UPDATE TO access_sys USING (true)"),
                policy.replace("USING (true)", "USING (tenant_id IS NOT NULL)"),
                policy.replace("role_grant_sys_replay_tenants", "role_grant_sys_replay"),
                policy.replace("ON access.role_grant", "ON access.identity"),
            )
        variants.forEach { variant ->
            assertThat(gate(shipped.replace(policy, variant))).`as`(variant).isNotEmpty()
        }
    }

    @Test
    fun `INV-AC-9051 the gate reports a second policy on role_grant`() {
        val second = "\nCREATE POLICY role_grant_sys_extra ON access.role_grant FOR SELECT TO access_sys USING (true);"
        assertThat(gate(shipped + second)).isNotEmpty()
    }

    @Test
    fun `INV-AC-9051 the gate does not accept the allowed name used only in a comment`() {
        val renamed = shipped.replace("access.replay_authority_tenants()", "access.other_tenants()")
        assertThat(gate("-- replay_authority_tenants\n$renamed")).contains("a definer function was added")
    }

    @Test
    fun `INV-AC-9212 a platform-bound page holds only the installation states and a tenant-bound page none`() {
        val platform = UUID.fromString(PlatformGrantSeedFixture.PLATFORM)
        val other = UUID.randomUUID()
        BoundaryFixture.asSuperuser { c ->
            exec(c, "delete from access.installation_role_grant")
            grant(c, platform, "seed-platform")
            grant(c, other, "seed-other")
            exec(c, "update access.role_grant set authority_version = 1 where authority_version is null")
        }
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val source = context.getBean(ReplayStateSource::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val platformBinding = context.getBean(PlatformTenantBinding::class.java)
            val job = "svc:access-svc/job:replay"
            val fromPlatform = platformBinding.bindPlatform(job) { tx.inTransaction { source.page(null, null, 100) } }
            // the two configured entries and no role_grant row of the platform tenant
            assertThat(fromPlatform.items).hasSize(2)
            assertThat(fromPlatform.items.map { it.dataJson }).noneMatch { it.contains("seed-platform") }
            assertThat(fromPlatform.items).allMatch { it.dataJson.contains("INSTALLATION") }
            val fromTenant = tenants.bind(TenantId(other), job) { tx.inTransaction { source.page(null, null, 100) } }
            assertThat(fromTenant.items).isNotEmpty().noneMatch { it.dataJson.contains("INSTALLATION") }
        }
    }

    @Test
    fun `INV-AC-9051 the started context holds one source for the AuthorityChanged type`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val types = context.getBeansOfType(ReplayStateSource::class.java).values.map { it.eventType }
            assertThat(types).isEqualTo(listOf(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType))
        }
    }

    companion object {
        @AfterAll
        @JvmStatic
        fun drop() = PlatformGrantSeedFixture.drop()
    }
}
