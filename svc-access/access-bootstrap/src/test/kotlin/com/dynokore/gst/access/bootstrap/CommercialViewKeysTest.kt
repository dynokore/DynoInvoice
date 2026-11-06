package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.util.UUID

/**
 * INV-AC-17762 (INV-REQ-906): access-svc built against the gst-catalogue version that holds `licence.view` and
 * `tenant-provisioning-request.view` answers them for the scope `platform`: ALLOW for `commercial.admin` in the form
 * SAAS only (the keys have no PRIVATE cell). No row of access.role_grant or access.identity changes in any call.
 */
class CommercialViewKeysTest {
    private val platform = "ffffffff-ffff-ffff-ffff-ffffffffffff"
    private val run = UUID.randomUUID().toString().take(8)

    private fun id(name: String) = "user:$name-$run"

    private fun row(
        name: String,
        role: String,
    ) {
        val identity = id(name)
        val ref = "GRT-" + UUID.randomUUID().toString().take(8)
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute(
                    "insert into access.identity (id, tenant_id, identity_ref, grantee_kind, created_at, created_by, " +
                        "updated_at, updated_by, version) select gen_random_uuid(), '$platform', '$identity', " +
                        "'STAFF', now(), 'mig', now(), 'mig', 0 where not exists (select 1 from access.identity " +
                        "where tenant_id = '$platform' and identity_ref = '$identity')",
                )
                it.execute(
                    "insert into access.role_grant (id, tenant_id, grant_ref, identity_ref, role, scope_level, " +
                        "scope_ref, valid_from, valid_until, status, grant_source, created_at, created_by, " +
                        "updated_at, updated_by, version) values (gen_random_uuid(), '$platform', '$ref', " +
                        "'$identity', '$role', 'TENANT', '$platform', now() - interval '1 day', " +
                        "now() + interval '30 days', 'ACTIVE', 'BY_HAND', now(), 'mig', now(), 'mig', 0)",
                )
            }
        }
    }

    private fun snapshot(): String =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                listOf("access.role_grant", "access.identity").joinToString("|") { table ->
                    s.executeQuery("select * from $table order by id").use { r ->
                        val n = r.metaData.columnCount
                        val out = StringBuilder()
                        while (r.next()) (1..n).forEach { out.append(r.getString(it)).append(',') }
                        out.toString()
                    }
                }
            }
        }

    private fun removeRows() {
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use {
                it.execute("delete from access.role_grant where identity_ref like '%-$run'")
                it.execute("delete from access.identity where identity_ref like '%-$run'")
            }
        }
    }

    private fun inForm(
        form: String,
        block: (AuthoriseController) -> Unit,
    ) {
        BoundaryFixture
            .runner(catalogueMatrix = true, form = form, requirements = null)
            .run { context: AssertableWebApplicationContext ->
                context.startupFailure?.let { throw it }
                try {
                    block(context.getBean(AuthoriseController::class.java))
                } finally {
                    removeRows()
                }
            }
    }

    private fun AuthoriseController.ask(
        identity: String,
        permission: String,
    ): AuthoriseResponse = authorise(identity, permission, "platform").body!!

    private fun AuthoriseController.allow(
        name: String,
        permission: String,
        role: String,
    ) {
        val r = ask(id(name), permission)
        assertThat(r.decision).describedAs("$name $permission").isEqualTo(AuthoriseResponse.Decision.ALLOW)
        assertThat(r.role).isEqualTo(role)
    }

    private fun AuthoriseController.deny(
        name: String,
        permission: String,
    ) {
        val r = ask(id(name), permission)
        assertThat(r.decision).describedAs("$name $permission").isEqualTo(AuthoriseResponse.Decision.DENY)
        assertThat(r.reasonCode).isNotBlank()
    }

    private val keys = listOf("licence.view", "tenant-provisioning-request.view")
    private val others =
        listOf("release.manager", "platform.engineer", "regulatory.author", "regulatory.approver", "platform.support")
    private val lookAlikes =
        listOf(
            "licence.views",
            "licence-view",
            "Licence.View",
            "tenant-provisioning-requests.view",
            "tenant-provisioning-request.views",
        )

    private fun seed() {
        row("commercial.admin", "commercial.admin")
        others.forEach { row(it, it) }
    }

    @Test
    fun `INV-AC-17762 the commercial admin gets ALLOW for both view keys in SAAS`() =
        inForm("SAAS") { api ->
            seed()
            val before = snapshot()
            keys.forEach { api.allow("commercial.admin", it, "commercial.admin") }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-17762 the other operator roles get DENY for both view keys in SAAS`() =
        inForm("SAAS") { api ->
            seed()
            val before = snapshot()
            others.forEach { name -> keys.forEach { api.deny(name, it) } }
            listOf("audit-entry.purge", "release-train.publish").forEach { api.deny("commercial.admin", it) }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-17762 look-alike view keys get DENY`() =
        inForm("SAAS") { api ->
            seed()
            val before = snapshot()
            lookAlikes.forEach { api.deny("commercial.admin", it) }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-17762 the commercial admin gets DENY for both view keys in PRIVATE`() =
        inForm("PRIVATE") { api ->
            seed()
            val before = snapshot()
            keys.forEach { api.deny("commercial.admin", it) }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-17762 the grant and identity tables are unchanged by the calls`() =
        inForm("SAAS") { api ->
            seed()
            val before = snapshot()
            (keys + lookAlikes + "audit-entry.purge").forEach { key ->
                (listOf("commercial.admin") + others).forEach { api.ask(id(it), key) }
            }
            assertThat(snapshot()).isEqualTo(before)
        }
}
