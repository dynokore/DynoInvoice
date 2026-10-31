package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import com.dynokore.platform.serviceruntime.replica.BreakGlassActionReporter
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionChanged
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassSessionView
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-9304 for the restricted break-glass mode (INV-Q-9119 A, INV-Q-9300 A, INV-Q-9298 B). */
class BreakGlassRestrictionFilterTest {
    @TempDir
    lateinit var dir: Path

    private val t0: Instant = Instant.parse("2026-10-31T00:00:00Z")
    private val expires: Instant = t0.plus(Duration.ofHours(4))
    private val tenantT = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000a"))
    private val tenantT2 = TenantId(UUID.fromString("0a000000-0000-0000-0000-00000000000b"))
    private var now: Instant = t0
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant() = now
        }
    private val replica = BreakGlassSessionReplica(clock)
    private val reports = mutableListOf<List<Any>>()
    private val marked = "GET\t/v1/submissions/{id}\tVIEW\nPOST\t/v1/replay\tREPLAY\n"
    private val lifecycle = "POST\t/v1/break-glass/unlock\n"

    private fun <T : Any> providerOf(
        type: Class<T>,
        bean: T?,
    ): ObjectProvider<T> =
        DefaultListableBeanFactory().apply { bean?.let { registerSingleton("b", it) } }.getBeanProvider(type)

    private fun classpath(files: Map<String, String>): ClassLoader {
        val root = Files.createTempDirectory(dir, "cp")
        files.forEach { (name, text) ->
            Files.createDirectories(root.resolve(name).parent)
            Files.writeString(root.resolve(name), text)
        }
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }

    private fun files(
        marked: String? = this.marked,
        lifecycle: String? = this.lifecycle,
    ) = buildMap {
        marked?.let { put(BreakGlassActions.RESOURCE, it) }
        lifecycle?.let { put(BreakGlassLifecycleOperations.RESOURCE, it) }
    }

    private fun filter(
        loader: ClassLoader = classpath(files()),
        reporter: BreakGlassActionReporter? =
            BreakGlassActionReporter { ref, action, target, at ->
                reports +=
                    listOf(ref, action, target, at)
            },
        view: BreakGlassSessionView = replica,
        counted: MutableList<String> = mutableListOf(),
    ): BreakGlassRestrictionFilter {
        val actions = BreakGlassActions.from(loader)
        return BreakGlassRestrictionFilter(
            view,
            providerOf(BreakGlassActionReporter::class.java, reporter),
            actions,
            BreakGlassLifecycleOperations.from(loader, actions),
            { counted += it },
            clock,
        )
    }

    private fun open(
        status: String = "OPEN",
        unlockRef: String = "BG-1",
    ) = replica.accept(
        BreakGlassSessionChanged(unlockRef, tenantT, status, expires, listOf("identity:USR-004", "identity:USR-005")),
    )

    private class Outcome(
        val response: MockHttpServletResponse,
        val chainCalls: Int,
    )

    private fun call(
        filter: BreakGlassRestrictionFilter,
        method: String,
        path: String,
        principal: EndUserPrincipal? = EndUserPrincipal("identity:USR-004", tenantT, emptySet()),
        machine: Boolean = false,
        chainStatus: Int = 200,
    ): Outcome {
        val request = MockHttpServletRequest(method, path)
        principal?.let { request.setAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE, it) }
        if (machine) {
            request.setAttribute(
                MachineTokenFilter.PRINCIPAL_ATTRIBUTE,
                Principal.machine("client-1", tenantT, MachineEntityCoverage.Entity("E1")),
            )
        }
        val response = MockHttpServletResponse()
        var calls = 0
        filter.doFilter(
            request,
            response,
            FilterChain { _, res ->
                calls++
                (res as MockHttpServletResponse).status = chainStatus
            },
        )
        return Outcome(response, calls)
    }

    @Test
    fun `a marked operation reaches the chain with one report of its action and target`() {
        open()
        val out = call(filter(), "GET", "/v1/submissions/S1")
        out.chainCalls shouldBe 1
        out.response.status shouldBe 200
        reports shouldBe listOf(listOf("BG-1", BreakGlassAction.VIEW, "GET /v1/submissions/{id}", t0))
    }

    @Test
    fun `an unmarked operation is refused 403 with no chain call, no report and one count`() {
        open()
        val counted = mutableListOf<String>()
        val out = call(filter(counted = counted), "GET", "/v1/other")
        out.response.status shouldBe 403
        out.response.contentAsString shouldContain "SRT-004"
        out.chainCalls shouldBe 0
        reports shouldBe emptyList()
        counted shouldBe listOf("break_glass_refused_total")
    }

    @Test
    fun `look-alikes of a marked template and of a method are refused`() {
        open()
        call(filter(), "GET", "/v10/submissions/S1").response.status shouldBe 403
        call(filter(), "POST", "/v1/submissions/S1").response.status shouldBe 403
        call(filter(), "GET", "/v1/submissions/S1/extra").response.status shouldBe 403
        reports shouldBe emptyList()
    }

    @Test
    fun `an actor look-alike, another tenant, expiry, a sealed session and no session are not restricted`() {
        open()
        val f = filter()
        val lookAlike = EndUserPrincipal("identity:USR-0041", tenantT, emptySet())
        call(f, "GET", "/v1/other", lookAlike).chainCalls shouldBe 1
        call(f, "GET", "/v1/other", EndUserPrincipal("identity:USR-004", tenantT2, emptySet())).chainCalls shouldBe 1
        call(f, "GET", "/v1/other", EndUserPrincipal("identity:USR-003", tenantT, emptySet())).chainCalls shouldBe 1
        now = expires.minusSeconds(1)
        call(f, "GET", "/v1/other").response.status shouldBe 403
        now = expires
        call(f, "GET", "/v1/other").chainCalls shouldBe 1
        reports shouldBe emptyList()
    }

    @Test
    fun `a sealed session and a tenant with no event do not restrict`() {
        val f = filter()
        call(f, "GET", "/v1/other").chainCalls shouldBe 1
        open("SEALED")
        open()
        call(f, "GET", "/v1/other").chainCalls shouldBe 1
    }

    @Test
    fun `a machine request, an operator-session request and a request with no principal are untouched`() {
        open()
        val f = filter()
        call(f, "GET", "/v1/other", principal = null, machine = true).chainCalls shouldBe 1
        call(
            f,
            "GET",
            "/v1/other",
            principal = EndUserPrincipal("identity:USR-004", tenantT, emptySet()),
            machine = true,
        ).chainCalls shouldBe
            1
        val session = OperatorSession("PLATFORM_SUPPORT", "CASE-1")
        val operator = EndUserPrincipal("identity:USR-004", tenantT, emptySet(), operatorSession = session)
        call(f, "GET", "/v1/other", operator).chainCalls shouldBe 1
        call(f, "GET", "/v1/other", principal = null).chainCalls shouldBe 1
        call(f, "GET", "/v1/other", EndUserPrincipal("identity:USR-004", null, emptySet())).chainCalls shouldBe 1
    }

    @Test
    fun `a marked operation whose chain answers 403 gives that 403`() {
        open()
        val out = call(filter(), "GET", "/v1/submissions/S1", chainStatus = 403)
        out.response.status shouldBe 403
        out.chainCalls shouldBe 1
    }

    @Test
    fun `a reporter that throws, or none, gives 500 SRT-010 and no chain call`() {
        open()
        val failing = filter(reporter = BreakGlassActionReporter { _, _, _, _ -> error("down") })
        val out = call(failing, "GET", "/v1/submissions/S1")
        out.response.status shouldBe 500
        out.response.contentAsString shouldContain "SRT-010"
        out.response.contentAsString shouldNotContain "down"
        out.chainCalls shouldBe 0
        val none = call(filter(reporter = null), "GET", "/v1/submissions/S1")
        none.response.status shouldBe 500
        none.chainCalls shouldBe 0
    }

    @Test
    fun `a lifecycle line reaches the chain with no report, its look-alikes are refused`() {
        open()
        val f = filter()
        val out = call(f, "POST", "/v1/break-glass/unlock")
        out.chainCalls shouldBe 1
        reports shouldBe emptyList()
        call(f, "PUT", "/v1/break-glass/unlock").response.status shouldBe 403
        call(f, "POST", "/v10/break-glass/unlock").response.status shouldBe 403
    }

    @Test
    fun `with no resource every operation of a restricted caller is refused and start-up does not fail`() {
        open()
        val f = filter(loader = classpath(files(marked = null, lifecycle = null)))
        call(f, "GET", "/v1/submissions/S1").response.status shouldBe 403
        call(f, "POST", "/v1/break-glass/unlock").response.status shouldBe 403
    }

    @Test
    fun `a malformed marker line stops the start-up naming the line and never the payload`() {
        listOf(
            "GET\t/v1/a\n",
            "GET\t/v1/a\tVIEW\tEXTRA\n",
            "GET\t/v1/a\tVIEW\n\nPOST\t/v1/b\tDRAIN\n",
            "get\t/v1/a\tVIEW\n",
            "GET\tv1/a\tVIEW\n",
            "GET\t/v1/a\tSECRET-ACTION\n",
            "GET\t/v1/a\tview\n",
        ).forEach { text ->
            val failure = shouldThrow<IllegalStateException> { BreakGlassActions.from(classpath(files(text, null))) }
            failure.message!! shouldContain BreakGlassActions.RESOURCE
            failure.message!! shouldNotContain "SECRET-ACTION"
        }
        shouldThrow<IllegalStateException> {
            BreakGlassActions.from(classpath(files("GET\t/v1/a\tVIEW\nGET\t/v1/a\tDRAIN\n", null)))
        }
    }

    @Test
    fun `a malformed lifecycle line, or one that is also a marker line, stops the start-up`() {
        listOf("POST\n", "POST\t/v1/a\tVIEW\n", "POST\ta\n", "\n\n").forEach { text ->
            shouldThrow<IllegalStateException> { filter(classpath(files(lifecycle = text))) }
        }
        val both =
            shouldThrow<IllegalStateException> {
                filter(
                    classpath(files(lifecycle = "GET\t/v1/submissions/{other}\n")),
                )
            }
        both.message!! shouldContain BreakGlassLifecycleOperations.RESOURCE
        filter(classpath(files(lifecycle = "POST\t/v1/submissions/{id}\n")))
    }
}
