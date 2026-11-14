package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
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
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val IMPORT_PERMISSION = "regulatory-pack.import-verify"

private val HOLIDAYS = listOf("2027-01-01", "2027-02-06", "2027-04-02", "2027-05-01", "2027-12-25")

private fun row(
    section: String,
    key: String = "",
) = """{"section":"$section",$key"sourceClass":"PRIMARY","sourceReference":"para 1"}"""

private fun completeRows() =
    listOf(
        row("windows", """"frequency":"MONTHLY","date":"2027-01-01","""),
        row("windows", """"frequency":"QUARTERLY","date":"2027-01-01","""),
        row("finalReturnForm"),
        row("dueRules", """"returnForm":"F5","""),
        row("weekend"),
        row("alertThresholds", """"frequency":"MONTHLY","""),
        row("alertThresholds", """"frequency":"QUARTERLY","""),
        row("safetyMargin"),
    ) + HOLIDAYS.map { row("holidays", """"date":"$it",""") }

/** A calendar with two frequencies and five holidays; [margin] and [rows] are the members the cases take away. */
private fun calendar(
    margin: String? = ""","safetyMargin":{"value":2,"unit":"DAYS"}""",
    rows: List<String> = completeRows(),
) = (
    """{"windows":{"MONTHLY":[{"start":"2027-01-01","end":"2027-01-31","returnForm":"F5"}],""" +
        """"QUARTERLY":[{"start":"2027-01-01","end":"2027-03-31","returnForm":"F5"}]},"finalReturnForm":"F8",""" +
        """"dueRules":{"F5":{"monthsAfterEnd":1,"endOfMonth":true,"daysAfterEnd":0}},""" +
        """"weekend":["SATURDAY","SUNDAY"],"holidays":[${HOLIDAYS.joinToString(",") { "\"$it\"" }}],""" +
        """"alertThresholds":{"MONTHLY":[14,7,1],"QUARTERLY":[30,14,7]}${margin.orEmpty()},""" +
        """"entrySources":[${rows.joinToString(",")}]}"""
)

/**
 * INV-T-22703 (slice 2 of INV-T-6567; INV-Q-6554, INV-Q-6556; INV-REQ-774, INV-REQ-1117, INV-REQ-1279): the
 * served `importRegulatoryPack` refuses a calendar pack whose defaults or source rows are incomplete, and a
 * scenario pack with an unclassified
 * rendition wording, with RUL-005 and the failing item in the detail; nothing is stored for a refused pack.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PackContentsImportBoundaryTest(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
) {
    companion object {
        private val dir: Path = Files.createTempDirectory("rules-contents-bundles")
        private val versions = AtomicLong(0)
        private val epoch = AtomicLong(1)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            registry.add("service-runtime.modules.schema") { "rules" }
            registry.add("inv.tenancy.reference-tables") { "rules.pack_registry,rules.pack_entry_source" }
            registry.add("service-runtime.workload-identity.callers[0].service") { "console-bff" }
            registry.add("service-runtime.workload-identity.callers[0].subject") { subjectOf("console-bff") }
            registry.add("gst.form") { "private" }
            registry.add("rules.pack-source.directory") { dir.toString() }
        }
    }

    private val http = HttpClient.newHttpClient()
    private val tenant = TenantId(UUID.randomUUID())
    private val user = "u-contents"

    @BeforeEach
    fun grantImport() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
        val role =
            (
                OperationRoles.rolesFor(DeploymentForm.PRIVATE, IMPORT_PERMISSION).keys +
                    OperationRoles.rolesFor(DeploymentForm.SAAS, IMPORT_PERMISSION).keys
            ).first()
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        role,
                        GrantScope.TENANT,
                        tenant.toString(),
                        clock.instant().minus(Duration.ofHours(1)),
                        null,
                    ),
                ),
                epoch.get(),
            ),
        )
    }

    private fun sha(bytes: ByteArray) =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** A bundle of [type] with [contents], signed by the test train; mounted under its digest; the hash returned. */
    private fun mounted(
        type: String,
        version: String,
        contents: String,
    ): String {
        val body = contents.toByteArray()
        val manifest =
            (
                """{"packId":"$type-pack","packType":"$type","version":"$version","effectiveFrom":"2027-01-01",""" +
                    """"effectiveTo":null,"minEngineVersion":"1.0.0"}"""
            ).toByteArray()
        val checksums = """{"contents":"${sha(body)}"}""".toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            linkedMapOf(
                "manifest.json" to manifest,
                "checksums.json" to checksums,
                "contents.json" to body,
                "signature.p7s" to TestTrain.key.sign(manifest + checksums),
            ).forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        val bytes = out.toByteArray()
        Files.write(dir.resolve(sha(bytes)), bytes)
        return "sha256:${sha(bytes)}"
    }

    private fun import(
        type: String,
        contents: String,
        version: String = "t" + UUID.randomUUID().toString().take(8),
    ): HttpResponse<String> {
        val hash = mounted(type, version, contents)
        val request =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs/import"))
                .header("Authorization", "Bearer $user|$tenant|")
                .header("X-Workload-Token", workloadToken("console-bff"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"packType":"$type","version":"$version","bundleHash":"$hash"}""",
                    ),
                ).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun rows(sql: String): List<String> =
        DriverManager
            .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
            .use { c ->
                c.createStatement().use { st ->
                    st.executeQuery(sql).use {
                        buildList { while (it.next()) add("${it.getString(1)}|${it.getString(2)}") }
                    }
                }
            }

    /** The import record of [version] as `status|failure_code`, read as the container's superuser. */
    private fun record(version: String) =
        rows("select status, coalesce(failure_code, '-') from rules.pack_import where pack_version = '$version'")

    private fun staged(version: String) =
        rows("select 'artefact', count(*) from rules.pack_artefact where pack_version = '$version'") +
            rows("select 'registry', count(*) from rules.pack_registry where pack_version = '$version'")

    private fun newVersion() = "t" + UUID.randomUUID().toString().take(8)

    /** 422 RUL-005 with the detail equal to [detail]; the record is FAILED RUL-005 and nothing is staged. */
    private fun refused(
        type: String,
        contents: String,
        detail: String,
    ) {
        val version = newVersion()
        val answer = import(type, contents, version)
        answer.statusCode() shouldBe 422
        answer.body() shouldContain "\"code\":\"RUL-005\""
        answer.body() shouldContain "\"detail\":\"$detail\""
        record(version) shouldBe listOf("FAILED|RUL-005")
        staged(version) shouldBe listOf("artefact|0", "registry|0")
    }

    private fun accepted(
        type: String,
        contents: String,
    ) {
        val version = newVersion()
        val answer = import(type, contents, version)
        answer.statusCode() shouldBe 201
        answer.body() shouldContain "\"status\":\"STAGED\""
        record(version) shouldBe listOf("STAGED|-")
        staged(version) shouldBe listOf("artefact|1", "registry|1")
    }

    @Test
    fun `INV-AC-22704 a complete calendar pack is staged`() {
        accepted("CALENDAR", calendar())
    }

    @Test
    fun `INV-AC-22704 a calendar pack without safetyMargin is refused RUL-005 naming safetyMargin`() {
        refused("CALENDAR", calendar(margin = null), "safetyMargin is absent")
    }

    @Test
    fun `INV-AC-22704 a calendar pack whose holiday has no row is refused RUL-005 naming the date`() {
        val rows = completeRows().filterNot { it.contains("2027-04-02") }
        refused("CALENDAR", calendar(rows = rows), "entrySources has no row for entry holidays date=2027-04-02")
    }

    @Test
    fun `INV-AC-22704 a calendar pack with two rows for one holiday is refused RUL-005 naming the date`() {
        refused(
            "CALENDAR",
            calendar(rows = completeRows() + row("holidays", """"date":"2027-02-06",""")),
            "entrySources has 2 rows for entry holidays date=2027-02-06",
        )
    }

    @Test
    fun `INV-AC-22704 a pack of type Calendar is not checked as the calendar pack type`() {
        accepted("Calendar", calendar(margin = null))
    }

    @Test
    fun `INV-AC-22704 a scenario pack with an unclassified rendition wording is refused RUL-005 naming the item`() {
        val item = """{"text":"Credit Note","citation":"c","sourceClass":"PRIMARY"}"""
        val bare = """{"text":"Debit Note","citation":"c"}"""
        val scenario =
            """{"renditionWording":[{"scenario":"S","submissionType":"TYPE_2","requiredWording":[$item,$bare]}]}"""
        refused("SCENARIO", scenario, "renditionWording[0].requiredWording[1].sourceClass is required")
    }
}
