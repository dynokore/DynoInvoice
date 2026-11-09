package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.application.rate.RateStore
import com.dynokore.gst.masterdata.application.rate.SaveResult
import com.dynokore.gst.masterdata.domain.rate.ExchangeRate
import com.dynokore.gst.masterdata.domain.rate.Provenance
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Parks `latest` of the rate store of two racing writers until both have read, so that the race is deterministic. */
internal object RateRace {
    @Volatile var barrier: CyclicBarrier? = null
}

private const val INSTALLATION_PROPERTY = "masterdata.installation-environment"

private class RacingRateStore(
    private val delegate: RateStore,
) : RateStore by delegate {
    override fun latest(
        source: String,
        baseCurrency: String,
        quoteCurrency: String,
        date: LocalDate,
    ): ExchangeRate? =
        delegate.latest(source, baseCurrency, quoteCurrency, date).also {
            RateRace.barrier?.await(BARRIER_SECONDS, TimeUnit.SECONDS)
        }

    private companion object {
        const val BARRIER_SECONDS = 20L
    }
}

private class RateRaceProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is RateStore && bean !is RacingRateStore) RacingRateStore(bean) else bean
}

/**
 * `createConnection`, `getConnection`, `testConnection` and `enterRate` (INV-T-18774) at the service boundary: the real
 * context over real PostgreSQL and the certification-kit register simulator. INV-REQ-506, INV-REQ-834, INV-REQ-835.
 */
class ConnectionWiringTest {
    private val connections = "/internal/v1/masterdata/connections"
    private val rates = "/internal/v1/masterdata/exchange-rates"

    private data class Answer(
        val status: Int,
        val body: String,
    ) {
        val node: JsonNode get() = JsonMapper.builder().build().readTree(body)
        val code: String? get() = node.get("code")?.asString()
    }

    /** [installation] is set as the process environment would set it: exactly as given, or not at all when null. */
    private fun withInstallation(
        installation: String?,
        racing: Boolean = false,
    ): (WebApplicationContextRunner) -> WebApplicationContextRunner =
        { runner ->
            val configured =
                runner.withInitializer { context ->
                    if (installation != null) {
                        context.environment.propertySources.addFirst(
                            MapPropertySource("installation", mapOf(INSTALLATION_PROPERTY to installation)),
                        )
                    }
                }
            if (racing) configured.withBean(RateRaceProcessor::class.java, { RateRaceProcessor() }) else configured
        }

    private fun on(
        installation: String?,
        racing: Boolean = false,
        block: (RegistryEnv) -> Unit,
    ) = runRegistry(customize = withInstallation(installation, racing), block = block)

    private fun RegistryEnv.mvc(): MockMvc =
        MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()

    private fun RegistryEnv.call(
        method: String,
        path: String,
        actor: String = OPERATOR,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
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
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = mvc().perform(builder).andReturn().response
            Answer(response.status, response.contentAsString)
        }

    private fun createBody(
        tag: String,
        credentialRef: String = TestSecrets.CONNECTION_CREDENTIAL_REF,
        service: String = "CHECK_GST_REGISTER",
    ) = """{"externalService":"$service","endpoint":"${endpoint()}","credentialRef":"$credentialRef",""" +
        """"environmentTag":"$tag","ownerService":"masterdata-svc"}"""

    private fun endpoint() = "${Simulators.register.baseUrl}/lookup/${Simulators.UEN_REGISTERED}"

    private fun RegistryEnv.create(
        tag: String,
        credentialRef: String = TestSecrets.CONNECTION_CREDENTIAL_REF,
        service: String = "CHECK_GST_REGISTER",
    ): String {
        val answer = call("POST", connections, body = createBody(tag, credentialRef, service))
        assertThat(answer.status).isEqualTo(201)
        return answer.node.get("connectionRef").asString()
    }

    private fun RegistryEnv.test(
        ref: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ) = call("POST", "$connections/$ref/connection-tests", headers = headers, body = body)

    private fun RegistryEnv.rateRows(
        source: String,
        date: String,
    ): Int =
        count(
            "select count(*) from masterdata.exchange_rate where tenant_id = '{tenant}'::uuid " +
                "and source = '$source' and rate_date = '$date'",
        )

    private fun RegistryEnv.connectionRows(): Int =
        count("select count(*) from masterdata.connection where tenant_id = '{tenant}'::uuid")

    private fun enterBody(
        source: String,
        date: String,
        evidence: String?,
        rate: String = "1.35800000",
    ) = """{"currency":"USD","date":"$date","source":"$source","rate":"$rate",""" +
        (evidence?.let { """"evidenceRef":"$it"}""" } ?: """"evidenceRef":""}""")

    // ---- the acceptance criteria ----

    @Test
    fun `INV-AC-1512 a manual rate without evidence is refused and one with evidence is stored with its provenance`() =
        on("SANDBOX") { env ->
            val source = unique("MAS")
            val refused = env.call("POST", rates, body = enterBody(source, "2026-03-20", ""))
            assertThat(refused.status).isEqualTo(400)
            assertThat(refused.code).isEqualTo("SRT-001")
            assertThat(env.rateRows(source, "2026-03-20")).isZero()
            val stored = env.call("POST", rates, body = enterBody(source, "2026-03-20", "EVD-3010"))
            assertThat(stored.status).isEqualTo(201)
            assertThat(
                stored.node
                    .get("provenance")
                    .get("kind")
                    .asString(),
            ).isEqualTo("MANUAL")
            assertThat(
                stored.node
                    .get("provenance")
                    .get("evidenceRef")
                    .asString(),
            ).isEqualTo("EVD-3010")
            assertThat(env.rateRows(source, "2026-03-20")).isEqualTo(1)
        }

    @Test
    fun `INV-AC-8625 enterRate without evidence leaves 0 records and with evidence stores one manual record`() =
        on("SANDBOX") { env ->
            val source = unique("MAS")
            assertThat(env.call("POST", rates, body = enterBody(source, "2026-03-21", "")).status).isEqualTo(400)
            assertThat(env.call("POST", rates, body = enterBody(source, "2026-03-21", "  ")).status).isEqualTo(400)
            assertThat(env.rateRows(source, "2026-03-21")).isZero()
            val stored = env.call("POST", rates, body = enterBody(source, "2026-03-21", "EVD-1"))
            assertThat(stored.status).isEqualTo(201)
            assertThat(env.rateRows(source, "2026-03-21")).isEqualTo(1)
            assertThat(
                env.count(
                    "select count(*) from masterdata.exchange_rate where tenant_id = '${env.ce.tenant}'::uuid " +
                        "and source = '$source' and provenance = 'MANUAL_ENTRY' and evidence_ref = 'EVD-1'",
                ),
            ).isEqualTo(1)
        }

    @Test
    fun `INV-AC-8627 the record shows exactly the contract properties with no secret and a workspace is refused`() =
        on("SANDBOX") { env ->
            val ref = env.create("PRODUCTION")
            val read = env.call("GET", "$connections/$ref")
            assertThat(read.status).isEqualTo(200)
            assertThat(read.node.propertyNames().toList())
                .containsExactlyInAnyOrder(
                    "connectionRef",
                    "externalService",
                    "endpoint",
                    "credentialRef",
                    "environmentTag",
                    "ownerService",
                )
            assertThat(read.body.split(TestSecrets.CONNECTION_SECRET).size - 1).isZero()
            Simulators.register.reset()
            val refused = env.test(ref)
            assertThat(refused.status).isEqualTo(403)
            assertThat(refused.code).isEqualTo(ErrorCode.MDM_018.code)
            assertThat(refused.body).doesNotContain(TestSecrets.CONNECTION_SECRET)
            assertThat(Simulators.register.calls()).isZero()
        }

    @Test
    fun `INV-AC-1883 a workspace using a PRODUCTION credential is refused 403 MDM-018, simulator not called`() =
        on("SANDBOX") { env ->
            val ref = env.create("PRODUCTION")
            Simulators.register.reset()
            val refused = env.test(ref)
            assertThat(refused.status).isEqualTo(403)
            assertThat(refused.code).isEqualTo("MDM-018")
            assertThat(Simulators.register.calls()).isZero()
            assertThat(env.call("GET", "$connections/$ref").body).doesNotContain(TestSecrets.CONNECTION_SECRET)
        }

    @Test
    fun `INV-AC-1884 a connection test with a wrong credential reference returns authentication refused`() =
        on("SANDBOX") { env ->
            val ref = env.create("SANDBOX", credentialRef = "connections/wrong-reference", service = "DIRECTORY")
            val answer = env.test(ref)
            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.node.get("classification").asString()).isEqualTo("AUTHENTICATION_REFUSED")
            assertThat(answer.body).doesNotContain("Exception").doesNotContain("no secret bound")
        }

    @Test
    fun `INV-AC-8628 a classified result is returned and carries the class and no raw error text`() =
        on("SANDBOX") { env ->
            val ref = env.create("SANDBOX", credentialRef = "connections/wrong-reference")
            val answer = env.test(ref)
            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.node.get("classification").asString()).isEqualTo("AUTHENTICATION_REFUSED")
            assertThat(answer.node.get("detail").asString()).matches("^[A-Z][A-Z0-9_-]{1,31}$")
            assertThat(answer.body).doesNotContain("Exception").doesNotContain("wrong-reference")
            Simulators.register.reset()
            val ok = env.create("SANDBOX", service = "RATE_SOURCE")
            val success = env.test(ok)
            assertThat(success.node.get("classification").asString()).isEqualTo("SUCCESS")
            assertThat(Simulators.register.calls()).isEqualTo(1)
        }

    // ---- the permission probes ----

    @Test
    fun `INV-AC-8627 connection view denied answers 403 SRT-004 and the record is not returned`() =
        on("SANDBOX") { env ->
            val ref = env.create("SANDBOX")
            val denied = env.call("GET", "$connections/$ref", actor = "IDN-OUTSIDER")
            assertThat(denied.status).isEqualTo(403)
            assertThat(denied.code).isEqualTo("SRT-004")
            assertThat(denied.body).doesNotContain(ref).doesNotContain("masterdata-svc")
        }

    @Test
    fun `INV-AC-8627 every operation refuses a denied caller 403 SRT-004 with no state change`() =
        on("SANDBOX") { env ->
            val ref = env.create("SANDBOX")
            val before = env.connectionRows()
            val source = unique("MAS")
            Simulators.register.reset()
            val created = env.call("POST", connections, "IDN-OUTSIDER", createBody("SANDBOX", service = "DIRECTORY"))
            val tested = env.call("POST", "$connections/$ref/connection-tests", "IDN-OUTSIDER")
            val entered = env.call("POST", rates, "IDN-OUTSIDER", enterBody(source, "2026-03-22", "EVD-9"))
            assertThat(listOf(created, tested, entered).map { it.status to it.code }).containsOnly(403 to "SRT-004")
            assertThat(env.connectionRows()).isEqualTo(before)
            assertThat(env.rateRows(source, "2026-03-22")).isZero()
            assertThat(Simulators.register.calls()).isZero()
        }

    @Test
    fun `INV-AC-8627 the look-alike permission key connection views is not a key of the catalogue`() {
        val roles = { key: String ->
            runCatching { OperationRoles.rolesFor(DeploymentForm.SAAS, key) }.getOrNull().orEmpty()
        }
        listOf(
            OperationPermissionKeys.CONNECTION_VIEW,
            OperationPermissionKeys.CONNECTION_CONFIGURE,
            OperationPermissionKeys.CONNECTION_TEST,
            OperationPermissionKeys.EXCHANGE_RATE_ENTER,
        ).forEach { assertThat(roles(it)).`as`(it).isNotEmpty() }
        listOf("connection.views", "Connection.view", "connection.view ", "connection.*", "").forEach {
            assertThat(roles(it)).`as`("look-alike '$it'").isEmpty()
        }
    }

    // ---- the caller environment ----

    @Test
    fun `INV-AC-1883 installation SANDBOX with a PRODUCTION record is refused 403 MDM-018`() =
        on("SANDBOX") { env ->
            val ref = env.create("PRODUCTION")
            Simulators.register.reset()
            assertThat(env.test(ref).let { it.status to it.code }).isEqualTo(403 to "MDM-018")
            assertThat(Simulators.register.calls()).isZero()
        }

    @Test
    fun `INV-AC-8627 installation PRODUCTION with a SANDBOX record is refused 403 MDM-018`() =
        on("PRODUCTION") { env ->
            val ref = env.create("SANDBOX")
            Simulators.register.reset()
            assertThat(env.test(ref).let { it.status to it.code }).isEqualTo(403 to "MDM-018")
            assertThat(Simulators.register.calls()).isZero()
        }

    @Test
    fun `INV-AC-8628 installation and record both SANDBOX answer 200 with the classified result`() =
        on("SANDBOX") { env ->
            val ref = env.create("SANDBOX")
            val answer = env.test(ref)
            assertThat(answer.status).isEqualTo(200)
            assertThat(answer.node.get("connectionRef").asString()).isEqualTo(ref)
            assertThat(answer.node.get("classification").asString()).isEqualTo("SUCCESS")
        }

    @Test
    fun `INV-AC-1883 an unset installation environment answers 500 MDM-017 and the simulator is not called`() =
        on(null) { env ->
            val ref = env.create("SANDBOX")
            Simulators.register.reset()
            val answer = env.test(ref)
            assertThat(answer.status to answer.code).isEqualTo(500 to "MDM-017")
            assertThat(answer.node.has("detail")).isFalse()
            assertThat(Simulators.register.calls()).isZero()
        }

    @Test
    fun `INV-AC-1883 the look-alikes production and PRODUCTION with a trailing space answer 500 MDM-017`() {
        listOf("production", "PRODUCTION ", " SANDBOX", "SANDBOX\n", "").forEach { text ->
            on(text) { env ->
                val ref = env.create("SANDBOX")
                Simulators.register.reset()
                val answer = env.test(ref)
                assertThat(answer.status to answer.code).`as`("'$text'").isEqualTo(500 to "MDM-017")
                assertThat(Simulators.register.calls()).isZero()
            }
        }
    }

    @Test
    fun `INV-AC-1883 a request header and a body property naming PRODUCTION are ignored`() =
        on("SANDBOX") { env ->
            val ref = env.create("PRODUCTION")
            Simulators.register.reset()
            val answer =
                env.test(
                    ref,
                    headers = mapOf("X-Environment" to "PRODUCTION", "X-Installation-Environment" to "PRODUCTION"),
                    body = """{"callerEnvironment":"PRODUCTION"}""",
                )
            assertThat(answer.status to answer.code).isEqualTo(403 to "MDM-018")
            assertThat(Simulators.register.calls()).isZero()
        }

    // ---- the wire codes ----

    @Test
    fun `INV-AC-8627 an unknown environment tag on createConnection is 400 MDM-016 and nothing is stored`() =
        on("SANDBOX") { env ->
            val before = env.connectionRows()
            listOf("STAGING", "production", "PRODUCTION ", "").forEach { tag ->
                val answer = env.call("POST", connections, body = createBody(tag))
                assertThat(answer.status to answer.code).`as`("tag '$tag'").isEqualTo(400 to "MDM-016")
            }
            assertThat(env.connectionRows()).isEqualTo(before)
            val other = env.call("POST", connections, body = createBody("SANDBOX", service = "NOT_A_SERVICE"))
            assertThat(other.status to other.code).isEqualTo(400 to "SRT-001")
        }

    @Test
    fun `INV-AC-8627 the four wire pairs come from the pinned catalogue and the look-alikes are not rows`() {
        assertThat(ErrorCode.fromCode("MDM-016")).isEqualTo(ErrorCode.MDM_016)
        assertThat(ErrorCode.fromCode("MDM-017")).isEqualTo(ErrorCode.MDM_017)
        assertThat(ErrorCode.fromCode("MDM-018")).isEqualTo(ErrorCode.MDM_018)
        assertThat(ErrorCode.fromCode("SRT-008")).isNotNull()
        assertThat(ErrorCode.fromCode("MDM-0160")).isNull()
        assertThat(ErrorCode.fromCode("SRT-0080")).isNull()
        assertThat(ErrorCode.fromCode("mdm-016")).isNull()
    }

    @Test
    fun `INV-AC-8625 the envelope of a refusal carries the catalogue row of its code`() =
        on("SANDBOX") { env ->
            val answer = env.call("POST", connections, body = createBody("STAGING"))
            assertThat(answer.node.get("type").asString()).isEqualTo("https://errors.invoice.internal/MDM-016")
            assertThat(answer.node.get("severity").asString()).isEqualTo(ErrorCode.MDM_016.severity.name)
            assertThat(answer.node.get("remediationOwner").asString()).isEqualTo(ErrorCode.MDM_016.owner)
            assertThat(answer.node.get("correlationId").asString()).isNotBlank()
        }

    // ---- the rate store ----

    @Test
    fun `INV-AC-8625 two saves of one key return Saved then RevisionConflict and the first row is unchanged`() =
        on("SANDBOX") { env ->
            val store = env.context.getBean(RateStore::class.java)
            val tx = env.context.getBean(TransactionRunner::class.java)
            val source = unique("SRC")
            val day = LocalDate.of(2026, 4, 1)

            fun rate(
                value: String,
                revision: Int = 1,
                date: LocalDate = day,
                src: String = source,
            ) = ExchangeRate(src, "USD", "SGD", date, BigDecimal(value), Provenance.MANUAL_ENTRY, "EVD-A", revision)
            val first = env.ce.inTenant { tx.inTransaction { store.save(rate("1.30000000")) } }
            val second = env.ce.inTenant { tx.inTransaction { store.save(rate("9.99000000")) } }
            assertThat(first).isEqualTo(SaveResult.Saved)
            assertThat(second).isEqualTo(SaveResult.RevisionConflict)
            val kept = env.ce.inTenant { tx.inTransaction { store.history(source, "USD", "SGD", day) } }
            assertThat(kept.map { it.rate.toPlainString() }).containsExactly("1.30000000")
            val others =
                listOf(
                    rate("1.31000000", revision = 2),
                    rate("1.32000000", date = day.plusDays(1)),
                    rate("1.33000000", src = unique("OTHER")),
                ).map { env.ce.inTenant { tx.inTransaction { store.save(it) } } }
            assertThat(others).containsOnly(SaveResult.Saved)
            val otherTenant = env.anotherTenantSave(store, tx, rate("1.34000000"))
            assertThat(otherTenant).isEqualTo(SaveResult.Saved)
        }

    private fun RegistryEnv.anotherTenantSave(
        store: RateStore,
        tx: TransactionRunner,
        rate: ExchangeRate,
    ): SaveResult = ce.anotherTenant().inTenant { tx.inTransaction { store.save(rate) } }

    @Test
    fun `INV-AC-8625 two parallel enterRate calls for one key answer one 201 and one 409 SRT-008`() =
        on("SANDBOX", racing = true) { env ->
            val source = unique("MAS")
            RateRace.barrier = CyclicBarrier(2)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val answers =
                    (1..2)
                        .map { n ->
                            pool.submit<Answer> {
                                env.call("POST", rates, body = enterBody(source, "2026-05-01", "EVD-$n", "1.4$n"))
                            }
                        }.map { it.get(60, TimeUnit.SECONDS) }
                assertThat(answers.map { it.status }).containsExactlyInAnyOrder(201, 409)
                assertThat(answers.single { it.status == 409 }.code).isEqualTo("SRT-008")
                assertThat(env.rateRows(source, "2026-05-01")).isEqualTo(1)
                RateRace.barrier = null
                val retry = env.call("POST", rates, body = enterBody(source, "2026-05-01", "EVD-3", "1.43"))
                assertThat(retry.status).isEqualTo(201)
                assertThat(
                    env.count(
                        "select count(distinct revision) from masterdata.exchange_rate " +
                            "where tenant_id = '${env.ce.tenant}'::uuid and source = '$source'",
                    ),
                ).isEqualTo(2)
            } finally {
                RateRace.barrier = null
                pool.shutdownNow()
            }
        }
}
