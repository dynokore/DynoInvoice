package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.adapters.web.CompileObserver
import com.dynokore.gst.rules.adapters.web.CompiledPackHold
import com.dynokore.gst.rules.adapters.web.RegulatoryPackArmingMapping
import com.dynokore.gst.rules.application.AcknowledgePackDeltaResult
import com.dynokore.gst.rules.application.PackCurrencyStore
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.PolicyChanged
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.serviceruntime.replica.PolicySource
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.mock.env.MockEnvironment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name

private const val CALLER = "console-bff"
private const val ARM_KEY = "regulatory-pack.arm"
private const val ACK_KEY = "regulatory-pack.acknowledge"
private const val IMPORT_KEY = "regulatory-pack.import-verify"
private const val APPROVE_KEY = "regulatory-pack.approve"
private const val TYPE = "CALENDAR"
private const val SCHEMATRON = "SCHEMATRON"
private const val EFFECTIVE = "2027-01-01"
private val HOLDERS = listOf("rules-svc", "payload-svc", "tenancy-svc", "reconciliation-svc", "notification-svc")
private const val SVRL = "http://purl.oclc.org/dsdl/svrl"

/** Counts what the arming path asks of the pack currency monitor and what the registry said at that moment. */
object CurrencyCalls {
    val statuses: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
    val compiles = AtomicInteger()
}

@TestConfiguration(proxyBeanMethods = false)
class ArmingFixtures {
    /** Counts the evaluations of the currency monitor (every `packActivated()` ends in one `record`). */
    @Bean
    @Primary
    fun countingCurrencyStore(
        dsl: org.jooq.DSLContext,
        platform: com.dynokore.platform.tenancykernel.PlatformTenantBinding,
        transactions: com.dynokore.platform.tenancykernel.TransactionRunner,
        emitter: com.dynokore.gst.rules.application.PackEventEmitter,
    ): PackCurrencyStore {
        val real = com.dynokore.gst.rules.adapters.persistence.JooqPackCurrencyStore(dsl, platform, transactions, emitter)
        return object : PackCurrencyStore by real {
            override fun record(
                day: LocalDate,
                state: com.dynokore.gst.rules.domain.pack.PackCurrencyState,
                publish: Boolean,
            ) {
                real.record(day, state, publish)
                CurrencyCalls.statuses +=
                    DriverManager
                        .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
                        .use { c ->
                            c.createStatement().use { st ->
                                st.executeQuery("select string_agg(distinct status, ',') from rules.pack_registry").use {
                                    it.next()
                                    it.getString(1) ?: ""
                                }
                            }
                        }
            }
        }
    }

    @Bean
    fun countingCompiles(): CompileObserver = CompileObserver { CurrencyCalls.compiles.incrementAndGet() }
}

/** The harness: real PostgreSQL, the replica fixture and the served operations; one instance per test. */
abstract class ArmingHarness(
    protected val port: Int,
    protected val replica: AuthorityReplica,
    protected val clock: MovableClock,
    protected val context: ApplicationContext,
    protected val policy: PolicyReplica,
) {
    protected val http: HttpClient = HttpClient.newHttpClient()
    protected val tenant = TenantId(UUID.randomUUID())
    private val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    companion object {
        val versions = AtomicLong(0)
        val epoch = AtomicLong(1)
        val dates = AtomicLong(0)
        val dir: Path = Files.createTempDirectory("rules-arming-bundles")

        fun register(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            registry.add("service-runtime.workload-identity.callers[0].service") { CALLER }
            registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf(CALLER) }
            registry.add("rules.pack-source.directory") { dir.toString() }
        }
    }

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        CurrencyCalls.statuses.clear()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    protected fun roles(
        form: DeploymentForm,
        key: String,
    ): Set<String> = OperationRoles.rolesFor(form, key).keys

    protected fun grant(
        user: String,
        role: String,
        at: TenantId = tenant,
    ) {
        replica.accept(
            AuthorityChanged(
                at,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user-$role",
                    RoleGrantState.ACTIVE,
                    RoleGrant(role, GrantScope.TENANT, at.toString(), clock.instant().minus(Duration.ofHours(1)), null),
                ),
                epoch.get(),
            ),
        )
    }

    protected fun <T> superuser(block: (Connection) -> T): T =
        DriverManager
            .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
            .use(block)

    protected fun scalar(
        sql: String,
        vararg binds: String,
    ): String? =
        superuser { c ->
            c.prepareStatement(sql).use { ps ->
                binds.forEachIndexed { i, b -> ps.setString(i + 1, b) }
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    protected fun exec(
        sql: String,
        vararg binds: String?,
    ) = superuser { c ->
        c.prepareStatement(sql).use { ps ->
            binds.forEachIndexed { i, b -> ps.setString(i + 1, b) }
            ps.executeUpdate()
        }
    }

    /** Every table of the schema `rules` with its rows as text. */
    protected fun snapshot(): Map<String, List<String>> =
        superuser { c ->
            val names = mutableListOf<String>()
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "select table_name from information_schema.tables " +
                            "where table_schema = 'rules' and table_type = 'BASE TABLE' order by 1",
                    ).use { while (it.next()) names += it.getString(1) }
            }
            names.associateWith { name ->
                c.createStatement().use { st ->
                    st.executeQuery("select to_jsonb(t)::text from rules.\"$name\" t order by 1").use { rs ->
                        buildList { while (rs.next()) add(rs.getString(1)) }
                    }
                }
            }
        }

    protected fun version() = "t" + UUID.randomUUID().toString().take(8)

    /** An APPROVED platform pack of [type] with `effective_from` [effective]; no artefact. */
    protected fun seedApproved(
        type: String = TYPE,
        effective: String = EFFECTIVE,
        status: String = "STAGED",
    ): String {
        val v = version()
        probe.inTenant(TenantId.PLATFORM) {
            fetchValue(
                "select rules.register_platform_pack(?::uuid, ?, ?, ?, ?, ?::date, null, '1.0.0', null)",
                UUID.randomUUID().toString(),
                "$type-pack",
                type,
                v,
                status,
                effective,
            )
            if (status == "STAGED") fetchValue("select rules.approve_platform_pack(?, ?)", type, v)
        }
        return v
    }

    protected fun status(
        type: String,
        v: String,
    ) = scalar("select status from rules.pack_registry where pack_type = ? and pack_version = ?", type, v)

    protected fun instanceAck(
        type: String,
        v: String,
        instance: String,
        outcome: String = "LOADED",
    ) = exec(
        "insert into rules.pack_readiness_ack (pack_type, pack_version, instance, outcome, failure_reason, received_at) " +
            "values (?, ?, ?, ?, ?, clock_timestamp())",
        type,
        v,
        instance,
        outcome,
        if (outcome == "FAILED") "VERIFICATION_FAILED" else null,
    )

    protected fun ackAll(
        type: String,
        v: String,
        except: String? = null,
    ) = HOLDERS.filter { it != except }.forEach { instanceAck(type, v, it) }

    /** A COMPLETE delta of [who] for the pack, delivered [daysAgo] days before the injected clock's date. */
    protected fun delta(
        who: TenantId,
        type: String,
        v: String,
        daysAgo: Long = 0,
    ) = exec(
        "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, completed_at, " +
            "changed_count) values (?::uuid, ?::uuid, ?, ?, 'COMPLETE', 'u-dry', ?::timestamptz, 0)",
        UUID.randomUUID().toString(),
        who.toString(),
        type,
        v,
        clock.instant().minus(Duration.ofDays(daysAgo)).toString(),
    )

    protected fun reviews(v: String) =
        scalar(
            "select coalesce(string_agg(tenant_id::text || ':' || reviewed, ',' order by tenant_id), '') " +
                "from rules.pack_activation_review where pack_version = ?",
            v,
        )

    protected fun call(
        path: String,
        user: String?,
        body: String?,
        token: String? = workloadToken(CALLER),
        at: TenantId = tenant,
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs/$path"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
        if (user != null) builder.header("Authorization", "Bearer $user|$at|")
        if (token != null) builder.header("X-Workload-Token", token)
        val publisher = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body)
        return http.send(builder.POST(publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    protected fun arm(
        user: String?,
        type: String,
        v: String,
        date: String = EFFECTIVE,
        token: String? = workloadToken(CALLER),
    ) = call("$type/$v/arming", user, """{"effectiveFrom":"$date"}""", token)

    protected fun acknowledge(
        user: String?,
        type: String,
        v: String,
        at: TenantId = tenant,
        token: String? = workloadToken(CALLER),
    ) = call("$type/$v/acknowledgement", user, null, token, at)

    protected fun refused(
        answer: HttpResponse<String>,
        http: Int,
        code: String,
    ) {
        answer.statusCode() shouldBe http
        answer.body() shouldContain code
    }

    /** The version a document dated [date] resolves: the latest ARMED platform version of [type] in force. */
    protected fun resolved(
        type: String,
        date: String,
        vararg among: String,
    ) = scalar(
        "select pack_version from rules.pack_registry where pack_type = ? and status = 'ARMED' and " +
            "effective_from <= ?::date and pack_version = any(string_to_array(?, ',')) " +
            "order by effective_from desc limit 1",
        type,
        date,
        among.joinToString(","),
    )

    private fun sha(bytes: ByteArray) =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun stylesheet() =
        """<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:svrl="$SVRL">
          <xsl:template match="/"><svrl:schematron-output/></xsl:template></xsl:stylesheet>"""

    private fun file(name: String): String {
        val bytes = stylesheet().toByteArray()
        return """{"name":"$name","sha256":"${sha(bytes)}","content":"${Base64.getEncoder().encodeToString(bytes)}"}"""
    }

    /** Imports a signed Schematron bundle of two stylesheets; returns the staged version. */
    protected fun importSchematron(
        user: String,
        effective: String = EFFECTIVE,
    ): String {
        val v = version()
        val contents = """{"version":"1.0","files":[${file("a.xsl")},${file("b.xslt")}]}""".toByteArray()
        val manifest =
            (
                """{"packId":"$SCHEMATRON-pack","packType":"$SCHEMATRON","version":"$v","effectiveFrom":"$effective",""" +
                    """"effectiveTo":null,"minEngineVersion":"1.0.0"}"""
            ).toByteArray()
        val checksums = """{"contents":"${sha(contents)}"}""".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            linkedMapOf(
                "manifest.json" to manifest,
                "checksums.json" to checksums,
                "contents.json" to contents,
                "signature.p7s" to TestTrain.key.sign(manifest + checksums),
            ).forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        val bytes = out.toByteArray()
        Files.write(dir.resolve(sha(bytes)), bytes)
        val answer = call("import", user, """{"packType":"$SCHEMATRON","version":"$v","bundleHash":"sha256:${sha(bytes)}"}""")
        answer.statusCode() shouldBe 201
        return v
    }

    /** A Schematron version taken through the import, the dry run and the approval by a second user. */
    protected fun approvedSchematron(effective: String = EFFECTIVE): String {
        grant("u-importer", roles(DeploymentForm.PRIVATE, IMPORT_KEY).first())
        grant("u-approver", roles(DeploymentForm.PRIVATE, APPROVE_KEY).first())
        val v = importSchematron("u-importer", effective)
        delta(TenantId.PLATFORM, SCHEMATRON, v)
        call("$SCHEMATRON/$v/approval", "u-approver", null).statusCode() shouldBe 200
        return v
    }

    protected fun setPolicy(
        who: TenantId,
        vp: String,
        value: String,
    ) = policy.accept(
        PolicyChanged(
            PolicyScope.tenant(who),
            vp,
            value,
            LocalDate.of(2020, 1, 1),
            PolicySource.PROFILE,
            null,
            "gst.platform.policy-changed.v1",
            versions.incrementAndGet(),
        ),
    )

    /** A date no other case of the class uses, so that the registry's resolution by date is unambiguous. */
    protected fun uniqueDate(): String = LocalDate.of(2040, 1, 1).plusDays(dates.incrementAndGet()).toString()

    protected fun armerOf(form: DeploymentForm) = roles(form, ARM_KEY).single()
}

/** The SaaS form, window PT15M from `application.yaml`: the clock never moves here, so the window stays open. */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, ArmingFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Suppress("TooManyFunctions", "LargeClass") // one case per refusal and per fence outcome of the contract
class RegulatoryPackArmingBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ArmingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "saas" }
        }
    }

    private fun grantArmer(user: String) = grant(user, armerOf(DeploymentForm.SAAS))

    @Test
    fun `INV-AC-1903 three tenants record reviewed true, false and false and the arming succeeds`() {
        val effective = LocalDate.now(clock).minusDays(1).toString()
        val v = seedApproved(effective = effective)
        ackAll(TYPE, v)
        val acked = TenantId(UUID.randomUUID())
        val late = TenantId(UUID.randomUUID())
        val notice = TenantId(UUID.randomUUID())
        listOf(acked, late, notice).forEach { delta(it, TYPE, v, daysAgo = 30) }
        exec(
            "insert into rules.pack_delta_ack (tenant_id, pack_type, pack_version, acknowledged_by, acknowledged_at) " +
                "values (?::uuid, ?, ?, 'u-ack', clock_timestamp())",
            acked.toString(),
            TYPE,
            v,
        )
        setPolicy(notice, "VP-39", "ACTIVATE_WITH_NOTICE")
        grantArmer("u-armer")
        val answer = arm("u-armer", TYPE, v, effective)
        answer.body() shouldContain "ARMED"
        reviews(v) shouldBe
            listOf(acked to true, late to false, notice to false)
                .sortedBy { it.first.toString() }
                .joinToString(",") { "${it.first}:${it.second}" }
    }

    /** One tenant with a COMPLETE delta, past its window, [policyValue] as VP-39 when given: the stored review row. */
    private fun recordedReview(policyValue: String?): String? {
        val effective = LocalDate.now(clock).minusDays(1).toString()
        val v = seedApproved(effective = effective)
        ackAll(TYPE, v)
        delta(tenant, TYPE, v, daysAgo = 30)
        if (policyValue != null) setPolicy(tenant, "VP-39", policyValue)
        grantArmer("u-armer")
        arm("u-armer", TYPE, v, effective).statusCode() shouldBe 200
        return reviews(v)
    }

    @Test
    fun `INV-AC-1904 an unreviewed window that expired records the activation as unreviewed (record part)`() {
        recordedReview(null) shouldBe "$tenant:false"
    }

    @Test
    fun `INV-AC-2286 the unreviewed activation of an expired window is recorded for the tenant (record part)`() {
        recordedReview("REVIEW_THEN_ACTIVATE") shouldBe "$tenant:false"
    }

    @Test
    fun `INV-AC-2388 notice-only activation has no review step and is recorded as unreviewed (record part)`() {
        recordedReview("ACTIVATE_WITH_NOTICE") shouldBe "$tenant:false"
    }

    @Test
    fun `INV-AC-1903 a tenant whose window is still open refuses the arming 409 SRT-008 and every table is equal`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        delta(tenant, TYPE, v, daysAgo = 1)
        grantArmer("u-armer")
        val before = snapshot()
        refused(arm("u-armer", TYPE, v), 409, "SRT-008")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-1903 the PRIVATE form-less case a tenant with no delta gets no review row`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grantArmer("u-armer")
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
        reviews(v) shouldBe ""
    }

    @Test
    fun `INV-AC-1903 a request date one day later or earlier is refused 409 SRT-008 and the equal date arms`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grantArmer("u-armer")
        val before = snapshot()
        refused(arm("u-armer", TYPE, v, "2027-01-02"), 409, "SRT-008")
        refused(arm("u-armer", TYPE, v, "2026-12-31"), 409, "SRT-008")
        snapshot() shouldBe before
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-1903 acknowledgements missing inside the window are 409 SRT-008 and every table is equal`() {
        val v = seedApproved()
        ackAll(TYPE, v, except = "payload-svc")
        grantArmer("u-armer")
        val before = snapshot()
        refused(arm("u-armer", TYPE, v), 409, "SRT-008")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-2306 the acknowledgement answers ACKNOWLEDGED with the window end and a repeat changes nothing`() {
        val v = seedApproved()
        delta(tenant, TYPE, v, daysAgo = 3)
        grant("u-ack", roles(DeploymentForm.SAAS, ACK_KEY).single())
        val answer = acknowledge("u-ack", TYPE, v)
        answer.statusCode() shouldBe 200
        answer.body() shouldContain "\"ACKNOWLEDGED\""
        answer.body() shouldContain "\"graceWindowEnd\":\"${LocalDate.now(clock).plusDays(11)}\""
        status(TYPE, v) shouldBe "APPROVED"
        val once = snapshot()
        acknowledge("u-ack", TYPE, v).statusCode() shouldBe 200
        snapshot() shouldBe once
    }

    @Test
    fun `INV-AC-2306 an acknowledgement with no complete delta is 409 SRT-008 and every table is equal`() {
        val v = seedApproved()
        grant("u-ack", roles(DeploymentForm.SAAS, ACK_KEY).single())
        val before = snapshot()
        refused(acknowledge("u-ack", TYPE, v), 409, "SRT-008")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-2306 the private form answer NoGraceWindow maps to 409 SRT-008 with a detail that names no person`() {
        val refusal = RegulatoryPackArmingMapping.refusalOf(AcknowledgePackDeltaResult.NoGraceWindow)!!
        refusal.status shouldBe 409
        refusal.code shouldBe "SRT-008"
        refusal.detail shouldBe "the deployment has no grace window to acknowledge in"
        RegulatoryPackArmingMapping.refusalOf(AcknowledgePackDeltaResult.Denied)!!.status shouldBe 403
        RegulatoryPackArmingMapping.refusalOf(AcknowledgePackDeltaResult.NotFound)!!.status shouldBe 404
    }

    @Test
    fun `INV-AC-20504 callers holding only another key are refused 403 SRT-004 and every table is equal`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grant("u-import", roles(DeploymentForm.PRIVATE, IMPORT_KEY).first { it !in roles(DeploymentForm.SAAS, ARM_KEY) })
        grant("u-ack", roles(DeploymentForm.SAAS, ACK_KEY).first { it !in roles(DeploymentForm.SAAS, ARM_KEY) })
        val before = snapshot()
        refused(arm("u-ack", TYPE, v), 403, "SRT-004")
        refused(arm("u-import", TYPE, v), 403, "SRT-004")
        refused(arm("u-nobody", TYPE, v), 403, "SRT-004")
        snapshot() shouldBe before
        CurrencyCalls.statuses.size shouldBe 0
    }

    @Test
    fun `INV-AC-20504 a caller with the one arming role of the SaaS form is not refused`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grantArmer("u-armer")
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-20504 an unavailable permission decision is 503 SRT-013 and every table is equal`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grantArmer("u-armer")
        val before = snapshot()
        replica.tracker.onDisconnected()
        clock.advance(Duration.ofSeconds(30))
        refused(arm("u-armer", TYPE, v), 503, "SRT-013")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-2306 the acknowledgement key held only as the arming key is refused 403 SRT-004`() {
        val v = seedApproved()
        delta(tenant, TYPE, v, daysAgo = 3)
        grantArmer("u-armer")
        val before = snapshot()
        refused(acknowledge("u-armer", TYPE, v), 403, "SRT-004")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-2306 a refused caller leaves nothing stored for the two operations`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        delta(tenant, TYPE, v, daysAgo = 3)
        grantArmer("u-armer")
        grant("u-ack", roles(DeploymentForm.SAAS, ACK_KEY).single())
        val before = snapshot()
        val alt = workloadToken("$CALLER-alt")
        refused(arm("u-armer", TYPE, v, token = alt), 403, "SRT-004")
        refused(acknowledge("u-ack", TYPE, v, token = alt), 403, "SRT-004")
        arm("u-armer", TYPE, v, token = null).statusCode() shouldBe 401
        acknowledge("u-ack", TYPE, v, token = null).statusCode() shouldBe 401
        snapshot() shouldBe before
        // the named caller is not refused, so the refusal above is specific to the caller
        acknowledge("u-ack", TYPE, v).statusCode() shouldBe 200
    }

    @Test
    fun `INV-AC-1903 a successful arming calls the currency monitor once after ARMED is committed`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grantArmer("u-armer")
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
        CurrencyCalls.statuses.size shouldBe 1
        CurrencyCalls.statuses.single() shouldContain "ARMED"
    }

    @Test
    fun `INV-AC-1903 the window key is read from configuration with no default and fails closed`() {
        val config = RulesConfiguration()
        val key = "rules.fence.acknowledgement-window"
        config.fenceAcknowledgementWindow(MockEnvironment().withProperty(key, "PT15M")).value shouldBe Duration.ofMinutes(15)
        listOf("", "15 minutes").forEach {
            val e = runCatching { config.fenceAcknowledgementWindow(MockEnvironment().withProperty(key, it)) }.exceptionOrNull()
            (e?.message ?: "") shouldContain key
        }
        (runCatching { config.fenceAcknowledgementWindow(MockEnvironment()) }.exceptionOrNull()?.message ?: "") shouldContain key
        val yaml = Files.readAllLines(Path.of("src/main/resources/application.yaml"))
        yaml.count { it.trim() == "fence.acknowledgement-window: PT15M" } shouldBe 1
        yaml.none { it.contains("acknowledgment-window") } shouldBe true
    }

    @Test
    fun `INV-AC-1903 no Kotlin main source carries PT15M or Duration ofMinutes 15`() {
        val roots = listOf("../rules-application/src/main", "../rules-adapters/src/main", "src/main")
        val offenders =
            roots.flatMap { root ->
                Files
                    .walk(Path.of(root))
                    .filter { it.name.endsWith(".kt") }
                    .filter { p ->
                        val text = Files.readString(p)
                        "PT15M" in text || "Duration.ofMinutes(15)" in text
                    }.map { it.toString() }
                    .toList()
            }
        offenders shouldBe emptyList()
    }

    @Test
    fun `INV-AC-1903 no answer names a person`() {
        val v = seedApproved()
        grantArmer("u-armer")
        val body = arm("u-nobody", TYPE, v).body()
        body shouldNotContain "u-nobody"
    }
}

/** The PRIVATE form with a one-second window, so that the clock passes it: the abort and late-answer cases. */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, ArmingFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RegulatoryPackArmingAbortBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ArmingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "private" }
            registry.add("rules.fence.acknowledgement-window") { "PT1S" }
        }
    }

    private fun armed(user: String) = grant(user, armerOf(DeploymentForm.PRIVATE))

    private fun pastWindow() {
        clock.advance(Duration.ofMinutes(5))
        CurrencyCalls.statuses.clear()
    }

    @Test
    fun `INV-AC-1235 one instance unacknowledged after the window aborts RUL-005, arms none, keeps the previous version`() {
        val effective = uniqueDate()
        val v = approvedSchematron(effective)
        val previous = seedApproved(SCHEMATRON, "2026-01-01", "ARMED")
        ackAll(SCHEMATRON, v, except = "tenancy-svc")
        armed("u-armer")
        pastWindow()
        val before = snapshot()
        val answer = arm("u-armer", SCHEMATRON, v, effective)
        refused(answer, 422, "RUL-005")
        status(SCHEMATRON, v) shouldBe "APPROVED"
        resolved(SCHEMATRON, "2027-06-01", v, previous) shouldBe previous
        snapshot() shouldBe before
        CurrencyCalls.statuses.size shouldBe 0
    }

    @Test
    fun `INV-AC-20503 the armed state and the resolved version equal the previous ones after an interrupted activation`() {
        val v = seedApproved()
        val previous = seedApproved(TYPE, "2026-01-01", "ARMED")
        ackAll(TYPE, v, except = "payload-svc")
        instanceAck(TYPE, v, "payload-svc", "FAILED")
        armed("u-armer")
        pastWindow()
        val before = snapshot()
        refused(arm("u-armer", TYPE, v), 422, "RUL-005")
        scalar("select count(*)::text from rules.pack_registry where status = 'ARMED' and pack_version = ?", v) shouldBe "0"
        resolved(TYPE, "2027-06-01", v, previous) shouldBe previous
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-1903 an acknowledgement stored after the window but before the request counts and the arming succeeds`() {
        val v = seedApproved()
        ackAll(TYPE, v, except = "reconciliation-svc")
        pastWindow()
        instanceAck(TYPE, v, "reconciliation-svc")
        armed("u-armer")
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
        CurrencyCalls.statuses.size shouldBe 1
    }

    @Test
    fun `INV-AC-1903 every refused arming calls the currency monitor 0 times`() {
        val v = seedApproved()
        armed("u-armer")
        pastWindow()
        refused(arm("u-armer", TYPE, v), 422, "RUL-005")
        refused(arm("u-armer", TYPE, "nope"), 404, "SRT-005")
        refused(arm("u-nobody", TYPE, v), 403, "SRT-004")
        CurrencyCalls.statuses.size shouldBe 0
    }
}

/** The PRIVATE form: only the arming role of the private catalogue arms and no review row is recorded. */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, ArmingFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class RegulatoryPackArmingPrivateBoundaryTest(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : ArmingHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "private" }
        }
    }

    /** Fence pass on a Schematron pack: INV-AC-7966 third request, INV-AC-7964, INV-AC-1729. */
    private fun passFence(): String {
        val effective = uniqueDate()
        val v = approvedSchematron(effective)
        val previous = seedApproved(SCHEMATRON, "2026-01-01", "ARMED")
        val controller = context.getBean(com.dynokore.gst.rules.adapters.web.PacksController::class.java)
        val startup = context.startupDate
        val before = CurrencyCalls.compiles.get()
        ackAll(SCHEMATRON, v)
        grant("u-armer", armerOf(DeploymentForm.PRIVATE))
        CurrencyCalls.statuses.clear()
        val answer = arm("u-armer", SCHEMATRON, v, effective)
        answer.statusCode() shouldBe 200
        answer.body() shouldContain "\"ARMED\""
        status(SCHEMATRON, v) shouldBe "ARMED"
        scalar("select effective_from::text from rules.pack_registry where pack_version = ?", v) shouldBe effective
        scalar(
            "select count(*)::text from rules.outbox where type = 'com.dynokore.gst.platform.RulePackActivated.v1' " +
                "and subject = ?",
            "$SCHEMATRON@$v",
        ) shouldBe "1"
        resolved(SCHEMATRON, effective, v, previous) shouldBe v
        resolved(SCHEMATRON, "2026-12-31", v, previous) shouldBe previous
        (context.getBean(com.dynokore.gst.rules.adapters.web.PacksController::class.java) === controller) shouldBe true
        context.startupDate shouldBe startup
        (CurrencyCalls.compiles.get() - before) shouldBe 2
        return v
    }

    @Test
    fun `INV-AC-7966 every instance acknowledges, effective_from is armed, RulePackActivated is published`() {
        passFence()
    }

    @Test
    fun `INV-AC-7964 the stylesheets are compiled at activation and no validation compiles again`() {
        val v = passFence()
        val held = context.getBean(CompiledPackHold::class.java)
        held.stylesheetNames(SCHEMATRON, v) shouldBe setOf("a.xsl", "b.xslt")
        val after = CurrencyCalls.compiles.get()
        repeat(100) { held.validateAll(SCHEMATRON, v, "<doc/>".toByteArray()) shouldBe 2 }
        CurrencyCalls.compiles.get() shouldBe after
    }

    @Test
    fun `INV-AC-1729 the count after arming equals the stylesheet files and is equal after 100 validations`() {
        val v = passFence()
        val held = context.getBean(CompiledPackHold::class.java)
        val count = CurrencyCalls.compiles.get()
        repeat(100) { held.validateAll(SCHEMATRON, v, "<doc/>".toByteArray()) }
        CurrencyCalls.compiles.get() shouldBe count
    }

    @Test
    fun `INV-AC-20504 the private arming role arms and records no review row`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        grant("u-armer", armerOf(DeploymentForm.PRIVATE))
        arm("u-armer", TYPE, v).statusCode() shouldBe 200
        reviews(v) shouldBe ""
    }

    @Test
    fun `INV-AC-20504 a caller with only the import permission is refused 403 SRT-004 and every table is equal`() {
        val v = seedApproved()
        ackAll(TYPE, v)
        val importer = roles(DeploymentForm.PRIVATE, IMPORT_KEY).first { it != armerOf(DeploymentForm.PRIVATE) }
        grant("u-import", importer)
        val before = snapshot()
        refused(arm("u-import", TYPE, v), 403, "SRT-004")
        snapshot() shouldBe before
    }

    @Test
    fun `INV-AC-20504 the acknowledgement is refused 403 SRT-004 in the private form even for the approver role`() {
        val v = seedApproved()
        delta(tenant, TYPE, v)
        grant("u-approver", armerOf(DeploymentForm.PRIVATE))
        val before = snapshot()
        refused(acknowledge("u-approver", TYPE, v), 403, "SRT-004")
        snapshot() shouldBe before
    }
}
