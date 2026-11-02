package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RateSourceElectionRequest
import com.dynokore.gst.tenancy.contract.model.SetIssuanceModeRequest
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The elected exchange-rate source is published as the profile attribute exchangeRateSource on EntityProfileChanged
 * (INV-REQ-1285, INV-REQ-504; INV-Q-16533 A, tenant source INV-Q-16597 A). The subject is the outbox rows of type
 * EntityProfileChanged appended by the real composition against PostgreSQL.
 */
class RateSourceProfileAttributeTest {
    private val mapper = JsonMapper.builder().build()
    private val daily = RateSourceElectionRequest.Basis.DAILY
    private val monthly = RateSourceElectionRequest.Basis.MONTHLY_AVERAGE
    private val later = TODAY.plusDays(30)
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/entity-profile-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private class Row(
        val tenant: UUID,
        val data: JsonNode,
    ) {
        val profile: Set<Triple<String, String, String>>
            get() =
                data["node"]["profile"]
                    .values()
                    .map { Triple(it["attribute"].asString(), it["value"].asString(), it["effectiveDate"].asString()) }
                    .toSet()
    }

    private fun run(block: (ElectionEnv, ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            block(ElectionEnv(env), env)
        }
    }

    private fun ready(
        e: ElectionEnv,
        tenant: com.dynokore.platform.tenancykernel.TenantId,
        entity: EntityResponse,
    ) {
        e.usualPeople(entity)
        e.staged(tenant, "SCOPE-SG", "1.0", sources = TWO_SOURCES)
        e.activated(tenant, "SCOPE-SG", "1.0", TODAY.minusDays(30))
    }

    /** The EntityProfileChanged rows of the entity, oldest first, read as the superuser. */
    private fun rows(entity: EntityResponse): List<Row> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select tenant_id, data::text from tenancy.outbox " +
                        "where type = 'com.dynokore.gst.tenancy.EntityProfileChanged.v1' and entity_ref = ? order by seq",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                Row(
                                    rs.getObject(1, UUID::class.java),
                                    mapper.readTree(rs.getString(2)),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun rateChange(row: Row): JsonNode =
        row.data["changedAttributes"].values().single { it["attribute"].asString() == "exchangeRateSource" }

    @Test
    fun `a first election appends one event with the attribute, the full profile, the tenant and a valid payload`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                assertThat(rows(entity)).isEmpty()
                assertThat(e.rate(entity, "MAS", daily).statusCode).isEqualTo(HttpStatus.CREATED)
                val row = rows(entity).single()
                assertThat(row.data["changedAttributes"].values()).hasSize(1)
                val change = rateChange(row)
                assertThat(change.has("oldValue")).isFalse()
                assertThat(change["newValue"].asString()).isEqualTo("MAS")
                assertThat(row.tenant).isEqualTo(tenant.value)
                assertThat(row.profile)
                    .contains(Triple("exchangeRateSource", "MAS", TODAY.toString()))
                    .noneMatch { it.second == "DAILY" }
                assertThat(row.data["node"]["profile"].values().map { it["attribute"].asString() })
                    .doesNotContain("exchangeRateSources", "rateSource")
                assertThat(schema.validate(row.data.toString(), InputFormat.JSON)).isEmpty()
            }
        }
    }

    @Test
    fun `a pending change appends none, its approval appends one, a repeat and a refusal append none`() {
        run { e, env ->
            env.withEntity { tenant, entity ->
                ready(e, tenant, entity)
                e.rate(entity, "MAS", daily)
                val first = rows(entity)
                assertThat(first).hasSize(1)
                // a repeat of the same election for the same date
                assertThat(e.rate(entity, "MAS", daily).statusCode).isEqualTo(HttpStatus.OK)
                assertThat(rows(entity)).hasSize(1)
                // a source not on the approved list
                assertThat(e.refusal { e.rate(entity, "XXX", daily) }).isNotNull
                assertThat(rows(entity)).hasSize(1)
                assertThat(e.history(entity, com.dynokore.gst.tenancy.domain.ElectionKind.EXCHANGE_RATE_SOURCE))
                    .hasSize(1)
                // a change answers 202
                val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                assertThat(rows(entity)).hasSize(1)
                // its approval
                e.people.caller = APPROVER
                assertThat(e.approve(entity, change.changeRef).statusCode).isEqualTo(HttpStatus.OK)
                val all = rows(entity)
                assertThat(all).hasSize(2)
                val row = all.last()
                assertThat(row.data["changedAttributes"].values()).hasSize(1)
                assertThat(rateChange(row)["oldValue"].asString()).isEqualTo("MAS")
                assertThat(rateChange(row)["newValue"].asString()).isEqualTo("BNM")
                assertThat(row.tenant).isEqualTo(tenant.value)
                assertThat(row.profile.filter { it.first == "exchangeRateSource" })
                    .containsExactlyInAnyOrder(
                        Triple("exchangeRateSource", "MAS", TODAY.toString()),
                        Triple("exchangeRateSource", "BNM", later.toString()),
                    )
                assertThat(all.map { it.data["node"]["structureVersion"].asLong() }).isSorted().doesNotHaveDuplicates()
                assertThat(schema.validate(row.data.toString(), InputFormat.JSON)).isEmpty()
            }
        }
    }

    @Test
    fun `both attributes survive in either order`() {
        listOf(true, false).forEach { issuanceFirst ->
            run { e, env ->
                env.withEntity { tenant, entity ->
                    ready(e, tenant, entity)
                    val mode = { e.mode(entity, SetIssuanceModeRequest.Mode.RELAY) }
                    val rate = { e.rate(entity, "MAS", daily) }
                    if (issuanceFirst) {
                        mode()
                        rate()
                    } else {
                        rate()
                        mode()
                    }
                    val all = rows(entity)
                    assertThat(all).hasSize(2)
                    assertThat(all.last().profile).containsExactlyInAnyOrder(
                        Triple("issuanceMode", "RELAY", TODAY.toString()),
                        Triple("exchangeRateSource", "MAS", TODAY.toString()),
                    )
                    assertThat(all.map { it.tenant }.toSet()).containsExactly(tenant.value)
                }
            }
        }
    }

    @Test
    fun `two tenants electing in one test each get the event under their own tenant`() {
        run { e, env ->
            val seen =
                List(2) {
                    env.withEntity { tenant, entity ->
                        ready(e, tenant, entity)
                        e.rate(entity, "MAS", daily)
                        val first = rows(entity).single()
                        val change = e.pending { e.rate(entity, "BNM", monthly, later) }
                        e.people.caller = APPROVER
                        e.approve(entity, change.changeRef)
                        val approved = rows(entity).last()
                        assertThat(first.tenant).isEqualTo(tenant.value)
                        assertThat(approved.tenant).isEqualTo(tenant.value)
                        tenant.value
                    }
                }
            assertThat(seen.toSet()).hasSize(2)
        }
    }
}
