package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.syncedge.BreakGlassDoubles
import com.dynokore.gst.submission.syncedge.BreakGlassTestBeans
import com.dynokore.gst.submission.syncedge.ContractDocument
import com.dynokore.gst.submission.syncedge.Doubles
import com.dynokore.gst.submission.syncedge.Mapped
import com.dynokore.gst.submission.syncedge.Operation
import com.dynokore.gst.submission.syncedge.Report
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.serviceruntime.events.PlatformEventTypes
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.BreakGlassAction
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Enumeration
import java.util.UUID

/**
 * INV-AC-9344 (INV-REQ-896 behavior 3; owner answers INV-Q-9285 A, INV-Q-9119 A, INV-Q-9300 A): an approver of an open
 * session reaches only the six marked operations of submission-svc and each marked call is reported once. The
 * replica is fed through the service's own consumer of `BreakGlassSessionChanged` ([BreakGlassFeed]); the reporter
 * and the clock are doubles. The controllers are stubs (501), so reaching the handler is the observable use case.
 */
class BreakGlassRestrictionTest {
    private val loader = javaClass.classLoader
    private val mapper = ObjectMapper()
    private val json = EnvelopeJson(mapper)
    private val tenant = TenantId.of(BreakGlassTestBeans.TENANT_T)
    private val expiresAt = BreakGlassTestBeans.E

    private val services =
        ContractDocument
            .operations(ContractDocument.text(loader))
            .flatMap { it.callerFields }
            .filter { it != "any" && !it.startsWith("any-except:") }
            .distinct()
            .sorted()

    private val operations = ContractDocument.operations(ContractDocument.text(loader)).associateBy { it.label }

    private fun marked(): List<Triple<String, String, BreakGlassAction>> =
        listOf(
            Triple(
                "POST /internal/v1/transactions/{docUuid}/versions/{version}/replay",
                "replaySubmissions",
                BreakGlassAction.REPLAY,
            ),
            Triple("POST /v1/entities/{entityRef}/periods/{periodRef}/drain", "drainPeriod", BreakGlassAction.DRAIN),
            Triple(
                "GET /v1/entities/{entityRef}/periods/{periodRef}/readiness",
                "getReadiness",
                BreakGlassAction.READINESS,
            ),
            Triple(
                "GET /v1/entities/{entityRef}/periods/{periodRef}/readiness/checks",
                "listReadinessChecks",
                BreakGlassAction.READINESS,
            ),
            Triple(
                "GET /v1/entities/{entityRef}/periods/{periodRef}/submissions",
                "listSubmissions",
                BreakGlassAction.VIEW,
            ),
            Triple(
                "GET /v1/entities/{entityRef}/periods/{periodRef}/submissions/summary",
                "getSubmissionSummary",
                BreakGlassAction.VIEW,
            ),
        )

    private fun op(label: String): Operation = checkNotNull(operations[label]) { "no operation $label" }

    private fun serve(
        dir: Path,
        classes: ClassLoader = loader,
        block: (ConfigurableApplicationContext) -> Unit,
    ) {
        val properties =
            services
                .flatMapIndexed { index, service ->
                    listOf(
                        "service-runtime.workload-identity.callers[$index].service=$service",
                        "service-runtime.workload-identity.callers[$index].subject=sa-$service",
                    )
                }.toTypedArray() + "service-runtime.event-loops.enabled=false"
        BreakGlassDoubles.reset(BreakGlassTestBeans.BEFORE_E)
        SubmissionContext
            .startServlet(dir, listOf(BreakGlassTestBeans::class.java), properties, classes)
            .use(block)
    }

    private fun call(
        context: ConfigurableApplicationContext,
        operation: Operation,
        token: String,
    ): HttpResponse<String> {
        val port = context.environment.getProperty("local.server.port")
        val path =
            operation.template.replace(Regex("\\{([^}]+)}")) {
                operation.pathValues.getOrDefault(it.groupValues[1], "a")
            }
        val query =
            operation.query
                .takeIf { it.isNotEmpty() }
                ?.let { "?$it" }
                .orEmpty()
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port$path$query"))
        val caller =
            operation.callerFields.firstOrNull { it != "any" && !it.startsWith("any-except:") } ?: services.first()
        (operation.headers + mapOf("X-Workload-Token" to "token-$caller", "Authorization" to "Bearer $token"))
            .forEach { (name, value) -> request.header(name, value) }
        if (operation.method == "GET") {
            request.GET()
        } else {
            request.header("Content-Type", "application/json")
            request.method(operation.method, HttpRequest.BodyPublishers.ofString(operation.body))
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun refusedByMode(answer: HttpResponse<String>) =
        answer.statusCode() == FORBIDDEN && "SRT-004" in answer.body()

    private inner class Feed(
        private val context: ConfigurableApplicationContext,
    ) {
        val replica: AuthorityReplica = context.getBean(AuthorityReplica::class.java)

        fun envelope(
            status: String,
            type: String = PlatformEventTypes.BREAK_GLASS_SESSION_CHANGED,
            unlockRef: String = "BG-1",
        ): Envelope =
            Envelope(
                id = EventId(UUID.randomUUID().toString()),
                source = "/gst/access-svc",
                type = type,
                subject = unlockRef,
                time = Instant.parse("2026-11-06T10:00:00Z"),
                tenant = tenant,
                entity =
                    if (type ==
                        PlatformEventTypes.BREAK_GLASS_SESSION_CHANGED
                    ) {
                        EntityRef.TENANT_WIDE
                    } else {
                        EntityRef("E-001")
                    },
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "access-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson =
                    """{"unlockRef":"$unlockRef","tenantRef":"$tenant","status":"$status","expiresAt":"$expiresAt",
                    |"approvedBy":["P1","P2"]}
                    """.trimMargin(),
            )

        /** Delivers [envelope] to the service's own consumer bean, which reads the log of the doubles. */
        fun deliver(envelope: Envelope): PollResult {
            BreakGlassDoubles.append(json.write(envelope))
            return context.getBean("breakGlassSessionConsumer", JdbcInboxConsumer::class.java).pollOnce()
        }

        fun restricted(
            actor: String = BreakGlassTestBeans.P1,
            at: Instant = BreakGlassDoubles.now.get(),
            who: TenantId = tenant,
        ) = replica.breakGlassSessions.restrictedSession(who, actor, at)
    }

    private val readiness = "GET /v1/entities/{entityRef}/periods/{periodRef}/readiness"

    private fun caseA(
        context: ConfigurableApplicationContext,
        handlers: Set<String>,
    ) {
        marked().forEach { (label, id, action) ->
            val mapped = label in handlers
            println("INV-AC-9344 $id $label handler mapped: $mapped")
            val reports = BreakGlassDoubles.reports.size
            val reached = Doubles.reached.size
            val answer = call(context, op(label), BreakGlassTestBeans.P1_TOKEN)
            val shown = answer.body().take(BODY_SHOWN)
            (id to refusedByMode(answer) to shown) shouldBe (id to false to shown)
            (id to (BreakGlassDoubles.reports.size - reports) to shown) shouldBe (id to 1 to shown)
            BreakGlassDoubles.reports.last() shouldBe Report("BG-1", action, label, BreakGlassTestBeans.BEFORE_E)
            (id to (Doubles.reached.size - reached) to shown) shouldBe (id to (if (mapped) 1 else 0) to shown)
        }
    }

    private fun caseB(context: ConfigurableApplicationContext) {
        val reports = BreakGlassDoubles.reports.size
        val reached = Doubles.reached.size
        call(context, op("GET /v1/transactions/{docUuid}"), BreakGlassTestBeans.P1_TOKEN).statusCode() shouldBe
            FORBIDDEN
        Doubles.reached.size shouldBe reached
        BreakGlassDoubles.reports.size shouldBe reports
    }

    private fun caseD(
        context: ConfigurableApplicationContext,
        feed: Feed,
        handlers: Set<String>,
    ) {
        val reports = BreakGlassDoubles.reports.size
        val reached = Doubles.reached.size
        refusedByMode(call(context, op(readiness), BreakGlassTestBeans.P1_T2_TOKEN)) shouldBe false
        BreakGlassDoubles.reports.size shouldBe reports
        (Doubles.reached.size - reached) shouldBe (if (readiness in handlers) 1 else 0)
        feed.restricted(who = TenantId.of(BreakGlassTestBeans.TENANT_T2)) shouldBe null
    }

    private fun caseF(context: ConfigurableApplicationContext) {
        BreakGlassDoubles.denyReadiness = true
        val denied = call(context, op(readiness), BreakGlassTestBeans.P1_TOKEN)
        BreakGlassDoubles.denyReadiness = false
        denied.statusCode() shouldBe FORBIDDEN
        denied.body() shouldBe BreakGlassTestBeans.ORDINARY_DENIAL
    }

    private fun caseC(
        context: ConfigurableApplicationContext,
        feed: Feed,
    ) {
        feed.restricted() shouldBe null
        val reports = BreakGlassDoubles.reports.size
        refusedByMode(call(context, op(readiness), BreakGlassTestBeans.P1_TOKEN)) shouldBe false
        BreakGlassDoubles.reports.size shouldBe reports
        BreakGlassDoubles.denyReadiness = true
        call(context, op(readiness), BreakGlassTestBeans.P1_TOKEN).body() shouldBe BreakGlassTestBeans.ORDINARY_DENIAL
    }

    @Test
    fun `INV-AC-9344 an approver reaches only the six marked operations and each call is reported`(
        @TempDir dir: Path,
    ) {
        serve(dir) { context ->
            val feed = Feed(context)
            val handlers = Mapped.handlers(context).map { it.label }.toSet()
            val applied = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)
            // (e) first half: a valid OPEN envelope makes P1 restricted
            feed.restricted() shouldBe null
            feed.deliver(feed.envelope("OPEN")) shouldBe applied
            feed.restricted() shouldNotBe null
            caseA(context, handlers)
            caseB(context)
            caseD(context, feed, handlers)
            caseF(context)
            // (e) second half and (c): SEALED for the same unlockRef
            feed.deliver(feed.envelope("SEALED")) shouldBe applied
            caseC(context, feed)
        }
    }

    @Test
    fun `INV-AC-9344 probe the call at E is not restricted and one second before E is`(
        @TempDir dir: Path,
    ) {
        serve(dir) { context ->
            val feed = Feed(context)
            feed.deliver(feed.envelope("OPEN"))
            val replay = op(REPLAY)
            BreakGlassDoubles.now.set(expiresAt.minusSeconds(1))
            feed.restricted() shouldNotBe null
            val before = BreakGlassDoubles.reports.size
            refusedByMode(call(context, replay, BreakGlassTestBeans.P1_TOKEN)) shouldBe false
            BreakGlassDoubles.reports.size shouldBe before + 1
            BreakGlassDoubles.now.set(expiresAt)
            feed.restricted() shouldBe null
            refusedByMode(call(context, replay, BreakGlassTestBeans.P1_TOKEN)) shouldBe false
            BreakGlassDoubles.reports.size shouldBe before + 1
        }
    }

    @Test
    fun `INV-AC-9344 probe a wrong event type is a contract violation and the replica is unchanged`(
        @TempDir dir: Path,
    ) {
        serve(dir) { context ->
            val feed = Feed(context)
            val result =
                feed.deliver(feed.envelope("OPEN", type = "com.dynokore.gst.access.BreakGlassSessionChanged.v1"))
            println("INV-AC-9344 wrong type poll result $result")
            result.applied shouldBe 0
            result.deadLettered shouldBe 1
            feed.restricted() shouldBe null
            feed.replica.breakGlassSessions.held(tenant, "BG-1") shouldBe null
        }
    }

    /** A class loader that hides the contract resource (the resource removed from the classpath). */
    private class Hiding(
        parent: ClassLoader,
        private val hidden: Set<String>,
    ) : ClassLoader(parent) {
        override fun getResources(name: String): Enumeration<URL> =
            if (name in hidden) Collections.emptyEnumeration() else super.getResources(name)

        override fun getResource(name: String): URL? = if (name in hidden) null else super.getResource(name)
    }

    @Test
    fun `INV-AC-9344 probe the resource removed from the classpath refuses every operation of P1`(
        @TempDir dir: Path,
    ) {
        serve(dir, Hiding(loader, setOf("META-INF/gst/break-glass-actions.tsv"))) { context ->
            val feed = Feed(context)
            feed.deliver(feed.envelope("OPEN"))
            val answer = call(context, op(REPLAY), BreakGlassTestBeans.P1_TOKEN)
            refusedByMode(answer) shouldBe true
            BreakGlassDoubles.reports.size shouldBe 0
        }
    }

    @Test
    fun `INV-AC-9344 probe a resource line with the action view stops the start`(
        @TempDir dir: Path,
    ) {
        val bad = dir.resolve("bad")
        java.nio.file.Files
            .createDirectories(bad.resolve("META-INF/gst"))
        java.nio.file.Files.writeString(
            bad.resolve("META-INF/gst/break-glass-actions.tsv"),
            "GET\t/v1/entities/{entityRef}/periods/{periodRef}/readiness\tview\n",
        )
        val classes = java.net.URLClassLoader(arrayOf(bad.toUri().toURL()), loader)
        shouldThrow<Exception> {
            serve(
                dir.resolve("run").also {
                    java.nio.file.Files
                        .createDirectories(it)
                },
                classes,
            ) { }
        }
    }

    private companion object {
        const val REPLAY = "POST /internal/v1/transactions/{docUuid}/versions/{version}/replay"
        const val FORBIDDEN = 403
        const val NOT_IMPLEMENTED = 501
        const val BODY_SHOWN = 160
    }
}
