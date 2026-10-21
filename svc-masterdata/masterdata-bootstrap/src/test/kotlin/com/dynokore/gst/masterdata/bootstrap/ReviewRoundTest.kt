package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.policy.PolicyRoleResolver
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.util.UUID

/**
 * Review round of MR !1 (rulings INV-Q-8121 A and INV-Q-8123 A): the confirmer roles fail closed, and a proposal whose
 * body carries the key `secondConfirmation` in any form is refused.
 */
class ReviewRoundTest {
    private val tenantA = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val admin = "IDN-ADMIN"
    private val otherAdmin = "IDN-ADMIN-2"
    private val agent = Constitution("SECTION_33_2_AGENT")

    private fun run(
        resolver: PolicyRoleResolver? = BoundaryFixture.testResolver,
        block: (Env) -> Unit,
    ) {
        BoundaryFixture.runner(resolver).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        private val tenants = context.getBean(TenantContext::class.java)

        fun <T> inTenant(
            actor: String,
            block: () -> T,
        ): T = tenants.bind(tenantA, actor) { block() }

        /** Proposes through the use case as [admin]; nothing is put in force. */
        fun proposed(entity: String) =
            inTenant(admin) {
                context.getBean(ProposeIdentityTripleChange::class.java).handle(
                    ProposeCommand(
                        EntityRef(entity),
                        agent,
                        IdentityTriple("0195:U-A", "U-A", "G-A"),
                        LocalDate.of(2026, 1, 1),
                        PersonRef(admin),
                    ),
                )
            }

        /** Confirms through the controller as the authenticated subject [actor]: the HTTP status or the refusal. */
        fun confirmedAs(
            actor: String,
            entity: String,
            changeRef: String,
        ): Any =
            inTenant(actor) {
                try {
                    context
                        .getBean(com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController::class.java)
                        .confirmIdentityTripleChange(entity, changeRef, UUID.randomUUID())
                        .statusCode
                } catch (refusal: ApiRefusal) {
                    refusal.code
                }
            }

        fun postProposal(
            entity: String,
            body: String,
        ): Pair<Int, String> {
            val web = context.sourceApplicationContext as org.springframework.web.context.WebApplicationContext
            val mvc =
                org.springframework.test.web.servlet.setup.MockMvcBuilders
                    .webAppContextSetup(web)
                    .build()
            return inTenant(admin) {
                mvc
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/internal/v1/masterdata/entities/$entity/identity-triple")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn()
                    .let { it.response.status to it.response.contentAsString }
            }
        }

        fun rows(
            table: String,
            entity: String,
            where: String = "true",
        ): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery("select count(*) from masterdata.$table where entity_ref = '$entity' and $where")
                    .use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }

        fun pendingRows(entity: String): Int = rows("identity_triple_change", entity, "state = 'PENDING_CONFIRMATION'")
    }

    @Test
    fun `INV-AC-1603 a body with the key secondConfirmation in any form is refused 400, an absent key is 202`() =
        run { env ->
            val head =
                """"senderId":"0195:U-A","supplierId":"U-A","supplierGstn":"G-A","effectiveFrom":"2026-07-01",""" +
                    """"constitution":"${agent.code}""""
            listOf(
                "\"secondConfirmation\":{\"identityRef\":\"$otherAdmin\",\"role\":\"tenant.admin\"}",
                "\"secondConfirmation\":null",
                "\"secondConfirmation\":{}",
                "\"secondConfirmation\" : null",
            ).forEachIndexed { index, member ->
                val entity = "ENT-KEY-$index"
                val (status, text) = env.postProposal(entity, "{$head,$member}")
                assertThat(status).describedAs(member).isEqualTo(400)
                assertThat(text).describedAs(member).contains("SRT-001")
                assertThat(env.rows("identity_triple_change", entity)).describedAs(member).isZero()
                assertThat(env.rows("identity_triple", entity)).describedAs(member).isZero()
            }
            val (status, _) = env.postProposal("ENT-KEY-ABSENT", "{$head}")
            assertThat(status).isEqualTo(202)
            assertThat(env.pendingRows("ENT-KEY-ABSENT")).isEqualTo(1)
            assertThat(env.rows("identity_triple_change", "ENT-KEY-ABSENT")).isEqualTo(1)
        }

    /** One confirmation by a different person holding [held], through a context whose resolver is [resolver]. */
    private fun confirmationUnder(
        resolver: PolicyRoleResolver?,
        entity: String = "ENT-ROLES",
        check: (outcome: Any, pending: Int, versions: Int) -> Unit,
    ) = run(resolver) { env ->
        val pending = env.proposed(entity)
        val outcome = env.confirmedAs(otherAdmin, entity, pending.changeRef)
        check(outcome, env.pendingRows(entity), env.rows("identity_triple", entity))
    }

    @Test
    fun `INV-AC-2254 confirmer roles fail closed - no resolver, empty answer, exception, role outside the set`() {
        val failures =
            mapOf(
                "no resolver bean" to null,
                "empty set" to PolicyRoleResolver { _, _, _, _, _ -> emptySet() },
                "exception" to PolicyRoleResolver { _, _, _, _, _ -> error("authority replica is down") },
                "role outside the set" to PolicyRoleResolver { _, _, _, _, _ -> setOf("agent.operator") },
                "look-alike role" to PolicyRoleResolver { _, _, _, _, _ -> setOf("Tenant.Admin", "tenant.admin2") },
                "tenant.admin only for another entity" to
                    PolicyRoleResolver { _, _, _, scopeRef, entityRef ->
                        if (scopeRef == "ENT-ANOTHER" &&
                            entityRef == "ENT-ANOTHER"
                        ) {
                            setOf("tenant.admin")
                        } else {
                            emptySet()
                        }
                    },
            )
        failures.entries.forEachIndexed { index, (name, resolver) ->
            confirmationUnder(resolver, "ENT-ROLES-$index") { outcome, pending, versions ->
                assertThat(outcome).describedAs(name).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(pending).describedAs(name).isEqualTo(1)
                assertThat(versions).describedAs(name).isZero()
            }
        }
    }

    @Test
    fun `INV-AC-2254 a resolver asked at level ENTITY for this entity and a confirmer role confirms`() {
        val asked = java.util.concurrent.CopyOnWriteArrayList<List<Any?>>()
        val resolver =
            PolicyRoleResolver { actor, tenant, level, scopeRef, entityRef ->
                asked += listOf(actor, tenant, level, scopeRef, entityRef)
                if (actor == otherAdmin) setOf("tax.approver") else emptySet()
            }
        confirmationUnder(resolver, "ENT-ROLES-PASS") { outcome, pending, versions ->
            assertThat(outcome).isEqualTo(HttpStatus.OK)
            assertThat(pending).isZero()
            assertThat(versions).isEqualTo(1)
        }
        assertThat(asked).isNotEmpty()
        asked.forEach {
            assertThat(it[0]).isEqualTo(otherAdmin)
            assertThat(it[1]).isEqualTo(tenantA)
            assertThat(it[2]).isEqualTo(ScopeLevel.ENTITY)
            assertThat(it[3]).isEqualTo("ENT-ROLES-PASS")
            assertThat(it[4]).isEqualTo("ENT-ROLES-PASS")
        }
        // and {tenant.admin} passes too
        confirmationUnder(
            PolicyRoleResolver {
                _,
                _,
                _,
                _,
                _,
                ->
                setOf("tenant.admin")
            },
            "ENT-ROLES-ADMIN",
        ) { outcome, _, _ ->
            assertThat(outcome).isEqualTo(HttpStatus.OK)
        }
    }

    @Test
    fun `INV-AC-1603 a proposal answers 202 with the pending change and puts nothing in force`() =
        run { env ->
            val head = """"senderId":"0195:U-A","supplierId":"U-A","supplierGstn":"G-A","effectiveFrom":"2026-07-01""""
            val (status, text) = env.postProposal("ENT-202", "{$head,\"constitution\":\"${agent.code}\"}")
            assertThat(status).isEqualTo(202)
            // the body validates against the schema PendingIdentityTripleChange of the pinned contract
            val schema = pendingSchemaJson()
            val findings =
                com.networknt.schema.SchemaRegistry
                    .withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schema)
                    .validate(text, com.networknt.schema.InputFormat.JSON)
            assertThat(findings).isEmpty()
            val body =
                tools.jackson.databind.json
                    .JsonMapper()
                    .readTree(text)
            assertThat(body["state"].asString()).isEqualTo("PENDING_CONFIRMATION")
            assertThat(body["proposedBy"].asString()).isEqualTo(admin)
            // the schema is not vacuous: a body that breaks it is reported
            val broken = text.replace("PENDING_CONFIRMATION", "CONFIRMED")
            assertThat(
                com.networknt.schema.SchemaRegistry
                    .withDefaultDialect(com.networknt.schema.SpecificationVersion.DRAFT_2020_12)
                    .getSchema(schema)
                    .validate(broken, com.networknt.schema.InputFormat.JSON),
            ).isNotEmpty()
            assertThat(env.rows("identity_triple", "ENT-202")).isZero()
            assertThat(env.pendingRows("ENT-202")).isEqualTo(1)
        }

    /** The schema PendingIdentityTripleChange of the pinned contract as a standalone JSON schema. */
    @Suppress("UNCHECKED_CAST")
    private fun pendingSchemaJson(): String {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/masterdata/openapi.yaml")) {
                "the masterdata contract is not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val components =
            org.yaml.snakeyaml
                .Yaml()
                .load<Map<String, Any?>>(text)["components"] as Map<String, Any?>
        val schema = (components["schemas"] as Map<String, Any?>)["PendingIdentityTripleChange"]
        return tools.jackson.databind.json
            .JsonMapper()
            .writeValueAsString(schema)
    }
}
