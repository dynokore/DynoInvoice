package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * The published `maxLength` 512 and the URI syntax of `CreateConnectionRequest.endpoint` are enforced by the generated
 * model (INV-T-19124), with no service-local validator (INV-T-18774). INV-REQ-834.
 */
class ConnectionEndpointTest {
    private val base = "https://register.example.gov.sg/"

    private fun post(endpoint: String): Int {
        var status = 0
        runRegistry(customize = ::sandbox) { env ->
            status =
                env.ce.inTenant(OPERATOR) {
                    MockMvcBuilders
                        .webAppContextSetup(env.context.sourceApplicationContext as WebApplicationContext)
                        .build()
                        .perform(
                            MockMvcRequestBuilders
                                .post("/internal/v1/masterdata/connections")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(endpoint)),
                        ).andReturn()
                        .response.status
                }
            stored = env.count("select count(*) from masterdata.connection where tenant_id = '{tenant}'::uuid")
        }
        return status
    }

    private var stored = -1

    private fun sandbox(runner: WebApplicationContextRunner) =
        runner.withInitializer {
            it.environment.propertySources.addFirst(
                MapPropertySource("installation", mapOf("masterdata.installation-environment" to "SANDBOX")),
            )
        }

    private fun body(endpoint: String) =
        """{"externalService":"CHECK_GST_REGISTER","endpoint":"$endpoint",""" +
            """"credentialRef":"${TestSecrets.CONNECTION_CREDENTIAL_REF}","environmentTag":"SANDBOX","ownerService":"masterdata-svc"}"""

    @Test
    fun `endpoint of exactly 512 characters that is a valid URI answers 201`() {
        val url = base + "a".repeat(512 - base.length)
        assertThat(url).hasSize(512)
        assertThat(post(url)).isEqualTo(201)
    }

    @Test
    fun `endpoint of 513 characters answers 400 and stores nothing`() {
        assertThat(post(base + "a".repeat(513 - base.length))).isEqualTo(400)
        assertThat(stored).isZero()
    }

    @Test
    fun `endpoint https register example check answers 201`() {
        assertThat(post(base + "check")).isEqualTo(201)
    }

    @Test
    fun `endpoint not a uri answers 400 and stores nothing`() {
        assertThat(post("not a uri")).isEqualTo(400)
        assertThat(stored).isZero()
    }
}
