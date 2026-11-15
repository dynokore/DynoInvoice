package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.domain.resolution.PackTypes
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
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
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
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val JOIN_IMPORT_KEY = "regulatory-pack.import-verify"
private const val JOIN_TYPE = "SCOPE"
private const val OUTBOX_TYPE = "com.dynokore.gst.platform.RulePackStaged.v1"

/** What the one import of this class produced: the manifest packId, the pack version label and the answer. */
private data class Imported(
    val packId: String,
    val version: String,
    val answer: HttpResponse<String>,
)

/**
 * INV-T-23196 (INV-REQ-771): the pack identifier of an imported pack is one string in the `RulePackStaged` payload,
 * the `rules.pack_artefact` row and the pack version the persisted registry resolves. The real import runs over HTTP
 * on real PostgreSQL; the manifest packId equals neither the pack type nor the pack version. One bundle is imported
 * for the whole class, because the registry resolves the latest pack of a type on a date, so a second SCOPE pack with
 * the same effective date would make the resolved pack ambiguous.
 */
@SpringBootTest(
    classes = [PolicyFixtures::class, ImportFixtures::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PackIdentifierStagingJoinBoundaryTest(
    @LocalServerPort private val port: Int,
    @Autowired private val replica: AuthorityReplica,
    @Autowired private val clock: MovableClock,
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val platform: PlatformTenantBinding,
    @Autowired private val db: DSLContext,
) {
    companion object {
        private val dir: Path = Files.createTempDirectory("rules-join-bundles")
        private val versions = AtomicLong(0)
        private val epoch = AtomicLong(1)
        private var imported: Imported? = null

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

    @BeforeEach
    fun freshReplica() {
        ReplicaFallbackDouble.reset()
        replica.tracker.admitIncarnation(epoch.get())
        replica.onSnapshotComplete(0)
        replica.tracker.onApplied(versions.get())
    }

    private fun importRole(): String {
        val saas = OperationRoles.rolesFor(DeploymentForm.SAAS, JOIN_IMPORT_KEY).keys
        val private = OperationRoles.rolesFor(DeploymentForm.PRIVATE, JOIN_IMPORT_KEY).keys
        check((saas + private).isNotEmpty()) { "$JOIN_IMPORT_KEY has no role in the catalogue" }
        return (private.ifEmpty { saas }).first()
    }

    private fun grant(user: String) {
        replica.accept(
            AuthorityChanged(
                tenant,
                user,
                versions.incrementAndGet(),
                AuthorityRecord.RoleGrantRecord(
                    "g-$user",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        importRole(),
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

    // ---- bundle ------------------------------------------------------------------------------------------------

    private fun sha(bytes: ByteArray) =
        java.util.HexFormat
            .of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

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

    /** A compatible bundle of pack type SCOPE signed by the train key, with the given manifest packId. */
    private fun bundle(
        packId: String,
        version: String,
    ): ByteArray {
        val contents = """{"rules":[]}""".toByteArray()
        val manifest =
            (
                """{"packId":"$packId","packType":"$JOIN_TYPE","version":"$version","effectiveFrom":"2027-01-01",""" +
                    """"effectiveTo":null,"minEngineVersion":"1.0.0"}"""
            ).toByteArray()
        val checksums = """{"contents":"${sha(contents)}"}""".toByteArray()
        return zip(
            linkedMapOf(
                "manifest.json" to manifest,
                "checksums.json" to checksums,
                "contents.json" to contents,
                "signature.p7s" to TestTrain.key.sign(manifest + checksums),
            ),
        )
    }

    private fun mount(bytes: ByteArray): String {
        val digest = sha(bytes)
        Files.write(dir.resolve(digest), bytes)
        return "sha256:$digest"
    }

    private fun import(
        user: String,
        version: String,
        hash: String,
    ): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs/import"))
        b.header("Authorization", "Bearer $user|$tenant|")
        b.header("X-Workload-Token", workloadToken("console-bff"))
        b.header("Content-Type", "application/json")
        b.header("Idempotency-Key", UUID.randomUUID().toString())
        b.POST(
            HttpRequest.BodyPublishers.ofString(
                """{"packType":"$JOIN_TYPE","version":"$version","bundleHash":"$hash"}""",
            ),
        )
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** The one import of this class, made by the first case that runs. */
    private fun theImport(): Imported =
        synchronized(Companion) {
            imported ?: run {
                grant("u-join")
                val suffix = UUID.randomUUID().toString().take(8).uppercase()
                val packId = "gst-join-$suffix"
                val version = "j${suffix.lowercase()}"
                Imported(packId, version, import("u-join", version, mount(bundle(packId, version))))
                    .also { imported = it }
            }
        }

    // ---- database ----------------------------------------------------------------------------------------------

    private fun <T> owner(block: (java.sql.Connection) -> T): T =
        DriverManager
            .getConnection(PostgresRules.pg.jdbcUrl, PostgresRules.pg.username, PostgresRules.pg.password)
            .use(block)

    private fun strings(
        sql: String,
        bind: String,
    ): List<String> =
        owner { c ->
            c.prepareStatement(sql).use { st ->
                st.setString(1, bind)
                st.executeQuery().use {
                    buildList { while (it.next()) add(it.getString(1)) }
                }
            }
        }

    /** The `packIdentifier` member of every RulePackStaged row whose payload names the pack version. */
    private fun stagedIdentifiers(version: String) =
        strings(
            "select data->>'packIdentifier' from rules.outbox where type = '$OUTBOX_TYPE' " +
                "and data::text like '%' || ? || '%'",
            "\"$version\"",
        )

    private fun storedIdentifiers(version: String) =
        strings("select pack_id from rules.pack_artefact where pack_version = ?", version)

    private fun resolved() =
        platform.bindPlatform("rules-join-test") {
            tx.inTransaction {
                PersistedPackRegistry(db, tenants).resolve(PackTypes.SCOPE, null, LocalDate.parse("2027-01-01"))
            }
        }

    // ---- cases -------------------------------------------------------------------------------------------------

    @Test
    fun `INV-AC-23195 (a) the RulePackStaged payload carries the manifest packId`() {
        val i = theImport()
        i.answer.statusCode() shouldBe 201
        i.answer.body() shouldContain "\"STAGED\""
        stagedIdentifiers(i.version) shouldBe listOf(i.packId)
    }

    @Test
    fun `INV-AC-23195 (b) the pack_id column of the stored artefact is the manifest packId`() {
        val i = theImport()
        i.answer.statusCode() shouldBe 201
        storedIdentifiers(i.version) shouldBe listOf(i.packId)
    }

    @Test
    fun `INV-AC-23195 (c) the registry resolves a pack version whose packId is the manifest packId`() {
        val i = theImport()
        i.answer.statusCode() shouldBe 201
        val pack = resolved()
        pack shouldNotBe null
        pack!!.manifest.packId.value shouldBe i.packId
        pack.version.value shouldBe i.version
    }

    @Test
    fun `INV-AC-23195 (d) the identifier equals neither the pack type nor the pack version in any of the three`() {
        val i = theImport()
        i.answer.statusCode() shouldBe 201
        val all = stagedIdentifiers(i.version) + storedIdentifiers(i.version) + resolved()!!.manifest.packId.value
        all.size shouldBe 3
        all.forEach {
            it shouldBe i.packId
            it shouldNotBe JOIN_TYPE
            it shouldNotBe i.version
        }
    }
}
