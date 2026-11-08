package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.InstallationStatusChangedOutbox
import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.contract.model.InstallLicenceRequest
import com.dynokore.gst.tenancy.contract.model.InstallationFactsRequest
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-Q-14289 A: each raise and clearing of DEP-004 and DEP-005 is the platform event InstallationStatusChanged, valid
 * against the contract schema, written under the platform tenant, never with the domain type.
 */
class InstallationStatusChangedPayloadSchemaTest {
    private val vendor = LicenceKey()
    private val mapper = JsonMapper.builder().build()
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(
                checkNotNull(
                    javaClass.classLoader.getResourceAsStream(
                        "gst/contracts/service-runtime/schemas/installation-status-changed-payload.schema.json",
                    ),
                ) { "the payload schema is not on the classpath" }.use { it.readBytes().toString(Charsets.UTF_8) },
            )

    private fun term(offsetDays: Long) = "2025-01-01/${LocalDate.now(ZoneOffset.UTC).plusDays(offsetDays)}"

    private fun drive(api: InstallationController) {
        val window = listOf("2026.10", "2026.07")
        api.recordInstallationFacts(UUID.randomUUID(), InstallationFactsRequest("2026.04", window))
        api.recordInstallationFacts(UUID.randomUUID(), InstallationFactsRequest("2026.10", window))
        listOf("LIC-P" to -1L, "LIC-Q" to 10L).forEach { (id, end) ->
            val file = vendor.file(LicenceKey.licence(id, "INST-1", term(end)))
            api.installLicence(UUID.randomUUID(), InstallLicenceRequest(id, file))
        }
    }

    private fun assertEvent(event: Envelope) {
        assertThat(schema.validate(event.dataJson, InputFormat.JSON)).isEmpty()
        assertThat(event.tenant).isEqualTo(TenantId.PLATFORM)
        assertThat(event.type).isEqualTo("com.dynokore.gst.platform.InstallationStatusChanged.v1")
        val node = mapper.readTree(event.dataJson) as ObjectNode
        assertThat(node["installationRef"].asString()).isEqualTo("INST-1")
        assertThat(event.dataJson).doesNotContain("entityRef")
        node.remove("depCode")
        assertThat(schema.validate(node.toString(), InputFormat.JSON)).isNotEmpty()
    }

    @Test
    fun `INV-AC-2079 InstallationStatusChanged payloads validate and are platform events`() {
        BoundaryFixture
            .runner()
            .withPropertyValues("gst.installation.ref=INST-1", "gst.licence.trust-anchors=${vendor.anchor}")
            .run { context ->
                context.startupFailure?.let { throw it }
                BoundaryFixture.asSuperuser { c ->
                    c.createStatement().use {
                        it.execute("delete from tenancy.installation_fact")
                        it.execute("delete from tenancy.installed_licence")
                    }
                }
                val captured = context.getBean(CapturedEvents::class.java)
                val before = captured.envelopes.count { it.type == InstallationStatusChangedOutbox.TYPE }
                drive(context.getBean(InstallationController::class.java))
                val events = captured.envelopes.filter { it.type == InstallationStatusChangedOutbox.TYPE }.drop(before)
                assertThat(events).hasSize(4)
                events.forEach(::assertEvent)
                val codes =
                    events.map {
                        val n = mapper.readTree(it.dataJson)
                        n["depCode"].asString() + n["status"].asString()
                    }
                assertThat(codes)
                    .containsExactly("DEP-004RAISED", "DEP-004CLEARED", "DEP-005RAISED", "DEP-005CLEARED")
                val domainType = "com.dynokore.gst.tenancy.InstallationStatusChanged.v1"
                assertThat(captured.envelopes.none { it.type == domainType }).isTrue()
            }
    }
}
