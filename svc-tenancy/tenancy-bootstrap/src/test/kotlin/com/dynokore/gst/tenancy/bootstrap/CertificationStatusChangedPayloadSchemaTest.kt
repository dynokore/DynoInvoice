package com.dynokore.gst.tenancy.bootstrap

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * The channel CertificationStatusChanged (INV-T-16428, INV-Q-16401 A): the payloads the service publishes, STALE with
 * a trigger and CURRENT without one, validate against the contract schema, and a payload without `entityRef` does not.
 */
class CertificationStatusChangedPayloadSchemaTest {
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/tenancy/schemas/certification-status-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    @Test
    fun `the STALE and the CURRENT payloads validate and a payload without entityRef does not`() {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(com.dynokore.platform.outboxandqueue.EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                val e = CertificationEnv(env, log)
                env.withEntity { tenant, entity ->
                    e.passed(entity)
                    e.identityTripleChanged(tenant, entity.entityRef)
                    e.poll("certificationIdentityTripleChangedConsumer")
                    e.certify(entity)
                    val payloads = e.stored(entity).map { JsonMapper.builder().build().readTree(it) as ObjectNode }
                    assertThat(payloads).hasSize(2)
                    payloads.forEach { assertThat(schema.validate(it.toString(), InputFormat.JSON)).isEmpty() }
                    val stale = payloads.single { it["status"].asString() == "STALE" }
                    assertThat(stale["trigger"].asString()).isEqualTo("IDENTITY_TRIPLE")
                    assertThat(stale["entityRef"].asString()).isEqualTo(entity.entityRef)
                    stale.remove("entityRef")
                    assertThat(schema.validate(stale.toString(), InputFormat.JSON)).isNotEmpty()
                }
            }
    }
}
