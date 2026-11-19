package com.dynokore.platform.serviceruntime.identity

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DegradedAllowTable
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.ReplicaFallback
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant

private val HR_TENANT = TenantId.of("0a000000-0000-0000-0000-00000000000a")
private const val HR_PATH = "/v1/entities/E1/periods/P1/filing"
private const val HR_ROWS =
    "operationId\tmethod\tpath\tpermission\n" +
        "confirmFiling\tPOST\t/v1/entities/{entityRef}/periods/{periodRef}/filing\tmachine.filing.confirm\n"

/**
 * INV-T-9258: the live authorise of a high-risk operation, whatever the replica lag (INV-REQ-1201 behaviour 7, owner
 * answers INV-Q-9243 B, INV-Q-9245 A). The fixture table is written to a temporary class path, as the contract-server
 * plugin does for a service.
 */
class HighRiskLiveAuthoriseFilterTest {
    @TempDir
    lateinit var dir: Path

    private val clock = Clock.systemUTC()
    private val meters = SimpleMeterRegistry()
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(HighRiskLiveAuthoriseFilter::class.java) as Logger
    private val asked = mutableListOf<AuthorizationRequest>()
    private var answer: () -> FallbackResult<AuthorityVerdict> = { FallbackResult.Found(AuthorityVerdict(true)) }
    private var stored = 0
    private val chain = FilterChain { _, _ -> stored++ }

    private val roles =
        OperationRoleMap {
            if (it == "filing.confirm" || it == "machine.filing.confirm") {
                mapOf("approver" to CellAllowance.PERMITTED)
            } else {
                emptyMap()
            }
        }

    @BeforeEach
    fun capture() {
        appender.start()
        logger.level = Level.INFO
        logger.addAppender(appender)
    }

    @AfterEach
    fun release() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun fallback() =
        ReplicaFallback<AuthorizationRequest, AuthorityVerdict> { key, _ ->
            asked += key
            answer()
        }

    private fun replica(
        degraded: DegradedAllowTable = DegradedAllowTable.EMPTY,
        held: Boolean = false,
    ): AuthorityReplica {
        val replica = AuthorityReplica(clock, roles, fallback = fallback(), degradedAllow = degraded)
        replica.onSnapshotComplete(0)
        if (held) {
            val grant = RoleGrant("approver", GrantScope.ENTITY, "E1", Instant.now().minusSeconds(60))
            val record = AuthorityRecord.RoleGrantRecord("g1", RoleGrantState.ACTIVE, grant)
            replica.accept(AuthorityChanged(HR_TENANT, "p1", 1, record))
            replica.observeHead(1)
        }
        return replica
    }

    private fun filter(
        replica: AuthorityReplica,
        rows: String = HR_ROWS,
    ): HighRiskLiveAuthoriseFilter {
        val file = dir.resolve("META-INF/gst/high-risk-live-calls.tsv")
        Files.createDirectories(file.parent)
        Files.writeString(file, rows)
        val loader = URLClassLoader(arrayOf(dir.toUri().toURL()), null)
        return HighRiskLiveAuthoriseFilter(replica, meters, clock, HighRiskLiveAuthoriseFilter.rows(loader))
    }

    private fun person() =
        MockHttpServletRequest("POST", HR_PATH).apply {
            requestURI = HR_PATH
            setAttribute(
                EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE,
                EndUserPrincipal("p1", HR_TENANT, emptySet()),
            )
        }

    private fun machine(path: String = HR_PATH) =
        MockHttpServletRequest("POST", path).apply {
            requestURI = path
            setAttribute(
                MachineTokenFilter.PRINCIPAL_ATTRIBUTE,
                Principal.machine(
                    "wf",
                    HR_TENANT,
                    MachineEntityCoverage.Entity("E1"),
                    "filing-workflow",
                    "filing-workflow",
                ),
            )
        }

    private fun run(
        filter: HighRiskLiveAuthoriseFilter,
        request: MockHttpServletRequest,
    ): MockHttpServletResponse = MockHttpServletResponse().also { filter.doFilter(request, it, chain) }

    private fun count(result: String) =
        meters
            .find(HighRiskLiveAuthoriseFilter.METER_NAME)
            .tags("operation", "confirmFiling", "result", result)
            .counter()
            ?.count()
            ?: 0.0

    private fun envelopeCode(response: MockHttpServletResponse) = response.contentAsString

    @Test
    fun `INV-AC-16549 unreachable access-svc is 503 SRT-013 and nothing stored, any degraded-allow row`() {
        answer = { FallbackResult.Unavailable }
        val degraded = DegradedAllowTable(setOf("POST" to "/v1/entities/{entityRef}/periods/{periodRef}/filing"))
        val filter = filter(replica(degraded, held = true))
        for (request in listOf(person(), machine())) {
            val response = run(filter, request)
            response.status shouldBe 503
            envelopeCode(response) shouldContain "\"code\":\"SRT-013\""
            response.contentType shouldBe "application/problem+json;charset=UTF-8"
        }
        stored shouldBe 0
        count("UNREACHABLE") shouldBe 2.0
        count("ALLOW") shouldBe 0.0
    }

    @Test
    fun `INV-AC-16549 an exception from the fallback is 503 with nothing stored`() {
        answer = { error("boom") }
        run(filter(replica(held = true)), person()).status shouldBe 503
        stored shouldBe 0
    }

    @Test
    fun `INV-AC-9051 replica ALLOW at lag 0 and access-svc DENY is refused 403`() {
        answer = { FallbackResult.Found(AuthorityVerdict(false)) }
        val replica = replica(held = true)
        val response = run(filter(replica), person())
        response.status shouldBe 403
        envelopeCode(response) shouldContain "\"code\":\"SRT-004\""
        stored shouldBe 0
        asked.size shouldBe 1
        count("DENY") shouldBe 1.0
    }

    @Test
    fun `INV-AC-9051 the replica is empty and access-svc answers ALLOW so the call is accepted`() {
        val response = run(filter(replica(held = false)), person())
        response.status shouldBe 200
        stored shouldBe 1
        asked.size shouldBe 1
        count("ALLOW") shouldBe 1.0
    }

    @Test
    fun `INV-AC-9051 a person asks the key without the machine prefix, a machine the key as written`() {
        val filter = filter(replica())
        run(filter, person())
        run(filter, machine())
        asked.map { it.action } shouldBe listOf("filing.confirm", "machine.filing.confirm")
        asked.map { it.entityRef } shouldBe listOf("E1", "E1")
        asked.map { it.tenant } shouldBe listOf(HR_TENANT, HR_TENANT)
        asked.map { it.resourcePeriod } shouldBe listOf(null, null)
    }

    @Test
    fun `INV-AC-9051 a look-alike path is not a listed operation and passes untouched`() {
        val filter = filter(replica())
        for ((method, path) in listOf(
            "POST" to "$HR_PATH/draft",
            "GET" to HR_PATH,
            "POST" to "/v1/entities/E1/periods/P1/filingX",
            "POST" to "/v1/entities/E1/periods/P1",
        )) {
            val request = MockHttpServletRequest(method, path).apply { requestURI = path }
            run(filter, request).status shouldBe 200
        }
        stored shouldBe 4
        asked.size shouldBe 0
        meters.find(HighRiskLiveAuthoriseFilter.METER_NAME).counters().size shouldBe 0
    }

    @Test
    fun `INV-AC-16549 a request with no bound principal is 401 and a person with no tenant is 503`() {
        val filter = filter(replica())
        run(filter, MockHttpServletRequest("POST", HR_PATH).apply { requestURI = HR_PATH }).status shouldBe 401
        val noTenant =
            MockHttpServletRequest("POST", HR_PATH).apply {
                requestURI = HR_PATH
                val person = EndUserPrincipal("p1", null, emptySet())
                setAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE, person)
            }
        run(filter, noTenant).status shouldBe 503
        stored shouldBe 0
        asked.size shouldBe 0
        count("UNREACHABLE") shouldBe 1.0
    }

    @Test
    fun `INV-AC-16549 a blank entityRef is 503 and access-svc is not asked`() {
        val rows = HR_ROWS.replace("{entityRef}", "{ref}")
        val filter = filter(replica(), rows)
        run(filter, person()).status shouldBe 503
        stored shouldBe 0
        asked.size shouldBe 0
    }

    @Test
    fun `INV-AC-9051 one log line per call holds operation, principalKind and result only`() {
        val filter = filter(replica())
        run(filter, person())
        answer = { FallbackResult.Unavailable }
        run(filter, machine())
        val lines = appender.list.map { it.formattedMessage }
        lines shouldBe
            listOf(
                "high_risk_live_authorise operation=confirmFiling principalKind=HUMAN result=ALLOW",
                "high_risk_live_authorise operation=confirmFiling principalKind=MACHINE result=UNREACHABLE",
            )
        for (line in lines) {
            line shouldNotContain "E1"
            line shouldNotContain "p1"
        }
    }

    @Test
    fun `INV-AC-9051 a malformed table stops the start-up naming resource and line number only`() {
        val header = HighRiskLiveAuthoriseFilter.HEADER + "\n"
        val cases =
            listOf(
                "operationId\tmethod\tpath\n" to "line 1",
                header + "a\tPOST\t/v1/a\n" to "line 2",
                header + "b\tPOST\t/v1/b\tx.y\na\tPOST\t/v1/a\tx.y\n" to "line 3",
                header + "a\tPOST\t/v1/a\tx.y\na\tGET\t/v1/a\tx.y\n" to "line 3",
                header + "a\tPOST\t/v1/a\tmachine.\n" to "line 2",
                header + "a\tpost\t/v1/a\tx.y\n" to "line 2",
            )
        for ((text, line) in cases) {
            val failure = shouldThrow<IllegalStateException> { filter(replica(), text) }
            failure.message!! shouldContain HighRiskLiveAuthoriseFilter.RESOURCE
            failure.message!! shouldContain line
            failure.message!! shouldNotContain "x.y"
        }
    }

    @Test
    fun `INV-AC-9051 no resource or only the header gives no row`() {
        val empty = URLClassLoader(arrayOf(dir.toUri().toURL()), null)
        HighRiskLiveAuthoriseFilter.rows(empty).size shouldBe 0
        val file = dir.resolve("META-INF/gst/high-risk-live-calls.tsv")
        Files.createDirectories(file.parent)
        Files.writeString(file, HighRiskLiveAuthoriseFilter.HEADER + "\n")
        HighRiskLiveAuthoriseFilter.rows(URLClassLoader(arrayOf(dir.toUri().toURL()), null)).size shouldBe 0
    }

    @Test
    fun `INV-AC-16549 no AuthorityReplica bean stops a non-empty table, an empty one registers none`() {
        val resources =
            org.springframework.core.io.DefaultResourceLoader(
                URLClassLoader(arrayOf(dir.toUri().toURL()), null),
            )
        val config = EndUserIdentityAutoConfiguration()
        val none =
            org.springframework.beans.factory.support
                .DefaultListableBeanFactory()

        fun <T : Any> provider(type: Class<T>) = none.getBeanProvider(type)
        config
            .highRiskLiveAuthoriseFilter(
                provider(AuthorityReplica::class.java),
                provider(io.micrometer.core.instrument.MeterRegistry::class.java),
                provider(Clock::class.java),
                resources,
            ).isEnabled shouldBe false
        val file = dir.resolve("META-INF/gst/high-risk-live-calls.tsv")
        Files.createDirectories(file.parent)
        Files.writeString(file, HR_ROWS)
        val failure =
            shouldThrow<IllegalStateException> {
                config.highRiskLiveAuthoriseFilter(
                    provider(AuthorityReplica::class.java),
                    provider(io.micrometer.core.instrument.MeterRegistry::class.java),
                    provider(Clock::class.java),
                    resources,
                )
            }
        failure.message!! shouldContain "no AuthorityReplica bean"
    }
}
