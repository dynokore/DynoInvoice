package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.JooqHeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.util.UUID

/**
 * INV-AC-21337 (INV-T-21338): the import lists the held documents of each written party in `MasterDataResolved`
 * (INV-REQ-941), over the real context and a real PostgreSQL; the held rows are recorded through [JooqHeldDocuments].
 */
class ImportHeldDocumentsTest {
    private val json = JsonMapper.builder().build()
    private val prefix = "/internal/v1/masterdata"

    private fun uuid(n: Int) = UUID.fromString("00000000-0000-0000-0000-" + n.toString().padStart(UUID_TAIL, '0'))

    private fun hash(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }

    private fun RegistryEnv.post(
        path: String,
        body: String,
    ): Pair<Int, String> =
        ce.inTenant(OPERATOR) {
            val mvc: MockMvc =
                MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()
            val response =
                mvc
                    .perform(
                        MockMvcRequestBuilders
                            .post(path)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn()
                    .response
            response.status to response.contentAsString
        }

    private fun row(
        ref: String,
        uen: String,
    ): String =
        json
            .createObjectNode()
            .apply {
                put("counterpartyRef", ref)
                put("uen", uen)
                put("legalName", "ACME")
                put("addressLine1", "1 Raffles Place")
                put("postalCode", "048616")
            }.toString()

    private fun RegistryEnv.hold(facts: List<HeldDocumentFact>) =
        ce.inTenant(OPERATOR) {
            context.getBean(TransactionRunner::class.java).inTransaction {
                val store = JooqHeldDocuments(context.getBean(DSLContext::class.java))
                facts.forEach(store::apply)
            }
        }

    private fun RegistryEnv.heldRows(): String =
        BoundaryFixture.asSuperuser { c ->
            c
                .createStatement()
                .executeQuery(
                    "select coalesce(string_agg(t::text, '|' order by t::text), '') from masterdata.held_document t " +
                        "where tenant_id = '${ce.tenant}'::uuid",
                ).use {
                    it.next()
                    it.getString(1)
                }
        }

    private fun JsonNode.docs(): List<String>? =
        get("docUuids")?.let { a ->
            (0 until a.size()).map {
                a.get(it).asString()
            }
        }

    @Test
    @Suppress("LongMethod") // one scenario: record the held rows, import, read the envelopes
    fun `INV-AC-21337 an import lists the held documents of each written party and none for a party without`() =
        runRegistry { env ->
            val entity = env.entity()
            val other = env.entity()
            val p1 = unique("CTP")
            val p2 = unique("CTP")
            val p3 = unique("CTP")
            val d = (1..6).map(::uuid)

            fun held(
                i: Int,
                seq: Long,
                e: String,
                party: String?,
            ) = HeldDocumentFact(HeldKind.HELD, d[i], seq, e, party)
            env.hold(
                listOf(
                    held(1, 1, entity, p1),
                    held(0, 1, entity, p1),
                    held(2, 1, entity, p2),
                    held(3, 1, entity, p1),
                    HeldDocumentFact(HeldKind.RELEASED, d[3], 2, null, null),
                    held(4, 1, other, p1),
                    held(5, 1, entity, null),
                ),
            )
            val heldBefore = env.heldRows()
            val h = hash("held" + p1)
            val rows =
                listOf(
                    row(p1, "%08d".format(java.util.Locale.ROOT, 41) + "X"),
                    row(
                        p2,
                        "%08d".format(java.util.Locale.ROOT, 42) + "X",
                    ),
                    row(p3, "%08d".format(java.util.Locale.ROOT, 43) + "X"),
                )
            val dry =
                env.post(
                    "$prefix/counterparties/import/dry-runs",
                    """{"fileHash":"$h","rowCount":3,"rows":[${rows.joinToString(",")}]}""",
                )
            assertThat(dry.first).isEqualTo(200)
            val dryRunId = json.readTree(dry.second).get("dryRunId").asString()
            val events = env.ce.captured.envelopes.size
            val applied =
                env.post(
                    "$prefix/counterparties/import",
                    """{"entityRef":"$entity","dryRunId":"$dryRunId","fileHash":"$h","rowCount":3,""" +
                        """"providedBy":"MIGRATED"}""",
                )
            assertThat(applied.first).isEqualTo(201)
            val resolved =
                env.ce.captured.envelopes
                    .drop(events)
                    .filter { it.type.contains("MasterDataResolved") && it.tenant == env.ce.tenant }
            assertThat(resolved).hasSize(3)
            assertThat(resolved.map { it.entity.value }.toSet()).isEqualTo(setOf(entity))
            val data = resolved.associate { it.subject to json.readTree(it.dataJson) }
            assertThat(data.keys).isEqualTo(setOf(p1, p2, p3))
            assertThat(data.getValue(p1).get("partyRef").asString()).isEqualTo(p1)
            assertThat(data.getValue(p1).docs()).isEqualTo(listOf(d[0].toString(), d[1].toString()))
            assertThat(data.getValue(p2).docs()).isEqualTo(listOf(d[2].toString()))
            assertThat(data.getValue(p3).has("docUuids")).isFalse()
            assertThat(env.heldRows()).isEqualTo(heldBefore)
        }

    private companion object {
        const val UUID_TAIL = 12
    }
}
