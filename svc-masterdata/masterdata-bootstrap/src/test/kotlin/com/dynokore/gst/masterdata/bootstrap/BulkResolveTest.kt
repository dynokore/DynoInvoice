package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/** `bulkResolveCounterparty` (INV-T-4944): one counterparty, one write, 2,000 documents in, 5,001 refused. */
class BulkResolveTest {
    private fun docs(count: Int) = List(count) { UUID.randomUUID() }

    private fun body(
        docUuids: List<UUID>,
        documents: List<Pair<UUID, String>>?,
        entity: String? = ENTITY,
        uen: String = "UEN-BULK",
    ): String {
        val ids = docUuids.joinToString(",") { "\"$it\"" }
        val items =
            documents?.joinToString(
                ",",
            ) { "{\"docUuid\":\"${it.first}\",\"counterpartyRef\":\"${it.second}\"}" }
        return "{" + (
            entity?.let {
                "\"entityRef\":\"$it\","
            } ?: ""
        ) + "\"errorCode\":\"MDM-001\",\"docUuids\":[$ids]," +
            "\"resolvedIdentifiers\":[{\"scheme\":\"UEN\",\"value\":\"$uen\"}],\"evidenceRef\":\"EVD-9\"" +
            (items?.let { ",\"documents\":[$it]" } ?: "") + "}"
    }

    private fun CounterpartyEnv.bulk(
        json: String,
        actor: String = OPERATOR,
    ): Pair<Int, String> {
        val mvc = MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()
        return inTenant(actor) {
            mvc
                .perform(
                    MockMvcRequestBuilders
                        .post("/internal/v1/masterdata/counterparties/bulk-resolution")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json),
                ).andReturn()
                .let { it.response.status to it.response.contentAsString }
        }
    }

    private fun CounterpartyEnv.versions() = count("select count(*) from masterdata.counterparty_version")

    @Test
    fun `INV-AC-9600 a group of 2000 documents of one counterparty is resolved with one write`() =
        runCounterparty { env ->
            val party = unique("CTP-BULK")
            val ids = docs(2000)
            val (status, text) = env.bulk(body(ids, ids.map { it to party }, uen = unique("U")))
            assertThat(status).isEqualTo(200)
            assertThat(text).contains("\"resolvedCount\":2000")
            assertThat(env.rows("counterparty_version", party, "party_version")).hasSize(1)
        }

    @Test
    fun `INV-AC-9600 INV-AC-348 5001 documents are refused 413 SRT-014 and nothing changes`() =
        runCounterparty { env ->
            val party = unique("CTP-BULK")
            val ids = docs(5001)
            val before = env.versions()
            val (status, text) = env.bulk(body(ids, ids.map { it to party }))
            assertThat(status).isEqualTo(413)
            assertThat(text).contains("SRT-014")
            assertThat(env.versions()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-348 a group of three counterparties, or an inconsistent request, is refused 400 and changes nothing`() =
        runCounterparty { env ->
            val ids = docs(3)
            val before = env.versions()
            val three = ids.mapIndexed { i, d -> d to "CTP-BULK-$i-${unique("x")}" }
            val cases =
                mapOf(
                    "three counterparties" to body(ids, three),
                    "no documents" to body(ids, null),
                    "other document set" to body(ids, listOf(UUID.randomUUID() to "CTP-A")),
                    "blank entity" to body(ids, ids.map { it to "CTP-A" }, entity = " "),
                    "no entity" to body(ids, ids.map { it to "CTP-A" }, entity = null),
                )
            cases.forEach { (label, json) ->
                val (status, text) = env.bulk(json)
                assertThat(status).describedAs(label).isEqualTo(400)
                assertThat(text).describedAs(label).contains("SRT-001")
            }
            assertThat(env.versions()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-9600 a person without the exception-resolve permission is refused and nothing changes`() =
        runCounterparty { env ->
            val ids = docs(2)
            val before = env.versions()
            val (status, _) = env.bulk(body(ids, ids.map { it to "CTP-A" }), actor = "IDN-NOBODY")
            assertThat(status).isEqualTo(403)
            assertThat(env.versions()).isEqualTo(before)
        }
}
