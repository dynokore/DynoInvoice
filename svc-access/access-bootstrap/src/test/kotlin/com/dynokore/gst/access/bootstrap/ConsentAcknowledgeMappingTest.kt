package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.ConsentController
import com.dynokore.gst.access.contract.model.AcknowledgeMappingRequest
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.util.UUID

/**
 * `acknowledgeMapping` (INV-T-4940; INV-REQ-900): the client signatory's acknowledgement of a pack version is stored as
 * evidence for exactly one client entity, decided by the scoped key `mapping-effect.acknowledge` and never by a role
 * name; the real application context over a real PostgreSQL.
 */
class ConsentAcknowledgeMappingTest {
    private val signatoryA = "user:signatory-a"
    private val outsider = "user:outsider"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val tenants = context.getBean(TenantContext::class.java)
        val api = context.getBean(ConsentController::class.java)

        init {
            listOf("CLI-A", "CLI-B").forEach { client ->
                context
                    .getBean(StructureReplica::class.java)
                    .accept(
                        OrganisationStructureChanged(
                            tenant,
                            client,
                            StructureChange.CREATED,
                            BoundaryFixture.entityNode(),
                        ),
                    )
            }
            Seeds.grant(context, tenant, SeedSpec(signatoryA, Role.CLIENT_SIGNATORY, Scope.Entity(EntityRef("CLI-A"))))
            Seeds.grant(context, tenant, SeedSpec(outsider, Role.AGENT_OPERATOR, Scope.Entity(EntityRef("CLI-A"))))
        }

        fun acknowledge(
            asUser: String,
            client: String,
            pack: String = "2026.3",
        ) = tenants.bind(tenant, asUser) {
            api.acknowledgeMapping(UUID.randomUUID(), AcknowledgeMappingRequest(pack, client))
        }

        fun read(
            client: String,
            pack: String = "2026.3",
        ) = runCatching {
            tenants.bind(tenant, signatoryA) { api.getMappingAcknowledgement(pack, client) }
        }

        fun evidenceRows(): Int =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement("select count(*) from access.mapping_acknowledgement where tenant_id = ?")
                    .use { statement ->
                        statement.setObject(1, UUID.fromString(tenant.toString()))
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "count returned no row" }
                            rows.getInt(1)
                        }
                    }
            }

        fun actorOf(client: String): String =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "select acknowledged_by from access.mapping_acknowledgement " +
                            "where tenant_id = ? and client_ref = ?",
                    ).use { statement ->
                        statement.setObject(1, UUID.fromString(tenant.toString()))
                        statement.setString(2, client)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no acknowledgement of $client" }
                            rows.getString(1)
                        }
                    }
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    @Test
    fun `INV-AC-1936 the signatory acknowledgement is stored once for its pack version and client entity`() =
        run { env ->
            val first = env.acknowledge(signatoryA, "CLI-A")
            assertThat(first.statusCode.value()).isEqualTo(201)
            assertThat(first.body!!.packVersion).isEqualTo("2026.3")
            assertThat(first.body!!.clientRef).isEqualTo("CLI-A")
            assertThat(env.actorOf("CLI-A")).isEqualTo(signatoryA)
            // a repeat returns the first acknowledgement and stores nothing more
            val repeat = env.acknowledge(signatoryA, "CLI-A")
            assertThat(repeat.body!!.acknowledgedAt).isEqualTo(first.body!!.acknowledgedAt)
            assertThat(env.evidenceRows()).isEqualTo(1)
            // the read operation finds it; a look-alike pack version or client does not
            assertThat(
                env
                    .read("CLI-A")
                    .getOrThrow()
                    .body!!
                    .acknowledgedAt,
            ).isEqualTo(first.body!!.acknowledgedAt)
            assertThat(env.read("CLI-A", "2026.30").exceptionOrNull()).isInstanceOf(ApiRefusal::class.java)
            assertThat(env.read("CLI-B").exceptionOrNull()).isInstanceOf(ApiRefusal::class.java)
        }

    @Test
    fun `INV-AC-1936 a caller without the key or on another client entity is refused and stores nothing`() =
        run { env ->
            // an agent operator holds no mapping-effect.acknowledge key; the signatory of CLI-A holds none on CLI-B
            listOf(outsider to "CLI-A", signatoryA to "CLI-B").forEach { (user, client) ->
                assertThatThrownBy { env.acknowledge(user, client) }
                    .describedAs("$user on $client")
                    .isInstanceOfSatisfying(ApiRefusal::class.java) {
                        assertThat(it.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                    }
            }
            assertThat(env.evidenceRows()).isZero()
        }
}
