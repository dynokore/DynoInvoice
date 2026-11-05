package com.dynokore.gst.masterdata.bootstrap

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * INV-T-4946: each lookup that influenced a document is recorded (queried value, response, timestamp) and published
 * once as `EvidenceArtefactRecorded`; a lookup served from cache publishes none (INV-REQ-802 b2, INV-Q-9645 A).
 */
class RegistryEvidenceTest {
    private val mapper = JsonMapper.builder().build()

    private fun body(
        base: String,
        value: String,
    ): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI("$base/lookup/$value")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    @Test
    fun `INV-AC-1856 each lookup is cached within its lifetime and recorded as evidence of the document`() =
        runRegistry { env ->
            val entity = env.entity()
            val directoryBody = body(Simulators.directory.baseUrl, Simulators.PEPPOL_FOUND)
            val registerBody = body(Simulators.register.baseUrl, Simulators.UEN_REGISTERED)
            Simulators.resetAll()
            val before = env.clock.instant()
            env.document("DOC-A") {
                env.reach(entity, Simulators.PEPPOL_FOUND)
                env.lookup(entity, Simulators.UEN_REGISTERED)
            }
            val after = env.clock.instant()
            val rows = env.records()
            assertThat(rows).hasSize(2)
            assertThat(env.evidenceEvents()).hasSize(2)
            rows.forEach { assertRecorded(env, it, "DOC-A", before, after) }
            assertThat(responseOf(rows.single { it.kind == "DIRECTORY" })).isEqualTo(directoryBody)
            assertThat(responseOf(rows.single { it.kind == "REGISTER" })).isEqualTo(registerBody)
            // a second document for the same counterparty an hour later: cached, no call, no new event
            env.advance(Duration.ofHours(1))
            val calls = Simulators.directory.calls() to Simulators.register.calls()
            env.document("DOC-B") {
                assertThat(env.reach(entity, Simulators.PEPPOL_FOUND).onNetwork).isTrue()
                assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTERED")
            }
            assertThat(Simulators.directory.calls() to Simulators.register.calls()).isEqualTo(calls)
            assertThat(env.evidenceEvents()).hasSize(2)
            assertThat(env.records()).hasSize(2)
        }

    private fun responseOf(row: LookupRow) = mapper.readTree(row.bytes).get("response").asString()

    /** The row holds the queried value, the kind and a time inside the call; its event matches by equality. */
    private fun assertRecorded(
        env: RegistryEnv,
        row: LookupRow,
        correlation: String,
        from: Instant,
        to: Instant,
    ) {
        val event = env.evidenceEvents().single { mapper.readTree(it.dataJson).get("reference").asString() == row.key }
        assertEvidenceEvent(event, row, correlation)
        val record = mapper.readTree(row.bytes)
        assertThat(record.get("queried").asString()).isEqualTo(row.queried)
        assertThat(record.get("kind").asString()).isEqualTo(row.kind)
        assertThat(Instant.parse(record.get("at").asString())).isBetween(from, to)
    }

    @Test
    fun `INV-AC-1856 INV-AC-2033 a negative directory answer is served from the record already stored`() =
        runRegistry { env ->
            val entity = env.entity()
            env.document("DOC-A") { env.reach(entity, Simulators.PEPPOL_NOT_FOUND) }
            val stored = env.records().single()
            val calls = Simulators.directory.calls()
            env.advance(Duration.ofMinutes(30))
            env.document("DOC-B") { assertThat(env.reach(entity, Simulators.PEPPOL_NOT_FOUND).onNetwork).isFalse() }
            assertThat(Simulators.directory.calls()).isEqualTo(calls)
            assertThat(env.records()).containsExactly(stored)
            assertThat(env.evidenceEvents()).hasSize(1)
            val cachedKey =
                env.count(
                    "select count(*) from masterdata.directory_cache where tenant_id = '{tenant}'::uuid " +
                        "and record_key = '${stored.key}'",
                )
            assertThat(cachedKey).isEqualTo(1)
        }

    @Test
    fun `INV-AC-1856 a lifetime that has passed looks up again and records again`() =
        runRegistry { env ->
            val entity = env.entity()
            env.document("DOC-A") {
                env.reach(entity, Simulators.PEPPOL_FOUND)
                env.lookup(entity, Simulators.UEN_REGISTERED)
            }
            val calls = Simulators.directory.calls() to Simulators.register.calls()
            env.advance(Duration.ofHours(25))
            env.document("DOC-B") {
                env.reach(entity, Simulators.PEPPOL_FOUND)
                env.lookup(entity, Simulators.UEN_REGISTERED)
            }
            assertThat(Simulators.directory.calls()).isEqualTo(calls.first + 1)
            assertThat(Simulators.register.calls()).isEqualTo(calls.second + 1)
            assertThat(env.evidenceEvents()).hasSize(4)
            val second = env.records().filter { it.correlation == "DOC-B" }
            assertThat(second).hasSize(2)
            second.forEach { row ->
                assertEvidenceEvent(
                    env.evidenceEvents().single {
                        mapper.readTree(it.dataJson).get("reference").asString() ==
                            row.key
                    },
                    row,
                    "DOC-B",
                )
            }
        }

    @Test
    fun `INV-AC-1856 the evidence equality fails on a look-alike artefact type and on a hash of other bytes`() =
        runRegistry { env ->
            val entity = env.entity()
            env.document("DOC-A") { env.reach(entity, Simulators.PEPPOL_FOUND) }
            val row = env.records().single()
            val event = env.evidenceEvents().single()
            assertEvidenceEvent(event, row, "DOC-A")
            val lookalike = event.copy(dataJson = event.dataJson.replace("\"REGISTER_LOOKUP\"", "\"REGISTER_LOOKUPS\""))
            assertThatThrownBy { assertEvidenceEvent(lookalike, row, "DOC-A") }.isInstanceOf(AssertionError::class.java)
            assertThatThrownBy {
                assertEvidenceEvent(event, row.copy(bytes = row.bytes + " "), "DOC-A")
            }.isInstanceOf(AssertionError::class.java)
            assertThatThrownBy { assertEvidenceEvent(event, row, "DOC-OTHER") }.isInstanceOf(AssertionError::class.java)
        }
}
