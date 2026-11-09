package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.adapters.http.TenancyRefusal
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverPermissions
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.RecordMigrationCutoverRequest
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlMapFactoryBean
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * recordMigrationCutover and getMigrationCutover through the real application context over PostgreSQL with forced
 * row-level security (INV-REQ-1109; owner ruling INV-Q-6232 A): one cutover per entity, the attribute
 * migrationCutoverDate on EntityProfileChanged, the cross-tenant case of INV-AC-1900 for this table. The decisive
 * assertion of INV-AC-2107 and INV-AC-11520 (the guard that sets a document to EXTERNALLY_SUBMITTED) belongs to
 * submission-svc; here only the stored date, its single record and its attribute are asserted.
 */
class MigrationCutoverTest {
    private val mapper = JsonMapper.builder().build()
    private val cutover = LocalDate.of(2026, 10, 1)
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

    /** The `x-operation.permission` of [operationId] in the pinned contract document (a classpath resource). */
    private fun pinnedPermission(operationId: String): String {
        val factory = YamlMapFactoryBean()
        factory.setResources(
            ByteArrayResource(
                checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/tenancy/openapi.yaml")) {
                    "the tenancy-contract module has no gst/contracts/tenancy/openapi.yaml"
                }.use { it.readAllBytes() },
            ),
        )
        val paths = checkNotNull(factory.getObject())["paths"] as Map<*, *>
        val operations = paths.values.flatMap { (it as Map<*, *>).values }.filterIsInstance<Map<*, *>>()
        val operation = operations.single { it["operationId"] == operationId }
        return (operation["x-operation"] as Map<*, *>)["permission"] as String
    }

    private fun run(block: (ProfileEnv) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(ProfileEnv(context))
        }
    }

    private fun ProfileEnv.people(): TestPeople = context.getBean(TestPeople::class.java)

    /** The caller of the test holds both keys on [entity]. */
    private fun ProfileEnv.permit(
        entity: EntityResponse,
        vararg keys: String = arrayOf(MigrationCutoverPermissions.RECORD, MigrationCutoverPermissions.VIEW),
    ) {
        keys.forEach { people().grantPermission(people().caller, entity.entityRef, it) }
    }

    private fun ProfileEnv.record(
        entity: EntityResponse,
        date: LocalDate = cutover,
        priorSolution: String = "LegacyPeppolProvider",
        approvedBy: String? = null,
        evidenceRef: String? = "EVD-20261001",
    ) = api.recordMigrationCutover(
        entity.entityRef,
        UUID.randomUUID(),
        RecordMigrationCutoverRequest(date, priorSolution, approvedBy, evidenceRef),
    )

    private fun refusal(block: () -> Any?): Throwable {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertThat(thrown).isNotNull
        return thrown!!
    }

    private fun code(block: () -> Any?): ClosedErrorCode {
        val thrown = refusal(block)
        assertThat(thrown).isInstanceOf(ApiRefusal::class.java)
        return (thrown as ApiRefusal).code
    }

    private class Row(
        val data: JsonNode,
    ) {
        val profile: Set<Triple<String, String, String>>
            get() =
                data["node"]["profile"]
                    .values()
                    .map { Triple(it["attribute"].asString(), it["value"].asString(), it["effectiveDate"].asString()) }
                    .toSet()
    }

    /** The EntityProfileChanged rows of the entity, oldest first, read as the superuser. */
    private fun events(entity: EntityResponse): List<Row> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select data::text from tenancy.outbox " +
                        "where type = 'com.dynokore.gst.tenancy.EntityProfileChanged.v1' " +
                        "and entity_ref = ? order by seq",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) Row(mapper.readTree(rs.getString(1))) else null }.toList()
                    }
                }
        }

    private fun storedRows(entity: EntityResponse): List<List<String?>> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select m.cutover_date::text, m.prior_solution, m.approved_by, m.evidence_ref, " +
                        "m.recorded_at::text from tenancy.migration_cutover m " +
                        "join tenancy.entity e on e.tenant_id = m.tenant_id and e.id = m.entity_id where e.ref = ?",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) (1..5).map { rs.getString(it) } else null
                        }.toList()
                    }
                }
        }

    private fun outboxCount(entity: EntityResponse): Int = events(entity).size

    @Test
    fun `INV-AC-2107 INV-AC-11520 the first record answers 201 and the row equals the request plus approver`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity)
                val first = env.record(entity)
                assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
                val body = first.body!!
                assertThat(body.entityRef).isEqualTo(entity.entityRef)
                assertThat(body.cutoverDate).isEqualTo(cutover)
                assertThat(body.priorSolution).isEqualTo("LegacyPeppolProvider")
                assertThat(body.approvedBy).isEqualTo("identity:USR-001")
                assertThat(body.evidenceRef).isEqualTo("EVD-20261001")
                val rows = storedRows(entity)
                assertThat(rows).hasSize(1)
                assertThat(rows.single().take(4))
                    .containsExactly("2026-10-01", "LegacyPeppolProvider", "identity:USR-001", "EVD-20261001")
                assertThat(env.api.getMigrationCutover(entity.entityRef).body).isEqualTo(body)
                val given = env.record(entity, approvedBy = "identity:USR-001")
                assertThat(given.statusCode).isEqualTo(HttpStatus.OK)
            }
        }
    }

    @Test
    fun `INV-AC-11520 a repeat answers 200 with the identical record and one row, another date is 409 TNC-011`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity)
                val first = env.record(entity).body!!
                val before = storedRows(entity)
                val repeat = env.record(entity)
                assertThat(repeat.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(repeat.body).isEqualTo(first)
                assertThat(storedRows(entity)).isEqualTo(before).hasSize(1)

                val thrown = refusal { env.record(entity, date = cutover.plusDays(1), priorSolution = "Other") }
                assertThat(thrown).isInstanceOf(TenancyRefusal::class.java)
                assertThat((thrown as TenancyRefusal).code).isEqualTo(TenancyErrorCode.MIGRATION_CUTOVER_CONFLICT)
                val problem = Problems.renderTenancy(thrown.code, "corr-test", thrown.detail)
                assertThat(problem.status).isEqualTo(409)
                val json = mapper.readTree(problem.body)
                assertThat(json["code"].asString()).isEqualTo("TNC-011")
                assertThat(json["type"].asString()).isEqualTo("https://errors.invoice.internal/TNC-011")
                assertThat(json["severity"].asString()).isEqualTo("BLOCK")
                assertThat(json["disposition"].asString()).isEqualTo("TERMINAL")
                assertThat(storedRows(entity)).isEqualTo(before)
                assertThat(env.api.getMigrationCutover(entity.entityRef).body).isEqualTo(first)
            }
        }
    }

    @Test
    fun `INV-AC-2107 priorSolution of 129 characters or none, a foreign approvedBy and a long evidenceRef are 400`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity)
                assertThat(code { env.record(entity, priorSolution = "x".repeat(129)) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(code { env.record(entity, priorSolution = "") })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(code { env.record(entity, approvedBy = "identity:USR-OTHER") })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(code { env.record(entity, evidenceRef = "e".repeat(65)) })
                    .isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
                assertThat(storedRows(entity)).isEmpty()
                assertThat(outboxCount(entity)).isZero()
                val edge = env.record(entity, priorSolution = "x".repeat(128), evidenceRef = null)
                assertThat(edge.statusCode).isEqualTo(HttpStatus.CREATED)
                assertThat(edge.body!!.evidenceRef).isNull()
            }
        }
    }

    @Test
    fun `INV-AC-2107 a caller without the record key gets 403, nothing is stored or published, the keys are asked`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity, MigrationCutoverPermissions.VIEW, "entity-config.manage")
                val asked = env.people().asked
                asked.clear()
                assertThat(code { env.record(entity) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(asked).containsExactly(pinnedPermission("recordMigrationCutover"))
                assertThat(storedRows(entity)).isEmpty()
                assertThat(outboxCount(entity)).isZero()
                assertThat(code { env.api.getMigrationCutover(entity.entityRef) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    @Test
    fun `INV-AC-2107 a caller without the view key gets 403 and one holding the manage key only is refused on both`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity, MigrationCutoverPermissions.RECORD)
                env.record(entity)
                val asked = env.people().asked
                asked.clear()
                assertThat(code { env.api.getMigrationCutover(entity.entityRef) })
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(asked).containsExactly(pinnedPermission("getMigrationCutover"))
            }
            env.withEntity { _, entity ->
                env.permit(entity, "entity-config.manage")
                assertThat(code { env.record(entity) }).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(code { env.api.getMigrationCutover(entity.entityRef) })
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            }
        }
    }

    @Test
    fun `INV-AC-1900 getMigrationCutover is 404 for an entity with no record and for an entity of another tenant`() {
        run { env ->
            lateinit var foreign: EntityResponse
            env.withEntity { _, entity ->
                foreign = entity
                env.permit(entity)
                assertThat(code { env.api.getMigrationCutover(entity.entityRef) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                env.record(entity)
                assertThat(env.api.getMigrationCutover(entity.entityRef).statusCode).isEqualTo(HttpStatus.OK)
            }
            env.withEntity { _: TenantId, _ ->
                env.permit(foreign)
                assertThat(code { env.api.getMigrationCutover(foreign.entityRef) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
                assertThat(code { env.record(foreign) }).isEqualTo(ClosedErrorCode.NOT_FOUND)
            }
            assertThat(storedRows(foreign)).hasSize(1)
        }
    }

    @Test
    fun `INV-AC-2107 the first record publishes one EntityProfileChanged, a repeat, a refusal and a 403 none`() {
        run { env ->
            env.withEntity { _, entity ->
                env.permit(entity)
                assertThat(events(entity)).isEmpty()
                env.record(entity)
                val row = events(entity).single()
                val change = row.data["changedAttributes"].values().single()
                assertThat(change["attribute"].asString()).isEqualTo("migrationCutoverDate")
                assertThat(change["newValue"].asString()).isEqualTo("2026-10-01")
                assertThat(change.has("oldValue")).isFalse()
                assertThat(row.data["effectiveDate"].asString()).isEqualTo("2026-10-01")
                assertThat(row.profile).contains(Triple("migrationCutoverDate", "2026-10-01", "2026-10-01"))
                assertThat(row.profile.count { it.first == "migrationCutoverDate" }).isEqualTo(1)
                assertThat(row.data["node"]["structureVersion"].asLong()).isPositive()
                assertThat(row.data["entityRef"].asString()).isEqualTo(entity.entityRef)
                assertThat(schema.validate(row.data.toString(), InputFormat.JSON)).isEmpty()

                assertThat(env.record(entity).statusCode).isEqualTo(HttpStatus.OK)
                assertThat(events(entity)).hasSize(1)
                refusal { env.record(entity, date = cutover.plusDays(3)) }
                assertThat(events(entity)).hasSize(1)
                refusal { env.record(entity, priorSolution = "") }
                assertThat(events(entity)).hasSize(1)
            }
            env.withEntity { _, entity ->
                env.permit(entity, MigrationCutoverPermissions.VIEW)
                refusal { env.record(entity) }
                assertThat(events(entity)).isEmpty()
            }
        }
    }
}
