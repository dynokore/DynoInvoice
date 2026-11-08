package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.contract.model.ElectionRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.domain.ElectionKeys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * INV-AC-18120 (INV-REQ-369): the exempt-purchase election through `recordElection` of the real context over PostgreSQL
 * with forced row-level security. The effective date is fixed, so no assertion depends on the wall clock.
 */
class ExemptPurchaseElectionApiTest {
    private val october = LocalDate.of(2026, 10, 1)
    private val basis = "Legal fees relate to exempt supplies"
    private val codes = linkedSetOf("TX-N33", "TX-ESS")

    private fun run(block: (ElectionEnv, EntityResponse) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val env = ProfileEnv(context)
            val e = ElectionEnv(env)
            env.withEntity { _, entity ->
                e.usualPeople(entity)
                block(e, entity)
            }
        }
    }

    private fun ElectionEnv.exempt(
        entity: EntityResponse,
        caller: String,
        election: String = ElectionKeys.EXEMPT_PURCHASE_CODES,
        taxCodes: Set<String>? = codes,
        text: String? = basis,
    ) = refusalOrResponse {
        people.caller = caller
        env.api.recordElection(entity.entityRef, UUID.randomUUID(), ElectionRequest(election, october, taxCodes, text))
    }

    /** The status and code of a refusal, or the status of the answer. */
    private fun refusalOrResponse(block: () -> org.springframework.http.ResponseEntity<*>): Pair<Int, String?> =
        try {
            block().statusCode.value() to null
        } catch (refused: com.dynokore.gst.tenancy.adapters.http.ApiRefusal) {
            refused.code.httpStatus to refused.code.code
        }

    private fun rows(entity: EntityResponse): List<List<String>> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select array_to_string(tax_codes, ','), basis, effective_from::text, recorded_by " +
                        "from tenancy.exempt_purchase_election where entity_ref = ? order by id",
                ).use { s ->
                    s.setString(1, entity.entityRef)
                    s.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) (1..4).map { rs.getString(it) } else null
                        }.toList()
                    }
                }
        }

    private fun audits(entity: EntityResponse): List<String> =
        BoundaryFixture.asSuperuser { c ->
            c
                .prepareStatement(
                    "select data::text from tenancy.outbox where topic = 'gst.platform.audit-entry-recorded.v1' " +
                        "and data::text like ? and data::text like ?",
                ).use { s ->
                    s.setString(1, "%${entity.entityRef}%")
                    s.setString(2, "%election.exempt-purchase.recorded%")
                    s.executeQuery().use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                    }
                }
        }

    private fun nothingStored(
        e: ElectionEnv,
        entity: EntityResponse,
    ) {
        assertThat(rows(entity)).isEmpty()
        assertThat(audits(entity)).isEmpty()
        assertThat(e.profileEvents().filter { it.toString().contains("exemptPurchase") }).isEmpty()
    }

    @Test
    fun `INV-AC-18120 U1 records the election with 201 and U2 the blank basis and the lowercase code are refused`() {
        run { e, entity ->
            // U2 holds no permission: 403 SRT-004, nothing stored
            assertThat(e.exempt(entity, NOBODY)).isEqualTo(403 to "SRT-004")
            nothingStored(e, entity)
            // U1: 201
            assertThat(e.exempt(entity, ADMIN).first).isEqualTo(201)
            val stored = rows(entity).single()
            assertThat(stored).containsExactly("TX-N33,TX-ESS", basis, "2026-10-01", ADMIN)
            assertThat(stored.last()).doesNotContain("entity.admin")
            val audit = audits(entity).single()
            assertThat(audit).contains(ADMIN)
            // the published node.profile entries
            val events = e.profileEvents().filter { it.toString().contains("exemptPurchaseCodes") }
            assertThat(events).hasSize(1)
            val published =
                events
                    .single()["node"]["profile"]
                    .values()
                    .map { Triple(it["attribute"].asString(), it["value"].asString(), it["effectiveDate"].asString()) }
            assertThat(published).contains(
                Triple("exemptPurchaseCodes", "TX-ESS,TX-N33", "2026-10-01"),
                Triple("exemptPurchaseBasis", basis, "2026-10-01"),
            )
            // the structure read
            val read =
                e.env.api
                    .getEntityStructure(entity.entityRef)
                    .body!!
                    .node!!.profile
                    .map { Triple(it.attribute, it.value, it.effectiveDate.toString()) }
            assertThat(read.filter { it.first.startsWith("exemptPurchase") })
                .containsExactlyInAnyOrderElementsOf(published.filter { it.first.startsWith("exemptPurchase") })
        }
    }

    @Test
    fun `INV-AC-18120 the blank basis and the lowercase code are each 400 SRT-001 and store nothing`() {
        run { e, entity ->
            assertThat(e.exempt(entity, ADMIN, text = "   ")).isEqualTo(400 to "SRT-001")
            assertThat(e.exempt(entity, ADMIN, taxCodes = linkedSetOf("tx-n33"))).isEqualTo(400 to "SRT-001")
            nothingStored(e, entity)
        }
    }

    @Test
    fun `INV-AC-18120 look-alikes fail for the key EXEMPT_PURCHASE_CODE and taxCodes sent with INVOICE_DATE`() {
        run { e, entity ->
            assertThat(e.exempt(entity, ADMIN, election = "EXEMPT_PURCHASE_CODE").first).isEqualTo(400)
            assertThat(e.exempt(entity, ADMIN, election = ElectionKeys.INVOICE_DATE)).isEqualTo(400 to "SRT-001")
            nothingStored(e, entity)
        }
    }

    @Test
    fun `INV-AC-18120 a caller holding only roles but not the permission is 403 SRT-004 and stores nothing`() {
        run { e, entity ->
            assertThat(e.exempt(entity, OTHER_ADMIN)).isEqualTo(403 to "SRT-004")
            assertThat(e.exempt(entity, APPROVER)).isEqualTo(403 to "SRT-004")
            nothingStored(e, entity)
        }
    }
}
