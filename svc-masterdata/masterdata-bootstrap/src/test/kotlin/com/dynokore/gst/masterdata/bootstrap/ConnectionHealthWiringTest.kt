package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.masterdata.adapters.events.ConnectionCredentialExpiryReminderOutbox
import com.dynokore.gst.masterdata.adapters.events.ConnectionStatusChangedOutbox
import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID

/**
 * `getConnectionHealth`, `rotateCredential`, `ConnectionStatusChanged` and the expiry reminder (INV-T-18776) at the
 * service boundary: the real context over real PostgreSQL and the certification-kit register simulator.
 * INV-REQ-836, INV-REQ-837.
 */
class ConnectionHealthWiringTest {
    private val connections = "/internal/v1/masterdata/connections"
    private val failuresToOpen = 5

    private data class Answer(
        val status: Int,
        val body: String,
    ) {
        val node: JsonNode get() = JsonMapper.builder().build().readTree(body)
        val code: String? get() = node.get("code")?.asString()
    }

    /** The test-only overlap fixture: the shipped configuration leaves `masterdata.connections.overlap` unset. */
    private fun on(
        overlap: String? = "PT1H",
        block: (RegistryEnv) -> Unit,
    ) = runRegistry(
        customize = { runner: WebApplicationContextRunner ->
            val withEnv = runner.withPropertyValues("masterdata.installation-environment=SANDBOX")
            if (overlap == null) withEnv else withEnv.withPropertyValues("masterdata.connections.overlap=$overlap")
        },
        block = block,
    )

    private fun RegistryEnv.mvc(): MockMvc =
        MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()

    private fun RegistryEnv.call(
        method: String,
        path: String,
        actor: String = OPERATOR,
        body: String? = null,
    ): Answer =
        ce.inTenant(actor) {
            val builder =
                if (method == "POST") {
                    MockMvcRequestBuilders
                        .post(path)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body ?: "{}")
                } else {
                    MockMvcRequestBuilders.get(path)
                }
            val response = mvc().perform(builder).andReturn().response
            Answer(response.status, response.contentAsString)
        }

    private fun RegistryEnv.create(expiresIn: Duration? = null): String {
        val expiry = expiresIn?.let { ""","credentialExpiresAt":"${clock.instant().plus(it)}"""" }.orEmpty()
        val answer =
            call(
                "POST",
                connections,
                body =
                    """{"externalService":"CHECK_GST_REGISTER","endpoint":"${Simulators.register.baseUrl}""" +
                        """/lookup/${Simulators.UEN_REGISTERED}",""" +
                        """"credentialRef":"${TestSecrets.CONNECTION_CREDENTIAL_REF}",""" +
                        """"environmentTag":"SANDBOX","ownerService":"masterdata-svc"$expiry}""",
            )
        assertThat(answer.status).isEqualTo(201)
        return answer.node.get("connectionRef").asString()
    }

    private fun RegistryEnv.test(ref: String) = call("POST", "$connections/$ref/connection-tests")

    private fun RegistryEnv.health(
        ref: String,
        actor: String = OPERATOR,
    ) = call("GET", "$connections/$ref/health", actor)

    private fun RegistryEnv.rotate(
        ref: String,
        credentialRef: String,
        actor: String = OPERATOR,
        expiresAt: String? = null,
    ) = call(
        "POST",
        "$connections/$ref/credential-rotations",
        actor,
        """{"credentialRef":"$credentialRef"${expiresAt?.let { ""","credentialExpiresAt":"$it"""" }.orEmpty()}}""",
    )

    private fun RegistryEnv.rows(table: String) =
        count("select count(*) from masterdata.$table where tenant_id = '{tenant}'::uuid")

    private fun RegistryEnv.events(type: String) =
        ce.captured.envelopes.filter { it.tenant == ce.tenant && it.type == type }

    private fun RegistryEnv.statusEvents() = events(ConnectionStatusChangedOutbox.EVENT_TYPE)

    private fun RegistryEnv.reminders() = events(ConnectionCredentialExpiryReminderOutbox.EVENT_TYPE)

    /** A first success (the baseline), then repeated failures until the breaker opens; returns the connection. */
    private fun RegistryEnv.failingConnection(): String {
        val ref = create()
        assertThat(test(ref).node.get("classification").asString()).isEqualTo("SUCCESS")
        assertThat(statusEvents()).isEmpty() // the first recorded call alone publishes nothing
        Simulators.register.outage(true)
        try {
            repeat(failuresToOpen) {
                assertThat(test(ref).node.get("classification").asString()).isNotEqualTo("SUCCESS")
            }
        } finally {
            Simulators.register.outage(false)
        }
        return ref
    }

    private fun assertOpenBreakerPublishedOnce(
        env: RegistryEnv,
        ref: String,
    ) {
        val health = env.health(ref)
        assertThat(health.status).isEqualTo(200)
        assertThat(health.node.get("breakerState").asString()).isEqualTo("OPEN")
        assertThat(health.node.get("state").asString()).isEqualTo("DOWN")
        assertThat(health.node.get("successRate").asDouble()).isEqualTo(1.0 / (failuresToOpen + 1))
        assertThat(health.node.get("latencyMillis").asLong()).isGreaterThanOrEqualTo(0)
        assertThat(
            health.node
                .get("lastFailure")
                .get("classification")
                .asString(),
        ).isNotEqualTo("SUCCESS")
        assertThat(
            health.node
                .get("lastFailure")
                .get("at")
                .asString(),
        ).isNotBlank()
        assertThat(health.body).doesNotContain(TestSecrets.CONNECTION_SECRET)
        val published = env.statusEvents()
        assertThat(published).hasSize(1)
        val event = published.single()
        assertThat(event.type).isEqualTo("com.dynokore.gst.platform.ConnectionStatusChanged.v1")
        assertThat(event.instanceId).isNotBlank()
        assertThat(event.entity.isTenantWide).isTrue()
        assertThat(event.subject).isEqualTo(ref)
        val payload = JsonMapper.builder().build().readTree(event.dataJson)
        assertThat(payload.get("connection").asString()).isEqualTo(ref)
        assertThat(payload.get("state").asString()).isEqualTo("down")
        assertThat(payload.get("lastFailure").get("at").asString()).isNotBlank()
    }

    // ---- the acceptance criteria ----

    @Test
    fun `INV-AC-1885 a connection with repeated failures shows an open breaker and publishes its state change once`() =
        on { env ->
            val ref = env.failingConnection()
            assertOpenBreakerPublishedOnce(env, ref)
            env.health(ref)
            assertThat(env.statusEvents()).hasSize(1) // reading the health publishes nothing
        }

    @Test
    fun `INV-AC-8629 the health shows rate, latency, last failure and OPEN and the change is published once`() =
        on { env ->
            val ref = env.failingConnection()
            assertOpenBreakerPublishedOnce(env, ref)
            Simulators.register.outage(true)
            try {
                env.test(ref) // a further failure in the same state publishes nothing more
            } finally {
                Simulators.register.outage(false)
            }
            assertThat(env.statusEvents()).hasSize(1)
            val key = OperationPermissionKeys.CONNECTION_HEALTH_VIEW
            assertThat(OperationRoles.rolesFor(DeploymentForm.SAAS, key).keys)
                .contains("integration.engineer", "platform.engineer")
            assertThat(OperationRoles.rolesFor(DeploymentForm.PRIVATE, key).keys)
                .contains("integration.engineer", "deployment.operator")
        }

    /** Returns the number of failed calls recorded for [ref] while the expiry, the reminder and the rotation ran. */
    private fun expiryAndRotation(
        env: RegistryEnv,
        ref: String,
    ): Int {
        assertThat(env.reminders()).isEmpty()
        val job = env.context.getBean(CheckCredentialExpiryForTenants::class.java)
        job.run()
        job.run() // a second run requests nothing more
        assertThat(env.reminders()).hasSize(1)
        assertThat(
            env
                .test(ref)
                .node
                .get("classification")
                .asString(),
        ).isEqualTo("SUCCESS")
        val rotated = env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF_2)
        assertThat(rotated.status).isEqualTo(200)
        assertThat(rotated.node.get("credentialRef").asString()).isEqualTo(TestSecrets.CONNECTION_CREDENTIAL_REF_2)
        assertThat(rotated.body).doesNotContain(TestSecrets.CONNECTION_SECRET)
        assertThat(
            env
                .test(ref)
                .node
                .get("classification")
                .asString(),
        ).isEqualTo("SUCCESS") // during the overlap
        env.advance(Duration.ofHours(2))
        assertThat(
            env
                .test(ref)
                .node
                .get("classification")
                .asString(),
        ).isEqualTo("SUCCESS") // after it
        return env.count(
            "select count(*) from masterdata.call_record where tenant_id = '{tenant}'::uuid and record_ref = '$ref' " +
                "and failure_class is not null",
        )
    }

    @Test
    fun `INV-AC-845 a credential expiring in 10 days raises a reminder and rotation with overlap fails no call`() =
        on { env ->
            val ref = env.create(expiresIn = Duration.ofDays(10))
            assertThat(expiryAndRotation(env, ref)).isZero()
            assertThat(env.reminders().single().subject).isEqualTo(ref)
        }

    @Test
    fun `INV-AC-8630 an expiry reminder is requested once, with the offset 14, and no register call fails`() =
        on { env ->
            val ref = env.create(expiresIn = Duration.ofDays(10))
            val failed = expiryAndRotation(env, ref)
            val reminders = env.reminders()
            assertThat(reminders).hasSize(1)
            val event = reminders.single()
            assertThat(event.type).isEqualTo("com.dynokore.gst.platform.ConnectionCredentialExpiryReminderRequested.v1")
            assertThat(event.entity.isTenantWide).isTrue()
            assertThat(event.instanceId).isNotBlank()
            val payload = JsonMapper.builder().build().readTree(event.dataJson)
            assertThat(payload.get("connection").asString()).isEqualTo(ref)
            assertThat(payload.get("offsetDays").asInt()).isEqualTo(14)
            assertThat(payload.get("expiresAt").asString()).isNotBlank()
            assertThat(failed).isZero()
            assertThat(Simulators.register.calls()).isEqualTo(3)
        }

    // ---- the permission and configuration probes ----

    @Test
    fun `connection-health view denied answers 403 SRT-004 and no figures`() =
        on { env ->
            val ref = env.failingConnection()
            val denied = env.health(ref, actor = "IDN-OUTSIDER")
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.code).isEqualTo("SRT-004")
            assertThat(denied.body).doesNotContain("breakerState").doesNotContain("successRate").doesNotContain(ref)
        }

    @Test
    fun `credential rotation denied answers 403 SRT-004 and changes nothing`() =
        on { env ->
            val ref = env.create()
            val denied =
                env.rotate(
                    ref,
                    TestSecrets.CONNECTION_CREDENTIAL_REF_2,
                    actor = "IDN-OUTSIDER",
                    expiresAt = "2027-01-01T00:00:00Z",
                )
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.code).isEqualTo("SRT-004")
            assertThat(env.rows("credential_state")).isZero()
            assertThat(env.rows("credential_expiry")).isZero()
            val read = env.call("GET", "$connections/$ref")
            assertThat(read.node.get("credentialRef").asString()).isEqualTo(TestSecrets.CONNECTION_CREDENTIAL_REF)
        }

    @Test
    fun `a rotation with the overlap property unset is refused and the credential is unchanged`() =
        on(overlap = null) { env ->
            val ref = env.create()
            val refused = env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF_2)
            assertThat(refused.status).isEqualTo(503)
            assertThat(refused.code).isEqualTo("SRT-013")
            assertThat(env.rows("credential_state")).isZero()
            assertThat(
                env
                    .call("GET", "$connections/$ref")
                    .node
                    .get("credentialRef")
                    .asString(),
            ).isEqualTo(TestSecrets.CONNECTION_CREDENTIAL_REF)
            assertThat(
                env
                    .test(ref)
                    .node
                    .get("classification")
                    .asString(),
            ).isEqualTo("SUCCESS")
        }

    @Test
    fun `a replacement that does not resolve, the current reference and an unknown connection are refused`() =
        on { env ->
            val ref = env.create()
            assertThat(env.rotate(ref, "connections/absent").status).isEqualTo(400)
            assertThat(env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF).status).isEqualTo(400)
            assertThat(env.rotate("CON-999999", TestSecrets.CONNECTION_CREDENTIAL_REF_2).status).isEqualTo(404)
            assertThat(env.health("CON-999999").status).isEqualTo(404)
            assertThat(env.rows("credential_state")).isZero()
        }

    @Test
    fun `a repeated rotation to the same reference returns the original outcome`() =
        on { env ->
            val ref = env.create()
            val first = env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF_2)
            val again = env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF_2)
            assertThat(listOf(first.status, again.status)).containsOnly(200)
            assertThat(again.body).isEqualTo(first.body)
        }

    @Test
    fun `the expiry recorded by a rotation is shown in the health and a rotation without one removes it`() =
        on { env ->
            val ref = env.create(expiresIn = Duration.ofDays(40))
            assertThat(
                env
                    .health(ref)
                    .node
                    .get("credentialExpiresAt")
                    .asString(),
            ).isNotBlank()
            assertThat(env.rotate(ref, TestSecrets.CONNECTION_CREDENTIAL_REF_2).status).isEqualTo(200)
            val after = env.health(ref)
            assertThat(after.node.has("credentialExpiresAt")).isFalse()
            assertThat(after.node.get("overlapEndsAt").asString()).isNotBlank()
        }
}
