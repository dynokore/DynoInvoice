package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.persistence.PersistedRegulatoryPackStore
import com.dynokore.gst.rules.application.ApproveRegulatoryPack
import com.dynokore.gst.rules.application.ApproveRegulatoryPackResult
import com.dynokore.gst.rules.domain.pack.ArmingGate
import com.dynokore.platform.rulepackkernel.Checksum
import com.dynokore.platform.rulepackkernel.PackArtefact
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVerificationOutcome
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.VerifiedPack
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.util.UUID

private const val STAGER = "tenancy-probe"
private const val PACK_TYPE = "SCHEMATRON"
private const val DIGEST = "ab"
private const val FIXTURE_ENGINE = "1.0.0"

/**
 * INV-T-4975 (INV-REQ-489): the approval over the real registry. `rules.approve_platform_pack` moves a STAGED platform
 * pack to APPROVED under the platform binding with the bound user ID, and nothing else; the store reads the staging
 * user ID and whether a COMPLETE dry-run delta exists for the pack type and version. The database is the one
 * PostgresRules builds from every migration, the service on its runtime login.
 */
@SpringBootTest(classes = [RulesApplication::class])
class RegulatoryPackApprovalPostgresIT(
    @Autowired private val tx: TransactionRunner,
    @Autowired private val tenants: TenantContext,
    @Autowired private val platform: PlatformTenantBinding,
    @Autowired private val db: DSLContext,
    @Autowired private val context: ApplicationContext,
) {
    private val probe: TenancyProbe =
        TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
    private val store get() = PersistedRegulatoryPackStore(db, platform, tx)

    private fun platformValue(
        sql: String,
        vararg binds: Any?,
    ): Any? = probe.inTenant(TenantId.PLATFORM) { fetchValue(sql, *binds) }

    private fun version() = "t" + UUID.randomUUID().toString().take(8)

    private fun seed(
        version: String,
        status: String = "STAGED",
        from: String = "2027-01-01",
    ) {
        platformValue(
            "select rules.register_platform_pack(?::uuid, 'SCHEMATRON-pack', ?, ?, ?, ?::date, null, '1.0.0', null)",
            UUID.randomUUID().toString(),
            PACK_TYPE,
            version,
            status,
            from,
        )
    }

    private fun addDryRun(
        version: String,
        state: String = "COMPLETE",
    ) {
        val completed = if (state == "COMPLETE") "clock_timestamp()" else "null"
        val changed = if (state == "COMPLETE") "0" else "null"
        platformValue(
            "insert into rules.dry_run_delta (id, tenant_id, pack_type, pack_version, state, started_by, " +
                "completed_at, changed_count, unavailable_reason) values (?::uuid, ?::uuid, ?, ?, ?, 'u-dry', " +
                "$completed, $changed, ${if (state == "SAMPLE_UNAVAILABLE") "'none'" else "null"}) returning id",
            UUID.randomUUID().toString(),
            TenantId.PLATFORM.toString(),
            PACK_TYPE,
            version,
            state,
        )
    }

    private fun column(
        name: String,
        version: String,
    ) = platformValue(
        "select $name from rules.pack_registry where pack_type = ? and pack_version = ?",
        PACK_TYPE,
        version,
    )

    private fun approve(
        version: String,
        approver: String,
    ) = ApproveRegulatoryPack(store) { true }.execute(PACK_TYPE, version, approver)

    @Test
    fun `INV-AC-7966 activation before any dry run and before approval is refused on the stored status and delta`() {
        val v = version()
        seed(v)
        val found = checkNotNull(store.find(PACK_TYPE, v))
        found.dryRunDeltaExists shouldBe false
        ArmingGate.check(found.status, found.dryRunDeltaExists) shouldBe
            ArmingGate.Outcome.Refused(ArmingGate.Reason.NO_DRY_RUN)
        addDryRun(v)
        val withDelta = checkNotNull(store.find(PACK_TYPE, v))
        ArmingGate.check(withDelta.status, withDelta.dryRunDeltaExists) shouldBe
            ArmingGate.Outcome.Refused(ArmingGate.Reason.NOT_APPROVED)
        column("status", v) shouldBe "STAGED"
        approve(v, "u-approver") shouldBe ApproveRegulatoryPackResult.Approved
        column("status", v) shouldBe "APPROVED"
        column("approved_by", v) shouldBe "u-approver"
        (column("approved_at", v) != null) shouldBe true
        val approved = checkNotNull(store.find(PACK_TYPE, v))
        ArmingGate.check(approved.status, approved.dryRunDeltaExists) shouldBe ArmingGate.Outcome.PassedToFence
    }

    @Test
    fun `INV-AC-7966 the store reads the staging user ID and only a COMPLETE run counts`() {
        val v = version()
        seed(v)
        addDryRun(v, "SAMPLE_UNAVAILABLE")
        val found = checkNotNull(store.find(PACK_TYPE, v))
        found.stagedByUserId shouldBe STAGER
        found.dryRunDeltaExists shouldBe false
        approve(v, "u-approver") shouldBe ApproveRegulatoryPackResult.NoDryRun
        column("status", v) shouldBe "STAGED"
    }

    @Test
    fun `INV-AC-7966 the approval by a user other than registered_by is recorded, the same ID is refused`() {
        val v = version()
        seed(v)
        addDryRun(v)
        approve(v, STAGER) shouldBe ApproveRegulatoryPackResult.SelfApproval
        column("status", v) shouldBe "STAGED"
        column("approved_by", v) shouldBe null
        approve(v, STAGER + "2") shouldBe ApproveRegulatoryPackResult.Approved
        column("approved_by", v) shouldBe STAGER + "2"
        approve(v, "u-later") shouldBe ApproveRegulatoryPackResult.AlreadyApproved
        column("approved_by", v) shouldBe STAGER + "2"
    }

    @Test
    fun `INV-AC-7966 the function approves only a STAGED pack and only under the platform tenant`() {
        val armed = version()
        seed(armed, "ARMED")
        addDryRun(armed)
        shouldThrow<Exception> { store.markApproved(PACK_TYPE, armed, "u-approver") }
        column("status", armed) shouldBe "ARMED"
        column("approved_by", armed) shouldBe null
        val staged = version()
        seed(staged)
        shouldThrow<Exception> {
            tenants.bind(TenantId(UUID.randomUUID()), "u-approver") {
                tx.inTransaction {
                    db.fetch("select rules.approve_platform_pack(?, ?)", PACK_TYPE, staged)
                }
            }
        }
        column("status", staged) shouldBe "STAGED"
    }

    @Test
    fun `INV-AC-7966 the table refuses a REGULATORY row in APPROVED without an approver`() {
        shouldThrow<Exception> { seed(version(), "APPROVED") }
    }

    @Test
    fun `INV-AC-7962 two SCHEMATRON versions resolve by effective date and the context is unchanged`() {
        val type =
            PackType(
                "SCHEMATRON_" +
                    UUID
                        .randomUUID()
                        .toString()
                        .take(8)
                        .uppercase(),
            )
        val earlier = verified(type, "e1", "2026-01-01", "2026-07-01")
        val later = verified(type, "l1", "2026-07-01", null)
        val registry = PersistedPackRegistry(db, tenants)
        platform.bindPlatform("rules-test") { tx.inTransaction { registry.register(earlier) } }
        val contextBefore = context
        val beansBefore = context.getBean(ApproveRegulatoryPack::class.java)

        val v = version()
        seed(v, from = "2026-07-01")
        addDryRun(v)
        platform.bindPlatform("rules-test") { tx.inTransaction { registry.register(later) } }
        approve(v, "u-approver") shouldBe ApproveRegulatoryPackResult.Approved

        fun resolved(date: String) =
            tenants.bind(TenantId(UUID.randomUUID()), "rules-test") {
                tx.inTransaction { registry.resolve(type, null, LocalDate.parse(date))?.version?.value }
            }
        resolved("2026-03-01") shouldBe earlier.version.version.value
        resolved("2026-09-01") shouldBe later.version.version.value
        (context === contextBefore) shouldBe true
        (context.getBean(ApproveRegulatoryPack::class.java) === beansBefore) shouldBe true
    }

    private fun verified(
        type: PackType,
        label: String,
        from: String,
        to: String?,
    ): VerifiedPack {
        val checksum = DIGEST.repeat(32)
        val manifest =
            PackManifest(
                packId = PackId("${type.value}-pack"),
                packType = type,
                version = PackVersionLabel(label),
                effectiveFrom = LocalDate.parse(from),
                effectiveTo = to?.let(LocalDate::parse),
                minEngineVersion = FIXTURE_ENGINE,
                contentChecksums = mapOf("contents" to Checksum(checksum)),
            )
        val artefact =
            PackArtefact(
                manifest = "{\"version\": \"$label\"}".toByteArray(),
                checksums = "{\"contents\": \"$checksum\"}".toByteArray(),
                signature = byteArrayOf(7, label.length.toByte(), -1),
                contents = "{\"rules\": [\"$label\"]}".toByteArray(),
            )
        return VerifiedPack.of(PackVerificationOutcome.Verified(manifest, "anchor-$label"), artefact, null)
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = PostgresRules.register(registry)
    }
}
