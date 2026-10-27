package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.LicenceRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.ProvisioningRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.RegionListJooq
import com.dynokore.gst.controlPlane.application.commercial.ProvisioningRecord
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContainIgnoringCase
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The region list, the provisioning requests and the licences as the runtime login (CS-19, INV-Q-8685 A). */
class ProvisioningPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC)
    private val dsl = PostgresDb.dsl()
    private val probe = PostgresDb.probe
    private val regions = RegionListJooq(dsl)
    private val provisionings = ProvisioningRepositoryJooq(dsl, clock)

    private fun has(
        table: String,
        privilege: String,
    ) = probe.fetchValue("select has_table_privilege(current_user, ?, ?)", "controlplane.$table", privilege) == true

    private fun record(
        contract: String = "CTR-${UUID.randomUUID().toString().take(8)}",
        region: String = "SG",
        key: UUID = UUID.randomUUID(),
    ) = ProvisioningRecord(
        TenantProvisioning(
            CommercialRef(contract),
            CommercialRef("TEN-${UUID.randomUUID().toString().take(8)}"),
            "Acme Holdings Pte Ltd",
            PlanRef("PLAN-STANDARD"),
            "admin@acme.example",
            RegionKey(region),
            ProvisioningStatus.PENDING,
            "signed.ref",
            Instant.parse("2026-11-03T08:00:00Z"),
        ),
        key,
        "fingerprint",
    )

    @Test
    fun `INV-AC-8572 INV-AC-1331 the region list holds SG with the seeded statement, compared by equality`() {
        regions.contains(RegionKey("SG")) shouldBe true
        regions.contains(RegionKey("XX")) shouldBe false
        regions.contains(RegionKey("sg")) shouldBe false
        regions.contains(RegionKey("SG ")) shouldBe false
        val statement = checkNotNull(regions.statement(RegionKey("SG")))
        statement shouldBe "Tenant data is stored and processed in Singapore."
        regions.statement(RegionKey("sg")) shouldBe null
        listOf("amazon", "google", "microsoft", "aws", "azure").forEach { statement shouldNotContainIgnoringCase it }
        dsl.fetchValue("select display_name from controlplane.region where region_key = 'SG'") shouldBe "Singapore"
        dsl.fetchValue("select count(*) from controlplane.region") shouldBe 1
    }

    @Test
    fun `INV-Q-8685 the tables are platform-scoped and the runtime login can only SELECT the region tables`() {
        listOf("region", "region_statement", "tenant_provisioning_request", "licence").forEach { table ->
            dsl.fetchValue(
                "select obj_description(?::regclass, 'pg_class')",
                "controlplane.$table",
            ) shouldBe "platform-scoped: no tenant data"
        }
        listOf("region", "region_statement").forEach { table ->
            has(table, "SELECT") shouldBe true
            has(table, "INSERT") shouldBe false
            has(table, "UPDATE") shouldBe false
            has(table, "DELETE") shouldBe false
        }
        listOf(
            "insert into controlplane.region (region_key, display_name) values ('XX', 'Xland')",
            "update controlplane.region set display_name = 'Sg' where region_key = 'SG'",
            "delete from controlplane.region where region_key = 'SG'",
            "insert into controlplane.region_statement (region_key, statement) values ('SG', 'x')",
            "update controlplane.region_statement set statement = 'x'",
            "delete from controlplane.region_statement",
        ).forEach { statement -> shouldThrow<Exception> { dsl.execute(statement) } }
    }

    @Test
    fun `a request round-trips, its status is marked CREATED and its region cannot be updated`() {
        val saved = record()
        provisionings.save(saved, "carol")
        provisionings.findByContract(saved.provisioning.contractReference) shouldBe saved
        provisionings.findByKey(saved.idempotencyKey, "carol") shouldBe saved
        provisionings.findByKey(saved.idempotencyKey, "bob") shouldBe null
        provisionings.markCreated(saved.provisioning.contractReference)
        provisionings.findByContract(saved.provisioning.contractReference)?.provisioning?.status shouldBe
            ProvisioningStatus.CREATED
        provisionings.findByContract(saved.provisioning.contractReference)?.provisioning?.dataRegion shouldBe
            RegionKey("SG")
        shouldThrow<Exception> {
            dsl.execute(
                "update controlplane.tenant_provisioning_request set data_region = 'XX' where contract_reference = ?",
                saved.provisioning.contractReference.value,
            )
        }
        has("tenant_provisioning_request", "DELETE") shouldBe false
    }

    @Test
    fun `a request for a region off the list is refused by the table and nothing is stored`() {
        val offList = record(region = "XX")
        shouldThrow<Exception> { provisionings.save(offList, "carol") }
        provisionings.findByContract(offList.provisioning.contractReference) shouldBe null
    }

    @Test
    fun `a licence round-trips its exact signed bytes and one term holds one licence`() {
        val repository = LicenceRepositoryJooq(dsl)
        val installation = CommercialRef("INST-${UUID.randomUUID().toString().take(8)}")
        val licence =
            Licence(
                CommercialRef("LIC-${UUID.randomUUID().toString().take(8)}"),
                installation,
                "2026-10-01/2027-09-30",
                Instant.parse("2026-10-27T08:00:00Z"),
                byteArrayOf(0, 1, 2, -1, 127),
            )
        repository.save(licence, "carol")
        repository.findByRef(licence.licenceRef) shouldBe licence
        repository.findByTerm(installation, "2026-10-01/2027-09-30") shouldBe licence
        repository.findByTerm(installation, "2027-10-01/2028-09-30") shouldBe null
        shouldThrow<Exception> { repository.save(licence.copy(licenceRef = CommercialRef("LIC-OTHER")), "carol") }
        has("licence", "UPDATE") shouldBe false
        has("licence", "DELETE") shouldBe false
    }
}
