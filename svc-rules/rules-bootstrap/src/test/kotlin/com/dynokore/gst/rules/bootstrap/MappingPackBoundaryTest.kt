package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.RefusingHolderCopyPort
import com.dynokore.gst.rules.adapters.packs.RefusingPackActionAuthority
import com.dynokore.gst.rules.adapters.packs.RefusingPackApprovalDecisionPort
import com.dynokore.gst.rules.adapters.packs.RefusingRateSourcePort
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyRequest
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.PackEvent
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.gst.rules.served.TENANT_A
import com.dynokore.gst.rules.served.USER_A_TOKEN
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"
private const val CONSOLE = "console-bff"
private const val ALT = "-alt"
private const val PACKS = "/internal/v1/rules/mapping-packs"
private const val ENTITY = "E-1"
private const val USER_B_TOKEN = "Bearer token-user-b"
private val TENANT_B = TenantId.of("0b000000-0000-0000-0000-00000000000b")

/** Counts and switches of the allowing fakes (probe only). */
object MappingPortProbe {
    val authorityCalls = AtomicInteger()
    val holderRequests = CopyOnWriteArrayList<HolderCopyRequest>()

    @Volatile var writeOutboxRow = false

    fun reset() {
        authorityCalls.set(0)
        holderRequests.clear()
        writeOutboxRow = false
    }
}

/** Two end users of tenant A and the workload tokens of the test: `user-a` authors, `user-b` approves. */
@TestConfiguration(proxyBeanMethods = false)
class MappingDoubles {
    @Bean
    fun mappingEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            when (authorization) {
                USER_A_TOKEN -> EndUserPrincipal("user-a", TENANT_A, emptySet())
                USER_B_TOKEN -> EndUserPrincipal("user-b", TENANT_A, emptySet())
                else -> null
            }
        }

    @Bean
    fun mappingWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified("subject-of-" + value.removePrefix("wl-"))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }
}

/** The allowing fakes that replace the four refusing defaults by bean name; registered before the service. */
@TestConfiguration(proxyBeanMethods = false)
class MappingAllowingPorts {
    @Bean
    fun packActionAuthority(emitter: PackEventEmitter): PackActionAuthority =
        PackActionAuthority { _, _, _ ->
            MappingPortProbe.authorityCalls.incrementAndGet()
            if (MappingPortProbe.writeOutboxRow) {
                emitter.emit(
                    listOf(PackEvent.RulePackActivated("p", "v", LocalDate.of(2026, 1, 1))),
                    EventContext(
                        TENANT_A,
                        EntityRef("entity-1"),
                        null,
                        null,
                        "agg",
                        null,
                        CorrelationId("corr"),
                        CausationId("corr"),
                    ),
                )
            }
            true
        }

    @Bean
    fun packApprovalDecisionPort(): PackApprovalDecisionPort =
        PackApprovalDecisionPort { _, _, _, _, _ -> PackApprovalDecision.Allow(false, null, "OPTION-1", emptySet()) }

    @Bean
    fun rateSourcePort(): RateSourcePort = RateSourcePort { _, _ -> emptyList() }

    @Bean
    fun holderCopyPort(): HolderCopyPort =
        HolderCopyPort { request ->
            MappingPortProbe.holderRequests.add(request)
            HolderCopyResult.Written
        }
}

/** What the two test classes share: the HTTP calls, the table snapshot and the fixtures of the probe login. */
abstract class MappingPackSupport(
    private val port: Int,
) {
    protected val mapper = JsonMapper()
    private val client = HttpClient.newHttpClient()
    private val probe: TenancyProbe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    protected fun call(
        method: String,
        path: String,
        body: String? = null,
        user: String? = USER_A_TOKEN,
        workload: String? = workloadToken(CONSOLE),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.header("Content-Type", "application/json")
        if (method == "POST") builder.header("Idempotency-Key", UUID.randomUUID().toString())
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        workload?.let { builder.header("X-Workload-Token", it) }
        user?.let { builder.header("Authorization", it) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    protected fun ok(
        response: HttpResponse<String>,
        status: Int = 200,
    ): JsonNode {
        withClue(response.body()) { response.statusCode() shouldBe status }
        return json(response)
    }

    private inline fun withClue(
        clue: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(clue, block)

    protected fun draft(
        label: String,
        user: String = USER_A_TOKEN,
    ): String = ok(call("POST", PACKS, """{"label":"$label","entityRef":"$ENTITY"}""", user), 201).get("packId").asString()

    protected fun mapCode(
        packId: String,
        code: String = "STD",
        iras: String = "SR",
        sourceClass: String = "PRIMARY",
    ) = call(
        "POST",
        "$PACKS/$packId/entries",
        """{"internalTaxCode":"$code","irasCode":"$iras","sourceClass":"$sourceClass","entityRef":"$ENTITY"}""",
    )

    protected fun submit(packId: String) = call("POST", "$PACKS/$packId/submissions?entityRef=$ENTITY")

    protected fun approve(
        packId: String,
        user: String = USER_B_TOKEN,
    ) = call("POST", "$PACKS/$packId/approval?entityRef=$ENTITY", user = user)

    protected fun activate(
        packId: String,
        from: String,
        user: String = USER_B_TOKEN,
    ) = call("POST", "$PACKS/$packId/activation", """{"effectiveFrom":"$from","entityRef":"$ENTITY"}""", user)

    /** Draft, map one PRIMARY entry, dry run (fixture), submit, approve by another user, activate. */
    protected fun fullFlow(
        label: String,
        from: String,
    ): String {
        val packId = draft(label)
        ok(mapCode(packId), 201)
        deltaRow(TENANT_A, label, "COMPLETE")
        ok(submit(packId))
        ok(approve(packId))
        ok(activate(packId, from))
        return packId
    }

    // ---- fixtures of the probe login (rules_runtime on the same database) ----

    protected fun value(
        tenant: TenantId,
        sql: String,
        vararg binds: Any?,
    ): Any? = probe.inTenant(tenant) { fetchValue(sql, *binds) }

    protected fun rows(
        tenant: TenantId,
        sql: String,
    ): List<Map<String, Any?>> = probe.inTenant(tenant) { fetchRows(sql) }

    /** A dry-run delta row the fixture inserts, because `dryRun` is INV-T-4974. */
    protected fun deltaRow(
        tenant: TenantId,
        version: String,
        state: String,
    ): String =
        value(
            tenant,
            "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, completed_at, " +
                "changed_count) values (?::uuid, ?::uuid, 'MAPPING', ?, ?, 'user-a', " +
                "case when ? = 'COMPLETE' then clock_timestamp() end, case when ? = 'COMPLETE' then 3 end) returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            version,
            state,
            state,
            state,
        ).toString()

    /** A pack of tenant A as a draft, SUBMITTED or APPROVED, with one PRIMARY entry; returns its pack id. */
    protected fun seedPack(
        version: String,
        status: String = "DRAFT",
        tenant: TenantId = TENANT_A,
    ): String {
        val packId = "MAP-$version"
        value(
            tenant,
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "min_engine_version, registered_by, label, entity_ref) values (?::uuid, ?::uuid, ?, 'MAPPING', ?, " +
                "'TENANT_MAPPING', 'DRAFT', '1.0.0', 'tenancy-probe', ?, '$ENTITY') returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            packId,
            version,
            version,
        )
        value(
            tenant,
            "insert into rules.pack_entry_source (id, tenant_id, pack_type, pack_version, entry_id, source_class, " +
                "internal_tax_code, iras_code) values (?::uuid, ?::uuid, 'MAPPING', ?, 'STD', 'PRIMARY', 'STD', 'SR') " +
                "returning id",
            UUID.randomUUID().toString(),
            tenant.toString(),
            version,
        )
        listOf("SUBMITTED", "APPROVED")
            .takeWhile { status != "DRAFT" }
            .forEach { step ->
                value(tenant, "update rules.pack_registry set status = '$step' where pack_version = ? returning id", version)
                if (step == status) return packId
            }
        return packId
    }

    /** Every table of the schema as tenant A and the platform see it under row-level security. */
    protected fun snapshot(): Map<String, List<String>> {
        val platform = TenantId(UUID.fromString(PLATFORM))
        val tables =
            probe
                .fetchRows(
                    "select table_name from information_schema.tables " +
                        "where table_schema = 'rules' and table_type = 'BASE TABLE' order by 1",
                ).map { it["table_name"] as String }
        return tables.associateWith { table ->
            listOf(TENANT_A, platform).flatMap { tenant ->
                probe.inTenant(tenant) {
                    fetchRows("select t::text as r from rules.\"$table\" t order by 1").map { "$tenant ${it["r"]}" }
                }
            }
        }
    }

    protected fun registryRow(packId: String): List<Map<String, Any?>> =
        rows(TENANT_A, "select * from rules.pack_registry where pack_id = '$packId'")

    companion object {
        fun registerCatalogue(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            val codes =
                listOf("SR", "ZR").joinToString(",") {
                    """{"code":"$it","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
                        """"sourceClass":"PRIMARY","sourceReference":"invented"}"""
                }
            val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
            val platform = TenantId(UUID.fromString(PLATFORM))
            // the database is shared by the test classes of the JVM: the catalogue is registered once
            if (probe.inTenant(platform) { fetchValue("select count(*) from rules.pack_registry where pack_version = 'cat-4972'") } == 1L) {
                return
            }
            probe.inTenant(platform) {
                fetchValue(
                    "select rules.register_platform_pack(?::uuid, 'code-catalogue-pack', 'CODE_CATALOGUE', 'cat-4972', " +
                        "'ARMED', '2025-01-01'::date, null, '1.0.0', null)",
                    UUID.randomUUID().toString(),
                )
                fetchValue(
                    "select rules.register_platform_pack(?::uuid, 'code-catalogue-pack', 'CODE_CATALOGUE', 'cat-4972', " +
                        "'2025-01-01'::date, null, '1.0.0', ?::jsonb, ?::jsonb, ?, ?::jsonb, 'anchor')",
                    UUID.randomUUID().toString(),
                    "{}",
                    "{}",
                    byteArrayOf(1),
                    """{"complete":true,"codes":[$codes]}""",
                )
            }
        }
    }
}

/**
 * The mapping pack operations at the service boundary, on real PostgreSQL, with the allowing fakes in place of the four
 * refusing defaults (INV-REQ-380, INV-REQ-381, INV-REQ-499; INV-AC-1509, INV-AC-1652, INV-AC-1653, INV-AC-1654).
 */
@SpringBootTest(
    classes = [MappingDoubles::class, MappingAllowingPorts::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE",
        "service-runtime.workload-identity.callers[1].service=$CONSOLE$ALT",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CONSOLE$ALT",
    ],
)
class MappingPackBoundaryTest(
    @Autowired private val context: ApplicationContext,
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
    @Autowired private val tenants: TenantContext,
    @Autowired private val tx: TransactionRunner,
    @Autowired private val db: org.jooq.DSLContext,
) : MappingPackSupport(port) {
    private var counter = 0

    private fun label() = "2026.${++counter}-${UUID.randomUUID().toString().take(6)}"

    @Test
    fun `INV-AC-1652 a version signed off by another user is activated and its approval record is exported`() {
        val label = label()
        val packId = fullFlow(label, "2026-01-01")
        val pack = ok(call("GET", "$PACKS/$packId?entityRef=$ENTITY"))
        pack.get("status").asString() shouldBe "ACTIVE"
        pack.get("effectiveFrom").asString() shouldBe "2026-01-01"
        val approval = pack.get("approvals").last()
        approval.get("approvedBy").asString() shouldBe "user-b" // the user ID of the verified token, never a role
        val stored = rows(TENANT_A, "select * from rules.mapping_pack_approval where pack_version = '$label'").single()
        stored["approved_by"] shouldBe "user-b"
        stored["dry_run_delta_id"].toString() shouldBe
            value(TENANT_A, "select id from rules.dry_run_delta where pack_version = ?", label).toString()
        stored["approved_at"].toString().take(MINUTE) shouldBe
            approval
                .get("approvedAt")
                .asString()
                .let { java.time.OffsetDateTime.parse(it).toInstant().toString() }
                .take(MINUTE)
        value(TENANT_A, "select registered_by from rules.pack_registry where pack_id = ?", packId) shouldBe "user-a"
        value(TENANT_A, "select activated_by from rules.pack_registry where pack_id = ?", packId) shouldBe "user-b"
        MappingPortProbe.holderRequests.last().versionLabel shouldBe label
    }

    @Test
    fun `the author is refused as approver and nothing is stored`() {
        val label = label()
        val packId = draft(label)
        ok(mapCode(packId), 201)
        deltaRow(TENANT_A, label, "COMPLETE")
        ok(submit(packId))
        val before = snapshot()
        val answer = approve(packId, USER_A_TOKEN)
        answer.statusCode() shouldBe 403
        answer.body() shouldContain "SRT-004"
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-1653 activating a draft or a submitted version is refused and its row is unchanged`() {
        val label = label()
        val packId = draft(label)
        ok(mapCode(packId), 201)
        val draftRow = registryRow(packId)
        activate(packId, "2026-01-01").statusCode() shouldBe 409
        registryRow(packId) shouldBe draftRow
        deltaRow(TENANT_A, label, "COMPLETE")
        ok(submit(packId))
        val submittedRow = registryRow(packId)
        activate(packId, "2026-01-01").statusCode() shouldBe 409
        registryRow(packId) shouldBe submittedRow
        // look-alike that must pass: the same call after approval
        ok(approve(packId))
        ok(activate(packId, "2026-01-01")).get("status").asString() shouldBe "ACTIVE"
    }

    private fun refusedWithoutQualifyingDelta(
        version: String,
        arrange: (String) -> Unit,
    ) {
        val draftPack = seedPack("$version-s")
        val submitted = seedPack("$version-a", "SUBMITTED")
        arrange("$version-s")
        arrange("$version-a")
        val before = snapshot()
        withClueOf("submitForApproval") { submit(draftPack).statusCode() shouldBe 409 }
        withClueOf("approvePack") { approve(submitted).statusCode() shouldBe 409 }
        snapshot() shouldBe before
    }

    private fun withClueOf(
        clue: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(clue, block)

    @Test
    fun `INV-AC-1654 no delta row refuses submit and approve`() = refusedWithoutQualifyingDelta("n0") {}

    @Test
    fun `INV-AC-1654 a RUNNING delta refuses submit and approve`() =
        refusedWithoutQualifyingDelta("n1") { deltaRow(TENANT_A, it, "RUNNING") }

    @Test
    fun `INV-AC-1654 a SAMPLE_UNAVAILABLE delta refuses submit and approve`() =
        refusedWithoutQualifyingDelta("n2") { deltaRow(TENANT_A, it, "SAMPLE_UNAVAILABLE") }

    @Test
    fun `INV-AC-1654 a COMPLETE delta of another pack version refuses submit and approve`() =
        refusedWithoutQualifyingDelta("2026.3") {
            deltaRow(TENANT_A, it.replace("2026.3", "2026.30"), "COMPLETE") // look-alike version 2026.30 for 2026.3
        }

    @Test
    fun `INV-AC-1654 a COMPLETE delta of another tenant refuses submit and approve`() =
        refusedWithoutQualifyingDelta("n4") { deltaRow(TENANT_B, it, "COMPLETE") }

    @Test
    fun `INV-AC-1654 a COMPLETE delta older than the last entry change refuses submit and approve`() =
        refusedWithoutQualifyingDelta("n5") {
            deltaRow(TENANT_A, it, "COMPLETE")
            value(TENANT_A, "update rules.pack_registry set content_changed_at = clock_timestamp() where pack_version = ? returning id", it)
        }

    @Test
    fun `INV-AC-1654 a qualifying COMPLETE delta is accepted and the approval stores its id`() {
        val draftPack = seedPack("q1-s")
        val submitted = seedPack("q1-a", "SUBMITTED")
        deltaRow(TENANT_A, "q1-s", "COMPLETE")
        val delta = deltaRow(TENANT_A, "q1-a", "COMPLETE")
        ok(submit(draftPack)).get("status").asString() shouldBe "SUBMITTED"
        ok(approve(submitted)).get("status").asString() shouldBe "APPROVED"
        value(TENANT_A, "select dry_run_delta_id from rules.mapping_pack_approval where pack_version = 'q1-a'").toString() shouldBe
            delta
    }

    @Test
    fun `INV-AC-1509 a drafted pack is stored as a MAPPING pack and nothing here changes a severity override pack`() {
        val packId = draft(label())
        value(TENANT_A, "select pack_type from rules.pack_registry where pack_id = ?", packId) shouldBe "MAPPING"
        value(TENANT_A, "select layer from rules.pack_registry where pack_id = ?", packId) shouldBe "TENANT_MAPPING"
        rows(TENANT_A, "select pack_id from rules.pack_registry where pack_type <> 'MAPPING' and registered_by = 'user-a'") shouldBe
            emptyList()
    }

    @Test
    fun `getMappingPack counts the entries and listMappingPackEntries pages them with their source class`() {
        val packId = draft(label())
        ok(mapCode(packId, "A", "SR", "PRIMARY"), 201)
        ok(mapCode(packId, "B", "SR", "SECONDARY"), 201)
        ok(mapCode(packId, "C", "ZR", "INFERRED"), 201)
        val pack = ok(call("GET", "$PACKS/$packId?entityRef=$ENTITY"))
        listOf("entryCount", "nonPrimaryCount", "unacceptedNonPrimaryCount").map { pack.get(it).asInt() } shouldBe listOf(3, 2, 2)
        ok(
            call("POST", "$PACKS/$packId/entries/B/source-class-acceptance", """{"rationale":"seen","expiresOn":"2099-01-01","entityRef":"$ENTITY"}""", USER_B_TOKEN),
        ).get("accepted").asBoolean() shouldBe true
        ok(call("GET", "$PACKS/$packId?entityRef=$ENTITY")).get("unacceptedNonPrimaryCount").asInt() shouldBe 1
        val first = ok(call("GET", "$PACKS/$packId/entries?limit=2&entityRef=$ENTITY"))
        entryIds(first) shouldBe listOf("A", "B")
        first.get("nextCursor").asString() shouldBe "B"
        val second = ok(call("GET", "$PACKS/$packId/entries?limit=2&cursor=B"))
        entryIds(second) shouldBe listOf("C")
        second.has("nextCursor") shouldBe false
        ok(call("GET", "$PACKS/$packId/entries?sourceClass=INFERRED")).get("items").size() shouldBe 1
        entryIds(ok(call("GET", "$PACKS/$packId/entries?accepted=true"))) shouldBe listOf("B")
        call("GET", "$PACKS/$packId?entityRef=E-2").statusCode() shouldBe 403
    }

    private fun entryIds(page: JsonNode): List<String> =
        page.get("items").let { items -> (0 until items.size()).map { items.get(it).get("entryId").asString() } }

    private fun resolvedVersionAt(date: String): String? =
        tenants.bind(TENANT_A, "mapping-test") {
            tx.inTransaction {
                ArmedPackLookup(db)
                    .resolve(PackType("MAPPING"), TENANT_A, LocalDate.parse(date))
                    ?.manifest
                    ?.version
                    ?.value
            }
        }

    private fun entryRows(label: String) = rows(TENANT_A, "select * from rules.pack_entry_source where pack_version = '$label' order by entry_id")

    private fun reproducible(year: Int) {
        val v1 = "repro-v1-${UUID.randomUUID().toString().take(6)}"
        val v2 = "repro-v2-${UUID.randomUUID().toString().take(6)}"
        fullFlow(v1, "$year-01-01")
        val entriesBefore = entryRows(v1)
        val v1Row = rows(TENANT_A, "select * from rules.pack_registry where pack_version = '$v1'")
        fullFlow(v2, "$year-07-01")
        value(TENANT_A, "select supersedes from rules.pack_registry where pack_version = ?", v2) shouldBe v1
        rows(TENANT_A, "select * from rules.pack_registry where pack_version = '$v1'") shouldBe v1Row // v1 is not modified
        resolvedVersionAt("$year-03-01") shouldBe v1
        entryRows(v1) shouldBe entriesBefore
        resolvedVersionAt("$year-07-01") shouldBe v2
    }

    @Test
    fun `INV-AC-395 re-applying the recorded version after a newer one is active gives the same codes`() = reproducible(2027)

    @Test
    fun `INV-AC-1655 regeneration part - the recorded mapping version stays resolvable and unchanged after a newer one`() =
        reproducible(2028)

    // ---- refused caller leaves nothing stored (INV-Q-12122) ----

    private fun refusedLeavesNothing(
        method: String,
        path: String,
        body: String?,
        usesCase: Boolean = true,
    ) {
        val before = snapshot()
        val calls = MappingPortProbe.authorityCalls.get()
        val wrong = call(method, path, body, workload = workloadToken(CONSOLE + ALT))
        wrong.statusCode() shouldBe 403
        wrong.body() shouldContain "SRT-004"
        call(method, path, body, workload = null).statusCode() shouldBe 401
        MappingPortProbe.authorityCalls.get() shouldBe calls
        snapshot() shouldBe before
        // the named caller is admitted and reaches the use case: the refusal is specific to the caller
        val named = call(method, path, body)
        if (usesCase) {
            MappingPortProbe.authorityCalls.get() shouldBe calls + 1
        } else {
            (named.statusCode() != 403 && named.statusCode() != 401) shouldBe true
        }
    }

    private fun entryBody(code: String) = """{"internalTaxCode":"$code","irasCode":"SR","sourceClass":"PRIMARY","entityRef":"$ENTITY"}"""

    @Test
    fun `draftMappingPack refuses a caller the edge does not name and leaves nothing stored`() =
        refusedLeavesNothing("POST", PACKS, """{"label":"${label()}","entityRef":"$ENTITY"}""")

    @Test
    fun `getMappingPack refuses a caller the edge does not name and leaves nothing stored`() =
        refusedLeavesNothing("GET", "$PACKS/${draft(label())}", null, usesCase = false)

    @Test
    fun `listMappingPackEntries refuses a caller the edge does not name and leaves nothing stored`() =
        refusedLeavesNothing("GET", "$PACKS/${draft(label())}/entries", null, usesCase = false)

    @Test
    fun `mapTaxCode refuses a caller the edge does not name and leaves nothing stored`() =
        refusedLeavesNothing("POST", "$PACKS/${draft(label())}/entries", entryBody("ALT1"))

    @Test
    fun `acceptSourceClassEntry refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = draft(label())
        ok(mapCode(packId, "S1", "SR", "SECONDARY"), 201)
        refusedLeavesNothing(
            "POST",
            "$PACKS/$packId/entries/S1/source-class-acceptance",
            """{"rationale":"seen","expiresOn":"2099-01-01","entityRef":"$ENTITY"}""",
        )
    }

    @Test
    fun `submitForApproval refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = seedPack("rc-s")
        deltaRow(TENANT_A, "rc-s", "COMPLETE")
        refusedLeavesNothing("POST", "$PACKS/$packId/submissions?entityRef=$ENTITY", null)
    }

    @Test
    fun `approvePack refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = seedPack("rc-a", "SUBMITTED")
        deltaRow(TENANT_A, "rc-a", "COMPLETE")
        refusedLeavesNothing("POST", "$PACKS/$packId/approval?entityRef=$ENTITY", null)
    }

    @Test
    fun `activateMappingPack refuses a caller the edge does not name and leaves nothing stored`() {
        val packId = seedPack("rc-v", "APPROVED")
        refusedLeavesNothing("POST", "$PACKS/$packId/activation", """{"effectiveFrom":"2026-01-01","entityRef":"$ENTITY"}""")
    }

    @Test
    fun `probe - the comparison can fail around a call of the named caller that writes one outbox row`() {
        val before = snapshot()
        MappingPortProbe.writeOutboxRow = true
        try {
            ok(call("POST", PACKS, """{"label":"${label()}","entityRef":"$ENTITY"}"""), 201)
        } finally {
            MappingPortProbe.writeOutboxRow = false
        }
        val after = snapshot()
        (after == before) shouldBe false
        after.getValue("outbox").size shouldBe before.getValue("outbox").size + 1
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}

/**
 * With only the refusing defaults in place the four commands are refused and every table of the schema is equal
 * before and after; the allowing fakes of [MappingPackBoundaryTest] make the same calls succeed, so the refusal is the
 * default and not a defect of the path (INV-Q-14647 A, INV-Q-14650 A, INV-Q-14651 A, INV-Q-14652 A).
 */
@SpringBootTest(
    classes = [MappingDoubles::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$CONSOLE",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$CONSOLE",
        "service-runtime.workload-identity.callers[1].service=$CONSOLE$ALT",
        "service-runtime.workload-identity.callers[1].subject=subject-of-$CONSOLE$ALT",
    ],
)
class MappingPackDefaultsTest(
    @Autowired private val context: ApplicationContext,
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
) : MappingPackSupport(port) {
    @Test
    fun `the four defaults are the refusing classes`() {
        context.getBean(PackActionAuthority::class.java)::class.java shouldBe RefusingPackActionAuthority::class.java
        context.getBean(PackApprovalDecisionPort::class.java)::class.java shouldBe
            RefusingPackApprovalDecisionPort::class.java
        context.getBean(RateSourcePort::class.java)::class.java shouldBe RefusingRateSourcePort::class.java
        context.getBean(HolderCopyPort::class.java)::class.java shouldBe RefusingHolderCopyPort::class.java
    }

    @Test
    fun `draftMappingPack and mapTaxCode are refused with only the defaults and nothing is stored`() {
        val before = snapshot()
        call("POST", PACKS, """{"label":"d-1","entityRef":"$ENTITY"}""").statusCode() shouldBe 403
        val packId = seedPack("df-m")
        val seeded = snapshot()
        val answer =
            call("POST", "$PACKS/$packId/entries", """{"internalTaxCode":"X","irasCode":"SR","sourceClass":"PRIMARY","entityRef":"$ENTITY"}""")
        answer.statusCode() shouldBe 403
        snapshot() shouldBe seeded
        (seeded == before) shouldBe false // the fixture itself changed the tables: the comparison is not vacuous
    }

    @Test
    fun `approvePack and activateMappingPack are refused with only the defaults and nothing is stored`() {
        val submitted = seedPack("df-a", "SUBMITTED")
        val approved = seedPack("df-v", "APPROVED")
        deltaRow(TENANT_A, "df-a", "COMPLETE")
        val before = snapshot()
        approve(submitted).statusCode() shouldBe 403
        activate(approved, "2026-01-01").statusCode() shouldBe 403
        snapshot() shouldBe before
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}

private const val MINUTE = 16
