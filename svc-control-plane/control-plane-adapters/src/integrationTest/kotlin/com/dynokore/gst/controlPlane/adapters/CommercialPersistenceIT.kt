package com.dynokore.gst.controlPlane.adapters

import com.dynokore.gst.controlPlane.adapters.persistence.PlanRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.QualificationRepositoryJooq
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The commercial tables as the runtime login (CS-19): rows round-trip, and the login holds no UPDATE or DELETE. */
class CommercialPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-22T08:30:00Z"), ZoneOffset.UTC)
    private val dsl = PostgresDb.dsl()
    private val probe = PostgresDb.probe

    private fun has(
        table: String,
        privilege: String,
    ) = probe.fetchValue("select has_table_privilege(current_user, ?, ?)", "controlplane.$table", privilege) == true

    @Test
    fun `a plan version round-trips and a second insert of the same version is refused`() {
        val repository = PlanRepositoryJooq(dsl, clock)
        val tenant = CommercialRef("TEN-${UUID.randomUUID().toString().take(8)}")
        val plan = Plan(tenant, PlanVersion("2026.1"), Entitlements(25, 100000))
        repository.save(plan, "carol")
        repository.find(tenant, PlanVersion("2026.1")) shouldBe plan
        repository.find(tenant, PlanVersion("2026.2")) shouldBe null
        shouldThrow<Exception> { repository.save(plan, "carol") }
    }

    @Test
    fun `a qualification round-trips`() {
        val repository = QualificationRepositoryJooq(dsl, clock)
        val installation = CommercialRef("INST-${UUID.randomUUID().toString().take(8)}")
        val qualification = Qualification(CommercialRef("CUST-1"), installation, QualificationOutcome.NOT_QUALIFIED)
        repository.save(qualification, "carol")
        repository.find(CommercialRef("CUST-1"), installation) shouldBe qualification
    }

    @Test
    fun `INV-Q-8689 a pair keeps every record, the latest governs find and latestFor`() {
        val repository = QualificationRepositoryJooq(dsl, clock)
        val installation = CommercialRef("INST-${UUID.randomUUID().toString().take(8)}")
        val customer = CommercialRef("CUST-1")
        repository.save(Qualification(customer, installation, QualificationOutcome.QUALIFIED), "carol")
        repository.save(Qualification(customer, installation, QualificationOutcome.NOT_QUALIFIED), "carol")
        repository.find(customer, installation)?.outcome shouldBe QualificationOutcome.NOT_QUALIFIED
        repository.latestFor(installation)?.outcome shouldBe QualificationOutcome.NOT_QUALIFIED
        dsl.fetchCount(
            org.jooq.impl.DSL
                .table(
                    org.jooq.impl.DSL
                        .name("controlplane", "qualification"),
                ),
            org.jooq.impl.DSL
                .field(
                    org.jooq.impl.DSL
                        .name("installation_ref"),
                ).eq(installation.value),
        ) shouldBe 2
    }

    @Test
    fun `the runtime login reads and inserts the commercial tables and changes none of their rows`() {
        listOf("plan_version", "qualification").forEach { table ->
            has(table, "SELECT") shouldBe true
            has(table, "INSERT") shouldBe true
            has(table, "UPDATE") shouldBe false
            has(table, "DELETE") shouldBe false
        }
    }
}
