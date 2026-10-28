package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.contract.model.AuthoriseResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.util.UUID

/**
 * INV-AC-9863 (INV-REQ-862 behaviour 5, INV-Q-9779 A): access-svc built against the gst-catalogue version that holds
 * `regulatory-pack.author` and `regulatory-pack.approve` answers them for the scope `platform`, in the forms SAAS and
 * PRIVATE. No row of access.role_grant or access.identity changes in any call.
 */
class RegulatoryPackKeysTest {
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

    private fun check(form: String) =
        inForm(form) { api ->
            row("author", "regulatory.author")
            row("approver", "regulatory.approver")
            val before = snapshot()
            api.allow("author", "regulatory-pack.author", "regulatory.author")
            listOf("regulatory-pack.approve", "release-train.publish", "plan.manage").forEach { api.deny("author", it) }
            api.allow("approver", "regulatory-pack.approve", "regulatory.approver")
            listOf(
                "regulatory-pack.author",
                "release-train.publish",
                "plan.manage",
            ).forEach { api.deny("approver", it) }
            listOf("regulatory-pack.authorize", "regulatory-pack.approver", "Regulatory-Pack.Author").forEach {
                api.deny("author", it)
                api.deny("approver", it)
            }
            assertThat(snapshot()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9863 the regulatory pack keys are answered for scope platform in the form SAAS`() = check("SAAS")

    @Test
    fun `INV-AC-9863 the regulatory pack keys are answered for scope platform in the form PRIVATE`() = check("PRIVATE")
}
