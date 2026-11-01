package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.FIRST_ADMINISTRATOR_INVITATION
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.FIRST
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provision
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.OffsetDateTime
import java.util.UUID

/** INV-T-8226 migration `V202611010900__first_administrator_invitation.sql`: the table, its RLS and its grants. */
class FirstAdministratorInvitationMigrationTest {
    private val invitation = "first_administrator_invitation"

    private fun scalar(
        c: Connection,
        sql: String,
    ): String? =
        c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    /** Row-level security forced, one tenant policy with the bound tenant for USING and WITH CHECK. */
    private fun rlsFindings(table: String): List<String> =
        BoundaryFixture.asSuperuser { c ->
            val findings = mutableListOf<String>()
            val flags =
                scalar(
                    c,
                    "select relrowsecurity || ',' || relforcerowsecurity from pg_class " +
                        "where oid = 'access.$table'::regclass",
                )
            if (flags != "true,true") findings += "row security flags $flags"
            val policy =
                scalar(
                    c,
                    "select polname || '|' || coalesce(pg_get_expr(polqual, polrelid), '') || '|' || " +
                        "coalesce(pg_get_expr(polwithcheck, polrelid), '') from pg_policy " +
                        "where polrelid = 'access.$table'::regclass",
                )
            if (policy == null || !policy.startsWith("tenant_isolation|")) findings += "policy $policy"
            val parts = policy?.split('|').orEmpty()
            parts.drop(1).forEach {
                if (!it.contains("tenant_id") || !it.contains("bound_tenant")) findings += "policy expression $it"
            }
            findings
        }

    @Test
    fun `INV-AC-8224 the table has forced row-level security as role_grant has (probe on role_grant)`() {
        assertThat(rlsFindings(invitation)).isEmpty()
        assertThat(rlsFindings("role_grant")).isEmpty()
    }

    @Test
    fun `INV-AC-8224 the runtime login can select insert and update the table and cannot delete from it`() {
        BoundaryFixture.asSuperuser { c ->
            fun has(privilege: String) =
                scalar(c, "select has_table_privilege('access_runtime', 'access.$invitation', '$privilege')")
            assertThat(listOf("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE").map { has(it) })
                .containsExactly("t", "t", "t", "f", "f")
        }
    }

    @Test
    fun `INV-AC-8224 the columns have the types and the constraints the task names`() {
        BoundaryFixture.asSuperuser { c ->
            val columns =
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "select column_name || ':' || data_type || ':' || is_nullable " +
                                "from information_schema.columns " +
                                "where table_schema = 'access' and table_name = '$invitation' order by column_name",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            assertThat(columns).contains(
                "id:uuid:NO",
                "tenant_id:uuid:NO",
                "invitation_ref:text:NO",
                "grant_ref:text:NO",
                "invited_email:text:NO",
                "expires_at:timestamp with time zone:NO",
                "code_digest:text:YES",
                "code_key_version:text:NO",
                "provisioning_key:uuid:NO",
                "request_fingerprint:text:NO",
            )
            val constraints =
                c.createStatement().use { s ->
                    s
                        .executeQuery(
                            "select pg_get_constraintdef(oid) from pg_constraint " +
                                "where conrelid = 'access.$invitation'::regclass",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            assertThat(constraints).contains(
                "UNIQUE (tenant_id, invitation_ref)",
                "UNIQUE (tenant_id, provisioning_key)",
                "FOREIGN KEY (tenant_id, grant_ref) REFERENCES access.role_grant(tenant_id, grant_ref)",
            )
            assertThat(constraints.joinToString("\n")).contains("^v[0-9]{1,3}$")
        }
    }

    @Test
    fun `INV-AC-8224 a bound tenant finds no row of another tenant and cannot insert one naming it`() =
        withService { served ->
            val tenantA = newTenant()
            val tenantB = newTenant()
            assertThat(served.provision(FIRST, CONTROL_PLANE, tenantB.toString()).status).isEqualTo(201)
            val context = served.application
            val dsl = context.getBean(DSLContext::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val visible =
                tenants.bind(tenantA, "probe") {
                    tx.inTransaction { dsl.fetchCount(FIRST_ADMINISTRATOR_INVITATION) }
                }
            assertThat(visible).isZero()
            val own =
                tenants.bind(tenantB, "probe") {
                    tx.inTransaction { dsl.fetchCount(FIRST_ADMINISTRATOR_INVITATION) }
                }
            assertThat(own).isEqualTo(1)
            val insertNamingB = runCatching { insertRow(dsl, tenants, tx, bound = tenantA, naming = tenantB) }
            assertThat(insertNamingB.isFailure).isTrue()
            assertThat(FirstAdministratorFixture.rows("access.$invitation", tenantB)).hasSize(1)
        }

    // ---- V202611021000__reissue_first_administrator_invitation.sql (INV-T-8282) ----

    private fun seedInvitation(
        c: Connection,
        tenant: TenantId,
        reissueKey: String? = null,
        fingerprint: String? = null,
    ) {
        val ref = "INVR-" + UUID.randomUUID().toString().take(8)
        val columns =
            "id, tenant_id, invitation_ref, grant_ref, invited_email, expires_at, code_digest, code_key_version, " +
                "provisioning_key, request_fingerprint, reissue_key, reissue_fingerprint, created_at, created_by, " +
                "updated_at, updated_by, version"
        val values =
            "?::uuid, ?::uuid, ?, g.grant_ref, 'a@example.sg', now(), 'd', 'v1', ?::uuid, 'f', ?::uuid, ?, " +
                "now(), 'p', now(), 'p', 0"
        val sql =
            "insert into access.first_administrator_invitation ($columns) select $values " +
                "from access.role_grant g where g.tenant_id = ?::uuid limit 1"
        c.prepareStatement(sql).use {
            it.setString(1, UUID.randomUUID().toString())
            it.setString(2, tenant.toString())
            it.setString(3, ref)
            it.setString(4, UUID.randomUUID().toString())
            it.setString(5, reissueKey)
            it.setString(6, fingerprint)
            it.setString(7, tenant.toString())
            check(it.executeUpdate() == 1) { "no grant row to hang the invitation on" }
        }
    }

    /** The message of the failure of [block]; the connection autocommits, so a refused statement leaves no trace. */
    private fun failsWith(block: () -> Unit): String? = runCatching(block).exceptionOrNull()?.message

    @Test
    fun `INV-AC-8281 the reissue columns exist, are NULL for a row never reissued and the pair check holds`() =
        withService { served ->
            val tenant = newTenant()
            assertThat(served.provision(FIRST, CONTROL_PLANE, tenant.toString()).status).isEqualTo(201)
            BoundaryFixture.asSuperuser { c ->
                val columns =
                    c
                        .createStatement()
                        .use { s ->
                            s
                                .executeQuery(
                                    "select column_name || ':' || data_type || ':' || is_nullable " +
                                        "from information_schema.columns where table_schema = 'access' " +
                                        "and table_name = '$invitation' " +
                                        "and column_name like 'reissue%' order by 1",
                                ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                        }
                assertThat(columns).containsExactly("reissue_fingerprint:text:YES", "reissue_key:uuid:YES")
                assertThat(
                    scalar(
                        c,
                        "select count(*) from access.$invitation where tenant_id = '$tenant' " +
                            "and reissue_key is null and reissue_fingerprint is null",
                    ),
                ).isEqualTo("1")
                val key = UUID.randomUUID().toString()
                assertThat(failsWith { seedInvitation(c, tenant, reissueKey = key) }).contains("reissue_pair")
                assertThat(failsWith { seedInvitation(c, tenant, fingerprint = "f") }).contains("reissue_pair")
                assertThat(failsWith { seedInvitation(c, tenant, key, "") }).contains("reissue_fingerprint")
                assertThat(failsWith { seedInvitation(c, tenant, key, "x".repeat(129)) })
                    .contains("reissue_fingerprint")
            }
        }

    @Test
    fun `INV-AC-8281 the reissue key is unique in a tenant and admitted in another tenant`() =
        withService { served ->
            val a = newTenant()
            val b = newTenant()
            assertThat(served.provision(FIRST, CONTROL_PLANE, a.toString()).status).isEqualTo(201)
            assertThat(served.provision(FIRST, CONTROL_PLANE, b.toString()).status).isEqualTo(201)
            val key = UUID.randomUUID().toString()
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute(
                        "update access.$invitation set reissue_key = '$key', reissue_fingerprint = 'f' " +
                            "where tenant_id = '$a'",
                    )
                    // the same key in another tenant is admitted
                    it.execute(
                        "update access.$invitation set reissue_key = '$key', reissue_fingerprint = 'f' " +
                            "where tenant_id = '$b'",
                    )
                }
                // a second row of tenant a with the same key is refused
                assertThat(failsWith { seedInvitation(c, a, key, "f") }).contains("reissue_key_key")
            }
        }

    @Test
    fun `INV-AC-8281 a bound tenant cannot read the reissue key of another tenant`() =
        withService { served ->
            val a = newTenant()
            val b = newTenant()
            assertThat(served.provision(FIRST, CONTROL_PLANE, b.toString()).status).isEqualTo(201)
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use {
                    it.execute(
                        "update access.$invitation set reissue_key = '${UUID.randomUUID()}', " +
                            "reissue_fingerprint = 'f' where tenant_id = '$b'",
                    )
                }
            }
            val context = served.application
            val dsl = context.getBean(DSLContext::class.java)
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val seen =
                tenants.bind(a, "probe") {
                    tx.inTransaction {
                        dsl.fetchCount(
                            FIRST_ADMINISTRATOR_INVITATION,
                            FIRST_ADMINISTRATOR_INVITATION.REISSUE_KEY.isNotNull,
                        )
                    }
                }
            assertThat(seen).isZero()
        }

    private fun insertRow(
        dsl: DSLContext,
        tenants: TenantContext,
        tx: TransactionRunner,
        bound: TenantId,
        naming: TenantId,
    ) = tenants.bind(bound, "probe") {
        tx.inTransaction {
            val t = FIRST_ADMINISTRATOR_INVITATION
            val now = OffsetDateTime.now()
            dsl
                .insertInto(t)
                .set(t.ID, UUID.randomUUID())
                .set(t.TENANT_ID, naming.value)
                .set(t.INVITATION_REF, "INVN-probe")
                .set(t.GRANT_REF, "GRT-none")
                .set(t.INVITED_EMAIL, "a@example.sg")
                .set(t.EXPIRES_AT, now)
                .set(t.CODE_KEY_VERSION, "v1")
                .set(t.PROVISIONING_KEY, UUID.randomUUID())
                .set(t.REQUEST_FINGERPRINT, "f")
                .set(t.CREATED_AT, now)
                .set(t.CREATED_BY, "p")
                .set(t.UPDATED_AT, now)
                .set(t.UPDATED_BY, "p")
                .set(t.VERSION, 0L)
                .execute()
        }
    }
}
