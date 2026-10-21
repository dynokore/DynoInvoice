package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.jooq.tables.references.PACK_ARTEFACT
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Column
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Grant
import com.dynokore.gst.rules.bootstrap.SchemaInspection.Table
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.Checksum
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackRegistryMigration
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.serviceruntime.migration.MigrationRunner
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.BindingKeyMigration
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * rules-svc's holder copy of the verified packs through [PersistedPackRegistry] on real PostgreSQL (INV-REQ-1195,
 * INV-REQ-1196): the kernel's own in-memory registry is the reference of the differential test.
 */
@SpringBootTest(classes = [RulesApplication::class])
class PersistedPackRegistryIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val platform: PlatformTenantBinding,
    @Autowired private val db: DSLContext,
) {
    private val probe: TenancyProbe = TenancyProbe.connect(pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val tenantA = TenantId(UUID.randomUUID())

    private fun value(
        tenant: TenantId,
        sql: String,
        vararg binds: Any?,
    ): Any? = probe.inTenant(tenant) { fetchValue(sql, *binds) }

    private val persisted get() = PersistedPackRegistry(db, tenants)
    private val digest = "ab".repeat(32)

    private fun verified(
        type: PackType,
        label: String,
        from: String,
        to: String?,
        tenant: TenantId? = null,
    ): VerifiedPack {
        val manifest =
            PackManifest(
                packId = PackId("${type.value}-pack"),
                packType = type,
                version = PackVersionLabel(label),
                effectiveFrom = LocalDate.parse(from),
                effectiveTo = to?.let(LocalDate::parse),
                minEngineVersion = "1.0.0",
                contentChecksums = mapOf("contents" to Checksum(digest)),
            )
        val artefact =
            PackArtefact(
                manifest = "{\"version\": \"$label\"}".toByteArray(),
                checksums = "{\"contents\": \"$digest\"}".toByteArray(),
                signature = byteArrayOf(7, label.length.toByte(), -1),
                contents = "{\"rules\": [\"$label\"]}".toByteArray(),
            )
        return VerifiedPack.of(PackVerificationOutcome.Verified(manifest, "anchor-$label"), artefact, tenant)
    }

    /** Registers under the binding the pack's owner has: the platform's, or the tenant's own. */
    private fun store(pack: VerifiedPack) {
        val tenant = pack.version.tenant
        if (tenant == null) {
            platform.bindPlatform("rules-test") { tx.inTransaction { persisted.register(pack) } }
        } else {
            tenants.bind(tenant, "rules-actor") { tx.inTransaction { persisted.register(pack) } }
        }
    }

    private fun <T> asTenant(block: PersistedPackRegistry.() -> T): T =
        tenants.bind(tenantA, "rules-test") { tx.inTransaction { persisted.block() } }

    @Test
    fun `INV-AC-2182 the persisted registry resolves as the in-memory reference does`() {
        val type = PackType("DIFF_" + UUID.randomUUID().toString().take(8))
        val timeline =
            listOf(
                verified(type, "p1", "2026-01-01", "2026-07-01"),
                verified(type, "p2", "2026-07-01", null),
                verified(type, "t1", "2026-03-01", "2026-05-01", tenantA),
            )
        val reference = InMemoryPackRegistry()
        timeline.forEach {
            store(it)
            reference.register(it)
        }
        val dates =
            timeline.flatMap { pack ->
                val from = pack.version.effectiveFrom
                val to = pack.version.effectiveTo
                listOfNotNull(from.minusDays(1), from, to?.minusDays(1), to, to?.plusDays(1))
            } + LocalDate.parse("2099-01-01")
        dates.distinct().forEach { date ->
            asTenant { resolve(type, tenantA, date) } shouldBe reference.resolve(type, tenantA, date)
            asTenant { resolve(type, null, date) } shouldBe reference.resolve(type, null, date)
        }
        asTenant { resolve(type, tenantA, LocalDate.parse("2026-04-01")) }?.tenant shouldBe tenantA
        // a look-alike: the platform row is not returned for another pack type
        asTenant { resolve(PackType(type.value + "_X"), tenantA, LocalDate.parse("2026-04-01")) } shouldBe null
    }

    @Test
    fun `INV-AC-2182 the persisted registry round trip returns the signed members and the anchor`() {
        val type = PackType("TRIP_" + UUID.randomUUID().toString().take(8))
        val packs = listOf(verified(type, "r1", "2026-01-01", null), verified(type, "r2", "2026-02-01", null, tenantA))
        packs.forEach { store(it) }
        packs.forEach { pack ->
            val read = asTenant { artefactOf(pack.version) }!!
            read.signature.contentEquals(pack.artefact.signature) shouldBe true
            read shouldBe PackArtefact(read.manifest, read.checksums, pack.artefact.signature, read.contents)
            val mapper =
                tools.jackson.databind.json
                    .JsonMapper()
            mapper.readTree(read.manifest) shouldBe mapper.readTree(pack.artefact.manifest)
            mapper.readTree(read.checksums) shouldBe mapper.readTree(pack.artefact.checksums)
            mapper.readTree(read.contents) shouldBe mapper.readTree(pack.artefact.contents)
            value(
                tenantA,
                "select trusted_anchor from rules.pack_artefact where pack_type = ? and pack_version = ?",
                type.value,
                pack.version.version.value,
            ) shouldBe pack.trustedAnchorLabel
        }
        // a version label with a trailing space is another version
        asTenant { artefactOf(verified(type, "r1 ", "2026-01-01", null).version) } shouldBe null
    }

    @Test
    fun `INV-AC-2182 re-registering a lineage is a no-op for the same manifest and refused for another`() {
        val type = PackType("REG_" + UUID.randomUUID().toString().take(8))
        val first = verified(type, "v1", "2026-01-01", null)
        store(first)
        store(first)
        val rows = "select count(*) from rules.pack_artefact where pack_type = '${type.value}'"
        value(tenantA, rows) shouldBe 1L
        shouldThrow<IllegalArgumentException> { store(verified(type, "v1", "2026-02-01", null)) }
        value(tenantA, rows) shouldBe 1L
        asTenant { resolve(type, null, LocalDate.parse("2026-01-01")) } shouldBe first.version
        // a tenant pack is its own lineage
        store(verified(type, "v1", "2026-02-01", null, tenantA))
        value(tenantA, rows) shouldBe 2L
    }

    @Test
    fun `INV-AC-2182 register of a bare PackVersion throws and writes nothing`() {
        val type = PackType("BARE_" + UUID.randomUUID().toString().take(8))
        val version = verified(type, "b1", "2026-01-01", null).version
        shouldThrow<UnsupportedOperationException> { asTenant { register(version) } }
        value(tenantA, "select count(*) from rules.pack_artefact where pack_type = '${type.value}'") shouldBe 0L
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)

        private val pg get() = PostgresRules.pg
    }
}
