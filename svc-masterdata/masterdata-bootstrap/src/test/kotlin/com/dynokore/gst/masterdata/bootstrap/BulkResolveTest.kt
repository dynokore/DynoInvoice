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
        val items = documents?.joinToString(",") { """{"docUuid":"${it.first}","counterpartyRef":"${it.second}"}""" }
        val entityPart = entity?.let { """"entityRef":"$it",""" }.orEmpty()
        val documentsPart = items?.let { ""","documents":[$it]""" }.orEmpty()
        return "{" + entityPart + """"errorCode":"MDM-001","docUuids":[$ids],""" +
            """"resolvedIdentifiers":[{"scheme":"UEN","value":"$uen"}],"evidenceRef":"EVD-9"""" + documentsPart + "}"
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

    private fun CounterpartyEnv.docUuidsOf(party: String): List<List<String>?> =
        captured.envelopes.filter { it.subject == party && it.tenant == tenant }.map { envelope ->
            val data =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree(envelope.dataJson)
            assertThat(data["partyRef"].asString()).isEqualTo(party)
            assertThat(envelope.entity.value).isEqualTo(ENTITY)
            data.get("docUuids")?.let { array -> (0 until array.size()).map { array.get(it).asString() } }
        }

    @Test
    fun `INV-AC-21321 a bulk resolution emits docUuids in request order, other emissions have no docUuids member`() =
        runCounterparty { env ->
            val party = unique("CTP-BULK")
            val (d1, d2, d3) = docs(3)
            val first =
                env.bulk(
                    body(listOf(d3, d1, d2, d1), listOf(d1, d2, d3).map { it to party }, uen = unique("U")),
                )
            assertThat(first.first).isEqualTo(200)
            assertThat(first.second).contains("\"resolvedCount\":3").contains("\"unchangedCount\":0")
            assertThat(env.rows("counterparty_version", party, "party_version")).hasSize(1)
            assertThat(env.docUuidsOf(party)).containsExactly(listOf(d3, d1, d2).map { it.toString() })

            val second = env.bulk(body(listOf(d1), listOf(d1 to party), uen = unique("U")))
            assertThat(second.first).isEqualTo(200)
            assertThat(second.second).contains("\"resolvedCount\":1").contains("\"unchangedCount\":0")
            assertThat(env.docUuidsOf(party)).containsExactly(
                listOf(d3, d1, d2).map { it.toString() },
                listOf(d1.toString()),
            )

            env.write(party, env.request("UEN" to unique("U"), entity = ENTITY))
            val emitted = env.docUuidsOf(party)
            assertThat(emitted).hasSize(3)
            assertThat(emitted.last()).isNull()
        }

    @Test
    fun `INV-AC-21321 refused bulk calls append no envelope and store no version`() =
        runCounterparty { env ->
            val two = docs(2)
            val many = docs(5001)
            val party = unique("CTP-BULK")
            val versions = env.versions()
            val events = env.captured.envelopes.size
            val two2 = body(two, listOf(two[0] to "CTP-A-${unique("x")}", two[1] to "CTP-B-${unique("x")}"))
            assertThat(env.bulk(two2).first).isEqualTo(400)
            assertThat(env.bulk(body(many, many.map { it to party })).first).isEqualTo(413)
            assertThat(env.bulk(body(two, two.map { it to party }), actor = "IDN-NOBODY").first).isEqualTo(403)
            assertThat(env.versions()).isEqualTo(versions)
            assertThat(env.captured.envelopes.size).isEqualTo(events)
        }
}
