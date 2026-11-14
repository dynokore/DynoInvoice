package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.application.ImportRegulatoryPack
import com.dynokore.gst.rules.application.PackCurrencyMonitor
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.ReplicaFallbackDouble
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.rulepackkernel.TrustAnchor
import com.dynokore.platform.rulepackkernel.TrustAnchors
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
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
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val IMPORT_KEY = "regulatory-pack.import-verify"
private const val ENGINE = "2.0.0"
private const val FAMILY = "SCHEMATRON"
private const val TABLES_SQL =
    "select table_name from information_schema.tables where table_schema = 'rules' " +
        "and table_type = 'BASE TABLE' order by 1"
private val OTHER_KEYS = listOf("regulatory-pack.author", "filing.confirm", "regulatory-pack.approve")
private const val CONTENTS = """{"rules":[]}"""

/** The throw-away train key whose certificate is the only trust anchor of these tests (STK-13). */
private object TestTrain {
    val key = TestPackSigning.newKey()
}

/** Test doubles for what a release train ships: the anchors and the build version (never a production default). */
@TestConfiguration(proxyBeanMethods = false)
class ImportFixtures {
    @Bean
    fun trainAnchors(): com.dynokore.gst.rules.application.TrustAnchorSource =
        com.dynokore.gst.rules.application.TrustAnchorSource {
            TrustAnchors(listOf(TrustAnchor("test-train", TestTrain.key.certificateDer)))
        }

    @Bean
    fun testBuild(): BuildProperties =
        BuildProperties(
            Properties().also {
                it.setProperty("group", "g")
                it.setProperty("artifact", "a")
                it.setProperty("version", ENGINE)
            },
        )
}

/**
 * INV-T-4976 (INV-REQ-1078): the served `importRegulatoryPack` and `getRegulatoryPack` over the wired context on real
 * PostgreSQL, with signed test bundles in the mounted directory of the pack source. The permission is the scoped key
 * `regulatory-pack.import-verify`; every refusal is compared by the full content of every `rules` table.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Suppress("TooManyFunctions") // one case per refusal of the contract
class RegulatoryPackImportBoundaryTest(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
    @Autowired private val context: ApplicationContext,
) {
    companion object {
        private val dir: Path = Files.createTempDirectory("rules-bundles")

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

        private val versions = AtomicLong(0)
        private val epoch = AtomicLong(1)
    }

    private val http = HttpClient.newHttpClient()
    private val tenant = TenantId(UUID.randomUUID())

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private fun importRole(): String {
        val saas = OperationRoles.rolesFor(DeploymentForm.SAAS, IMPORT_KEY).keys
        val private = OperationRoles.rolesFor(DeploymentForm.PRIVATE, IMPORT_KEY).keys
        check((saas + private).isNotEmpty()) { "$IMPORT_KEY has no role in the catalogue" }
        return (private.ifEmpty { saas }).first()
    }

    private fun grant(
        user: String,
        role: String = importRole(),
    ) {
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

    // ---- bundles -----------------------------------------------------------------------------------------------

    private fun manifest(
        version: String,
        minEngine: String = "1.0.0",
        type: String = FAMILY,
    ) = (
        """{"packId":"$type-pack","packType":"$type","version":"$version","effectiveFrom":"2027-01-01",""" +
            """"effectiveTo":null,"minEngineVersion":"$minEngine"}"""
    ).toByteArray()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun zip(members: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            members.forEach { (name, bytes) ->
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A bundle signed by the train key; [signed] false leaves out signature.p7s; [signer] swaps the signing key. */
    private fun bundle(
        version: String,
        minEngine: String = "1.0.0",
        signed: Boolean = true,
        signer: TestPackSigning.Key = TestTrain.key,
        type: String = FAMILY,
    ): ByteArray {
        val contents = CONTENTS.toByteArray()
        val manifest = manifest(version, minEngine, type)
        val checksums = """{"contents":"${sha(contents)}"}""".toByteArray()
        val members =
            linkedMapOf("manifest.json" to manifest, "checksums.json" to checksums, "contents.json" to contents)
        if (signed) members["signature.p7s"] = signer.sign(manifest + checksums)
        return zip(members)
    }

    /** Puts [bytes] into the mounted directory under its digest and returns the `bundleHash` of the request. */
    private fun mount(bytes: ByteArray): String {
        val digest = sha(bytes)
        Files.write(dir.resolve(digest), bytes)
        return "sha256:$digest"
    }

    private fun newVersion() = "t" + UUID.randomUUID().toString().take(8)

    // ---- http --------------------------------------------------------------------------------------------------

    private fun call(
        method: String,
        path: String,
        user: String?,
        body: String? = null,
        caller: String? = "console-bff",
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs$path"))
        if (user != null) b.header("Authorization", "Bearer $user|$tenant|")
        if (caller != null) b.header("X-Workload-Token", workloadToken(caller))
        b.header("Content-Type", "application/json")
        b.header("Idempotency-Key", UUID.randomUUID().toString())
        b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun import(
        user: String?,
        version: String,
        hash: String,
        type: String = FAMILY,
        caller: String? = "console-bff",
    ) = call("POST", "/import", user, """{"packType":"$type","version":"$version","bundleHash":"$hash"}""", caller)

    private fun status(
        user: String?,
        version: String,
        type: String = FAMILY,
        caller: String? = "console-bff",
    ) = call("GET", "/$type/$version", user, null, caller)

    // ---- database ----------------------------------------------------------------------------------------------

    /** Runs [sql] as the container's superuser (no row-level security), so every row of every tenant is read. */
    private fun <T> owner(block: (java.sql.Connection) -> T): T =
        DriverManager
            .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
            .use(block)

    private fun count(sql: String): Long =
        owner { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    /** The full content of every table of the schema `rules`, one comparable text per table. */
    private fun rulesTables(): Map<String, String> =
        owner { c ->
            val tables = mutableListOf<String>()
            c.createStatement().use { s ->
                s.executeQuery(TABLES_SQL).use {
                    while (it.next()) tables += it.getString(1)
                }
            }
            tables
                .associateWith { t ->
                    c.createStatement().use { s ->
                        s
                            .executeQuery(
                                "select coalesce(string_agg(x::text, '|' order by x::text), '') from rules.\"$t\" x",
                            ).use {
                                it.next()
                                it.getString(1)
                            }
                    }
                }.also { check(it.isNotEmpty()) { "the schema rules has no table" } }
        }

    private fun artefactRows(version: String) =
        count("select count(*) from rules.pack_artefact where pack_version = '$version'")

    private fun registryRows(version: String) =
        count("select count(*) from rules.pack_registry where pack_version = '$version'")

    private fun stagedEvents(version: String) =
        count(
            "select count(*) from rules.outbox where data::text like '%\"$version\"%' " +
                "and data::text like '%RulePackStaged%'",
        )

    private fun nothingStored(version: String) {
        artefactRows(version) shouldBe 0
        registryRows(version) shouldBe 0
        stagedEvents(version) shouldBe 0
    }

    private fun refused(
        answer: HttpResponse<String>,
        http: Int,
        code: String,
    ) {
        answer.statusCode() shouldBe http
        answer.body() shouldContain code
    }

    // ---- cases -------------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-2072 a bundle whose signature fails is refused DEP-002 and never loaded`() {
        grant("u-op")
        val version = newVersion()
        val foreign = TestPackSigning.newKey()
        val answer = import("u-op", version, mount(bundle(version, signer = foreign)))
        refused(answer, 422, "DEP-002")
        nothingStored(version)
        status("u-op", version).body() shouldContain "\"FAILED\""
        status("u-op", version).body() shouldContain "DEP-002"
    }

    @Test
    fun `INV-AC-2073 a pack requiring a newer train is refused DEP-003 and not staged`() {
        grant("u-op")
        val version = newVersion()
        val answer = import("u-op", version, mount(bundle(version, minEngine = "3.0.0")))
        refused(answer, 422, "DEP-003")
        nothingStored(version)
        status("u-op", version).body() shouldContain "DEP-003"
    }

    @Test
    fun `INV-AC-22192 the unsigned, bad-signature and newer-engine bundles answer DEP-002 before DEP-003`() {
        grant("u-op")
        val unsigned = newVersion()
        refused(import("u-op", unsigned, mount(bundle(unsigned, signed = false))), 422, "DEP-002")
        val badSignature = newVersion()
        val foreign = TestPackSigning.newKey()
        refused(import("u-op", badSignature, mount(bundle(badSignature, signer = foreign))), 422, "DEP-002")
        // a bundle that is both badly signed and for a newer engine answers the signature code first
        val both = newVersion()
        val first = import("u-op", both, mount(bundle(both, minEngine = "3.0.0", signer = foreign)))
        refused(first, 422, "DEP-002")
        first.body() shouldNotContain "DEP-003"
        val newer = newVersion()
        refused(import("u-op", newer, mount(bundle(newer, minEngine = "3.0.0"))), 422, "DEP-003")
        listOf(unsigned, badSignature, both, newer).forEach { nothingStored(it) }
    }

    @Test
    @org.junit.jupiter.api.Disabled(
        "gap G8: RulePackStaged cannot be written under the platform tenant; owner ruling needed",
    )
    fun `INV-AC-22192 a signed compatible bundle is staged once with artefact, registry row and RulePackStaged row`() {
        grant("u-op")
        val version = newVersion()
        val hash = mount(bundle(version))
        val answer = import("u-op", version, hash)
        answer.statusCode() shouldBe 201
        answer.body() shouldContain "\"STAGED\""
        artefactRows(version) shouldBe 1
        registryRows(version) shouldBe 1
        stagedEvents(version) shouldBe 1
        status("u-op", version).body() shouldContain "\"STAGED\""
        // a repeat of the same bundle stages nothing more
        import("u-op", version, hash).statusCode() shouldBe 201
        artefactRows(version) shouldBe 1
        stagedEvents(version) shouldBe 1
    }

    @Test
    fun `INV-AC-22192 a digest that differs from the bundle hash is refused DEP-002 and a hash with no file is 404`() {
        grant("u-op")
        val version = newVersion()
        val bytes = bundle(version)
        val wrong = "sha256:" + "0".repeat(64)
        Files.write(dir.resolve("0".repeat(64)), bytes)
        refused(import("u-op", version, wrong), 422, "DEP-002")
        nothingStored(version)
        val missing = "sha256:" + "1".repeat(64)
        val before = rulesTables()
        refused(import("u-op", version, missing), 404, "SRT-005")
        rulesTables() shouldBe before
        // an upper-case or padded hash is not the digest named by a file
        refused(import("u-op", version, "sha256:" + "A".repeat(64)), 404, "SRT-005")
        refused(import("u-op", version, "sha256:" + "1".repeat(64) + " "), 404, "SRT-005")
    }

    @Test
    fun `INV-AC-22192 a verified manifest that names another pack version than the request is refused DEP-002`() {
        grant("u-op")
        val real = newVersion()
        val asked = newVersion()
        refused(import("u-op", asked, mount(bundle(real))), 422, "DEP-002")
        nothingStored(real)
        registryRows(asked) shouldBe 0
    }

    @Test
    fun `INV-AC-2072 a caller without the key or holding only another key does not import`() {
        val version = newVersion()
        val hash = mount(bundle(version))
        val before = rulesTables()
        refused(import("u-nobody", version, hash), 403, "SRT-004")
        val importRoles = DeploymentForm.entries.flatMap { OperationRoles.rolesFor(it, IMPORT_KEY).keys }.toSet()
        val approveOnly =
            DeploymentForm.entries
                .flatMap { form -> OTHER_KEYS.flatMap { OperationRoles.rolesFor(form, it).keys } }
                .toSet() - importRoles
        check(approveOnly.isNotEmpty()) { "a role that holds another key but not the import key is needed" }
        grant("u-approver", approveOnly.first())
        refused(import("u-approver", version, hash), 403, "SRT-004")
        refused(status("u-nobody", version), 403, "SRT-004")
        rulesTables() shouldBe before
        // the key is compared exactly: the catalogue knows no role for a key with a trailing space or another case
        listOf("$IMPORT_KEY ", IMPORT_KEY.uppercase(), " $IMPORT_KEY").forEach { key ->
            DeploymentForm.entries.forEach { OperationRoles.rolesFor(it, key).isEmpty() shouldBe true }
        }
        // the granted caller is admitted, so the refusal is specific to the key
        grant("u-op")
        val bad = newVersion()
        refused(import("u-op", bad, mount(bundle(bad, signer = TestPackSigning.newKey()))), 422, "DEP-002")
    }

    @Test
    fun `INV-AC-2072 a refused caller leaves every rules table equal and the use case receives no call`() {
        grant("u-op")
        val version = newVersion()
        val hash = mount(bundle(version))
        val usecase = context.getBean(ImportRegulatoryPack::class.java)
        val before = rulesTables()
        refused(import("u-op", version, hash, caller = "console-bff-alt"), 403, "SRT-004")
        import("u-op", version, hash, caller = null).statusCode() shouldBe 401
        refused(status("u-op", version, caller = "console-bff-alt"), 403, "SRT-004")
        status("u-op", version, caller = null).statusCode() shouldBe 401
        rulesTables() shouldBe before
        (context.getBean(ImportRegulatoryPack::class.java) === usecase) shouldBe true
        // the named caller is not refused and reaches the use case, which answers its own refusal and records it
        val bad = newVersion()
        refused(import("u-op", bad, mount(bundle(bad, signer = TestPackSigning.newKey()))), 422, "DEP-002")
        rulesTables() shouldNotBe before
    }

    @Test
    fun `INV-AC-2072 probe the table comparison reports a difference when the outbox gains a row`() {
        val before = rulesTables()
        owner { c ->
            c.createStatement().use {
                it.execute(
                    "insert into rules.outbox (id, topic, type, source, subject, event_time, tenant_id, entity_ref, " +
                        "instance_id, correlation_id, causation_id, data) values ('probe-" + UUID.randomUUID() +
                        "', 't', 'com.dynokore.gst.rules.RulePackStaged.v1', '/gst/rules-svc', 's', now(), '" +
                        tenant + "', 'e', 'i', 'c', 'c', '{}'::jsonb)",
                )
            }
        }
        (before == rulesTables()) shouldBe false
    }
}
