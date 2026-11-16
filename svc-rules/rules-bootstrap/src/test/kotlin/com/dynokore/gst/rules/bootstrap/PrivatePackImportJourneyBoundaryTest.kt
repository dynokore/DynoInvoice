package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val IMPORT_KEY = "regulatory-pack.import-verify"
private const val APPROVE_KEY = "regulatory-pack.approve"
private const val SCHEMATRON = "SCHEMATRON"
private const val SVRL = "http://purl.oclc.org/dsdl/svrl"
private val HOLDERS = listOf("rules-svc", "payload-svc", "tenancy-svc", "reconciliation-svc", "notification-svc")
private const val OUTBOX_CURRENCY = "com.dynokore.gst.platform.PackCurrencyChanged.v1"
private const val OUTBOX_STAGED = "com.dynokore.gst.platform.RulePackStaged.v1"
private const val OUTBOX_ACTIVATED = "com.dynokore.gst.platform.RulePackActivated.v1"
private const val USER_A = "u-importer-a"
private const val USER_B = "u-approver-b"
private const val USER_DRY = "u-dry-other"
private const val CURRENCY_SCHEMA = "/contract/schemas/pack-currency-changed-payload.schema.json"

/**
 * INV-T-22759 (INV-REQ-47): the private-deployment pack import journey at the service boundary of rules-svc, on the
 * wired context and the real PostgreSQL. User A holds only the import key; user B holds the approval key and the
 * arming key. Roles are read from the generated catalogue map, never written as literals (INV-Q-10027); `actor` is a
 * user ID (INV-Q-10647). The harness, the fence fixture and the helpers are those of [ArmingHarness].
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, ArmingFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Suppress("TooManyFunctions")
class PrivatePackImportJourneyBoundaryTest(
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

    private val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val json = JsonMapper()

    // ---- catalogue roles -----------------------------------------------------------------------------------------

    private val approverRole get() = roles(DeploymentForm.PRIVATE, APPROVE_KEY).single()

    /** An import role that is neither the arming role nor the approval role: user A holds only the import key. */
    private val importerRole
        get() =
            roles(DeploymentForm.PRIVATE, IMPORT_KEY)
                .filter { it != armerOf(DeploymentForm.PRIVATE) && it != approverRole }
                .also { check(it.isNotEmpty()) { "$IMPORT_KEY has no role apart from the approval and arming roles" } }
                .first()

    private fun grantA() = grant(USER_A, importerRole)

    private fun grantB() {
        grant(USER_B, approverRole)
        grant(USER_B, armerOf(DeploymentForm.PRIVATE))
    }

    // ---- bundles -------------------------------------------------------------------------------------------------

    private fun sha(bytes: ByteArray) =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** A signed bundle of [version] requiring [minEngine]; the stylesheets are those the fence compiles at arming. */
    private fun mountedBundle(
        version: String,
        minEngine: String,
        effective: String,
    ): String {
        val stylesheet =
            """<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform" xmlns:svrl="$SVRL">
          <xsl:template match="/"><svrl:schematron-output/></xsl:template></xsl:stylesheet>""".toByteArray()
        val file =
            """{"name":"a.xsl","sha256":"${sha(stylesheet)}",""" +
                """"content":"${java.util.Base64.getEncoder().encodeToString(stylesheet)}"}"""
        val contents = """{"version":"1.0","files":[$file]}""".toByteArray()
        val manifest =
            (
                """{"packId":"$SCHEMATRON-pack","packType":"$SCHEMATRON","version":"$version",""" +
                    """"effectiveFrom":"$effective","effectiveTo":null,"minEngineVersion":"$minEngine"}"""
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
        return "sha256:${sha(bytes)}"
    }

    private fun importBody(
        version: String,
        hash: String,
    ) = """{"packType":"$SCHEMATRON","version":"$version","bundleHash":"$hash"}"""

    // ---- rows ----------------------------------------------------------------------------------------------------

    private fun count(
        sql: String,
        vararg binds: String,
    ) = scalar(sql, *binds)!!.toLong()

    /** The seeding of `addDryRun` of the approval boundary test: through the platform binding of TenancyProbe. */
    private fun seedDelta(
        version: String,
        state: String = "COMPLETE",
    ) {
        probe.inTenant(TenantId.PLATFORM) {
            fetchValue(
                "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, " +
                    "completed_at, changed_count) values (?::uuid, ?::uuid, ?, ?, ?, ?, " +
                    "clock_timestamp(), 0) returning id",
                UUID.randomUUID().toString(),
                TenantId.PLATFORM.toString(),
                SCHEMATRON,
                version,
                state,
                USER_DRY,
            )
        }
        if (state == "COMPLETE") {
            count(
                "select count(*) from rules.dry_run_delta where pack_type = ? and pack_version = ? " +
                    "and state = 'COMPLETE'",
                SCHEMATRON,
                version,
            ) shouldBe 1
        }
    }

    private fun importRow(version: String) =
        scalar(
            "select status || '|' || actor from rules.pack_import where pack_type = ? and pack_version = ?",
            SCHEMATRON,
            version,
        )

    private fun rows(
        table: String,
        version: String,
    ) = count("select count(*) from rules.$table where pack_version = ?", version)

    private fun outboxRows(
        type: String,
        version: String,
    ) = count("select count(*) from rules.outbox where type = ? and data::text like ?", type, "%\"$version\"%")

    private fun currencyRows(): List<Map<String, Any?>> =
        superuser { c ->
            c
                .prepareStatement(
                    "select data::text, tenant_id::text, entity_ref from rules.outbox where type = ? order by seq",
                ).use { ps ->
                    ps.setString(1, OUTBOX_CURRENCY)
                    ps.executeQuery().use { rs ->
                        buildList {
                            while (rs.next()) {
                                val data = json.readTree(rs.getString(1))
                                add(
                                    mapOf(
                                        "depCode" to data.path("depCode").asString(null),
                                        "status" to data.path("status").asString(null),
                                        "packIdentifier" to data.path("packIdentifier").asString(null),
                                        "packVersion" to data.path("packVersion").asString(null),
                                        "tenant" to rs.getString(2),
                                        "entity" to rs.getString(3),
                                    ),
                                )
                            }
                        }
                    }
                }
        }

    /** The two status values of the pinned schema, in its order: raised, cleared. */
    private fun schemaStatuses(): List<String> {
        val schema =
            json.readTree(
                checkNotNull(PrivatePackImportJourneyBoundaryTest::class.java.getResourceAsStream(CURRENCY_SCHEMA)) {
                    "the pinned schema $CURRENCY_SCHEMA is missing"
                }.use { String(it.readAllBytes(), Charsets.UTF_8) },
            )
        val values = schema.path("properties").path("status").path("enum")
        return (0 until values.size()).map { values.get(it).asString() }
    }

    private fun deniedWithAndWithoutDelta(
        v: String,
        date: String,
    ) {
        // (2a) A arms with no delta: denied, nothing changes
        val beforeA = snapshot()
        val deniedWithout = arm(USER_A, SCHEMATRON, v, date)
        refused(deniedWithout, 403, "SRT-004")
        status(SCHEMATRON, v) shouldBe "STAGED"
        snapshot() shouldBe beforeA
        // (2b) the seeded COMPLETE delta of a user other than A and B
        seedDelta(v)
        // (2c) A arms again with the delta present: the same answer
        val beforeC = snapshot()
        val deniedWith = arm(USER_A, SCHEMATRON, v, date)
        deniedWith.statusCode() shouldBe deniedWithout.statusCode()
        deniedWith.body().let { json.readTree(it).path("code").asString(null) } shouldBe
            json.readTree(deniedWithout.body()).path("code").asString(null)
        status(SCHEMATRON, v) shouldBe "STAGED"
        snapshot() shouldBe beforeC
    }

    private fun approveAndArm(
        v: String,
        date: String,
    ) {
        // (4) B approves, then arms through the fence: first with one instance not acknowledging
        grantB()
        call("$SCHEMATRON/$v/approval", USER_B, null).statusCode() shouldBe 200
        scalar("select approved_by from rules.pack_registry where pack_version = ?", v) shouldBe USER_B
        ackAll(SCHEMATRON, v, except = HOLDERS.last())
        clock.advance(java.time.Duration.ofMinutes(30))
        refused(arm(USER_B, SCHEMATRON, v, date), 422, "RUL-005")
        status(SCHEMATRON, v) shouldBe "APPROVED"
        instanceAck(SCHEMATRON, v, HOLDERS.last())
        val armed = arm(USER_B, SCHEMATRON, v, date)
        armed.statusCode() shouldBe 200
        status(SCHEMATRON, v) shouldBe "ARMED"
        scalar(
            "select count(*)::text from rules.outbox where type = ? and subject = ?",
            OUTBOX_ACTIVATED,
            "$SCHEMATRON@$v",
        ) shouldBe "1"
    }

    // ---- cases ---------------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-22758 the importer cannot arm or approve, a different user does, and the pack is armed`() {
        val date = uniqueDate()
        val v = version()
        grantA()
        // (1) A imports a signed, engine-compatible bundle
        val answer = call("import", USER_A, importBody(v, mountedBundle(v, "1.0.0", date)))
        answer.statusCode() shouldBe 201
        answer.body() shouldContain "\"STAGED\""
        importRow(v) shouldBe "STAGED|$USER_A"
        rows("pack_registry", v) shouldBe 1
        rows("pack_artefact", v) shouldBe 1
        outboxRows(OUTBOX_STAGED, v) shouldBe 1
        deniedWithAndWithoutDelta(v, date)
        // (3) the approval key is also granted to A: the stager cannot approve
        grant(USER_A, approverRole)
        refused(call("$SCHEMATRON/$v/approval", USER_A, null), 403, "SRT-004")
        status(SCHEMATRON, v) shouldBe "STAGED"
        scalar("select approved_by from rules.pack_registry where pack_version = ?", v) shouldBe null
        approveAndArm(v, date)
    }

    @Test
    fun `INV-AC-2075 INV-AC-2076 DEP-001 is raised at the import and cleared when the pack is armed`() {
        val statuses = schemaStatuses()
        statuses shouldBe listOf("RAISED", "CLEARED")
        val date = uniqueDate()
        val v = version()
        grantA()
        grantB()
        val before = currencyRows().size
        call("import", USER_A, importBody(v, mountedBundle(v, "1.0.0", date))).statusCode() shouldBe 201
        val raised = currencyRows().drop(before)
        raised.size shouldBe 1
        raised.single().let {
            it["depCode"] shouldBe "DEP-001"
            it["status"] shouldBe statuses[0]
            it["packIdentifier"] shouldBe "$SCHEMATRON-pack"
            it["packVersion"] shouldBe v
            it["tenant"] shouldBe TenantId.PLATFORM.toString()
            it["entity"] shouldBe EntityRef.PLATFORM.value
        }
        seedDelta(v)
        call("$SCHEMATRON/$v/approval", USER_B, null).statusCode() shouldBe 200
        ackAll(SCHEMATRON, v)
        arm(USER_B, SCHEMATRON, v, date).statusCode() shouldBe 200
        val after = currencyRows().drop(before)
        after.size shouldBe 2
        // The cleared payload names no pack (PackCurrency.evaluate: null identifier and version when nothing is
        // unactivated), so the row is tied to this journey by its position after the raised row of the version.
        after[1].let {
            it["depCode"] shouldBe "DEP-001"
            it["status"] shouldBe statuses[1]
            it["packIdentifier"] shouldBe null
            it["packVersion"] shouldBe null
            it["tenant"] shouldBe TenantId.PLATFORM.toString()
            it["entity"] shouldBe EntityRef.PLATFORM.value
        }
    }

    @Test
    fun `INV-AC-22758 engine a bundle requiring a newer train is refused DEP-003 and cannot be armed`() {
        val date = uniqueDate()
        val v = version()
        val never = version()
        grantA()
        grantB()
        val currencyBefore = currencyRows().size
        val answer = call("import", USER_A, importBody(v, mountedBundle(v, "3.0.0", date)))
        refused(answer, 422, "DEP-003")
        rows("pack_registry", v) shouldBe 0
        rows("pack_artefact", v) shouldBe 0
        (importRow(v)?.startsWith("STAGED") ?: false) shouldBe false
        outboxRows(OUTBOX_STAGED, v) shouldBe 0
        currencyRows().drop(currencyBefore).count { it["packVersion"] == v } shouldBe 0
        val control = arm(USER_B, SCHEMATRON, never, date)
        val refusedArm = arm(USER_B, SCHEMATRON, v, date)
        (refusedArm.statusCode() in 200..299) shouldBe false
        refusedArm.statusCode() shouldBe control.statusCode()
        json.readTree(refusedArm.body()).path("code").asString(null) shouldBe
            json.readTree(control.body()).path("code").asString(null)
    }
}
