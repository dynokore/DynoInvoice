package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.contract.model.CounterpartyAddress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * `runGapReport`, `getGapReport` and `exportGapReport` (INV-T-4944) at the service boundary: the real context over real
 * PostgreSQL, bound to a contract stub of the Check GST Register that can fail or be late for one UEN.
 */
class GapReportTest {
    private val json = JsonMapper.builder().build()
    private val registerGstn = "M2-1234567-0"
    private val conflictUen = "201812345K"
    private val outageUen = "201812399K"
    private val duplicateUen = "53012345X"

    private class Seed(
        val absent: String,
        val invalid: String,
        val conflict: String,
        val incomplete: String,
        val duplicateA: String,
        val duplicateB: String,
        val outage: String,
    )

    private fun RegistryEnv.seed(): Seed {
        val s =
            Seed(
                unique("CTP-A"),
                unique("CTP-B"),
                unique("CTP-C"),
                unique("CTP-D"),
                unique("CTP-E1"),
                unique("CTP-E2"),
                unique("CTP-F"),
            )
        val name = "ACME"
        ce.write(s.absent, ce.request("PEPPOL_PARTICIPANT_ID" to "0195:X", name = name, address = ADDRESS))
        ce.write(s.invalid, ce.request("UEN" to "5301234X", name = name, address = ADDRESS))
        ce.write(s.conflict, ce.request("UEN" to conflictUen, "GSTN" to "M2-9999999-9", name = name, address = ADDRESS))
        ce.write(
            s.incomplete,
            ce.request(
                "UEN" to "201912345A",
                name = name,
                address = CounterpartyAddress(addressLine1 = "1 Road", postalCode = " "),
            ),
        )
        ce.write(s.duplicateA, ce.request("UEN" to duplicateUen, name = name, address = ADDRESS))
        ce.write(s.duplicateB, ce.request("UEN" to duplicateUen, name = name, address = ADDRESS))
        ce.write(s.outage, ce.request("UEN" to outageUen, "GSTN" to "M2-1111111-1", name = name, address = ADDRESS))
        return s
    }

    private fun stub(block: (RegistryStub) -> Unit) =
        RegistryStub().use { stub ->
            stub.found(
                conflictUen,
                "{\"uen\":\"$conflictUen\",\"gstRegistrationNumber\":\"$registerGstn\",\"status\":\"REGISTERED\"," +
                    "\"registeredName\":\"ACME\"}",
            )
            stub.unavailable(outageUen)
            block(stub)
        }

    private fun RegistryEnv.mvc(): MockMvc =
        MockMvcBuilders.webAppContextSetup(context.sourceApplicationContext as WebApplicationContext).build()

    private data class Answer(
        val status: Int,
        val body: String,
        val location: String?,
    )

    private fun RegistryEnv.call(
        method: String,
        path: String,
        actor: String = OPERATOR,
        body: String? = null,
    ): Answer =
        ce.inTenant(actor) {
            val request =
                if (method == "POST") {
                    MockMvcRequestBuilders
                        .post(path)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body ?: "{}")
                } else {
                    MockMvcRequestBuilders.get(path)
                }
            val response = mvc().perform(request).andReturn().response
            Answer(response.status, response.contentAsString, response.getHeader("Location"))
        }

    private fun RegistryEnv.run(
        entity: String,
        actor: String = OPERATOR,
    ) = call("POST", "/internal/v1/masterdata/gap-reports", actor, """{"entityRef":"$entity"}""")

    private fun rowsOf(report: JsonNode) =
        report.get("rows").values().map {
            Triple(
                it.get("counterpartyRef").asString(),
                it.get("findingClass").asString(),
                it.get("errorCode")?.asString(),
            )
        }

    private fun RegistryEnv.fingerprint(): String =
        BoundaryFixture.asSuperuser { c ->
            val tables =
                listOf("counterparty", "counterparty_version", "counterparty_identifier", "counterparty_scheme_status")
            tables.joinToString("|") { table ->
                c
                    .createStatement()
                    .executeQuery(
                        "select count(*) || ':' || coalesce(md5(string_agg(t::text, '|' order by t::text)), '') " +
                            "from masterdata.$table t where tenant_id = '${ce.tenant}'::uuid",
                    ).use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
            }
        }

    private fun Seed.expected() =
        listOf(
            Triple(absent, "UEN_ABSENT", "MDM-002"),
            Triple(invalid, "UEN_INVALID", "MDM-003"),
            Triple(conflict, "GST_REGISTER_CONFLICT", "MDM-007"),
            Triple(incomplete, "ADDRESS_INCOMPLETE", "MDM-006"),
            Triple(duplicateA, "DUPLICATE_UEN", null),
            Triple(duplicateB, "DUPLICATE_UEN", null),
        ).sortedWith(compareBy({ it.first }, { gapOrder(it.second) }))

    private fun gapOrder(name: String) =
        listOf(
            "UEN_ABSENT",
            "UEN_INVALID",
            "GST_REGISTER_CONFLICT",
            "ADDRESS_INCOMPLETE",
            "DUPLICATE_UEN",
        ).indexOf(name)

    @Test
    fun `INV-AC-1608 each seeded counterparty is listed once with its class and no other`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val s = env.seed()
                val answer = env.run(entity)
                assertThat(answer.status).isEqualTo(200)
                val report = json.readTree(answer.body)
                assertThat(rowsOf(report)).isEqualTo(s.expected())
                assertThat(report.get("notChecked").values().map { it.get("counterpartyRef").asString() })
                    .isEqualTo(listOf(s.outage))
            }
        }

    @Test
    fun `INV-AC-9599 a register outage lists the counterparty as not checked and the call answers 200`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val s = env.seed()
                val report = json.readTree(env.run(entity).body)
                assertThat(report.get("notChecked").values().map { it.get("reason").asString() })
                    .isEqualTo(listOf("REGISTER_UNAVAILABLE"))
                assertThat(rowsOf(report).filter { it.second == "GST_REGISTER_CONFLICT" })
                    .isEqualTo(listOf(Triple(s.conflict, "GST_REGISTER_CONFLICT", "MDM-007")))
            }
        }

    @Test
    fun `INV-AC-1609 running the gap report changes no master data`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                env.seed()
                val before = env.fingerprint()
                assertThat(env.run(entity).status).isEqualTo(200)
                assertThat(env.fingerprint()).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-9599 a principal without the gap-report permission is refused and receives no row`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                env.seed()
                val before = env.ce.count("select count(*) from masterdata.gap_report")
                val answer = env.run(entity, actor = "IDN-NOBODY")
                assertThat(answer.status).isEqualTo(403)
                assertThat(json.readTree(answer.body).toString()).contains("SRT-004").doesNotContain("counterpartyRef")
                assertThat(env.ce.count("select count(*) from masterdata.gap_report")).isEqualTo(before)
            }
        }

    @Test
    fun `INV-AC-9599 a report that outlasts x-timeout answers 202 and getGapReport later returns the same rows`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val s = env.seed()
                val first = json.readTree(env.run(entity).body)
                stub.delay(conflictUen, LATE_MILLIS)
                env.advance(java.time.Duration.ofDays(2))
                val answer = env.run(entity)
                assertThat(answer.status).isEqualTo(202)
                val location = checkNotNull(answer.location)
                assertThat(location).startsWith("/internal/v1/masterdata/gap-reports/GAP-")
                assertThat(json.readTree(env.call("GET", location).body).get("state").asString()).isEqualTo("RUNNING")
                val done = awaitSucceeded(env, location)
                assertThat(rowsOf(done.get("result"))).isEqualTo(rowsOf(first)).isEqualTo(s.expected())
            }
        }

    private fun awaitSucceeded(
        env: RegistryEnv,
        location: String,
    ): JsonNode {
        repeat(POLLS) {
            val status = json.readTree(env.call("GET", location).body)
            if (status.get("state").asString() == "SUCCEEDED") return status
            Thread.sleep(POLL_MILLIS)
        }
        error("the report did not finish")
    }

    @Test
    fun `a request without entityRef or with a blank one is refused 400 SRT-001 and no report is stored`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                env.entity()
                val before = env.ce.count("select count(*) from masterdata.gap_report")
                listOf("{}", """{"entityRef":" "}""").forEach {
                    val answer = env.call("POST", "/internal/v1/masterdata/gap-reports", body = it)
                    assertThat(answer.status).isEqualTo(400)
                    assertThat(answer.body).contains("SRT-001")
                }
                assertThat(env.ce.count("select count(*) from masterdata.gap_report")).isEqualTo(before)
            }
        }

    @Test
    fun `the status resource and the export are readable by the starting principal only`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                env.seed()
                val id = json.readTree(env.run(entity).body).let { _ -> latestId(env) }
                assertThat(env.call("GET", "/internal/v1/masterdata/gap-reports/$id").status).isEqualTo(200)
                assertThat(env.call("GET", "/internal/v1/masterdata/gap-reports/$id", actor = "IDN-NOBODY").status)
                    .isEqualTo(403)
                val other = TestPermissions.OTHER_OPERATOR
                assertThat(
                    env.call("GET", "/internal/v1/masterdata/gap-reports/$id", actor = other).status,
                ).isEqualTo(404)
                assertThat(env.call("GET", "/internal/v1/masterdata/gap-reports/$id/export", actor = other).status)
                    .isEqualTo(404)
                assertThat(env.call("GET", "/internal/v1/masterdata/gap-reports/GAP-unknown").status).isEqualTo(404)
            }
        }

    private fun latestId(env: RegistryEnv): String =
        BoundaryFixture.asSuperuser { c ->
            c
                .createStatement()
                .executeQuery(
                    "select gap_report_id from masterdata.gap_report where tenant_id = '${env.ce.tenant}'::uuid " +
                        "order by started_at desc limit 1",
                ).use { rs ->
                    rs.next()
                    rs.getString(1)
                }
        }

    @Test
    fun `exportGapReport answers the csv of a succeeded report, once audited, and refuses the others`() =
        stub { stub ->
            runRegistry(registerUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                val s = env.seed()
                env.run(entity)
                val id = latestId(env)
                val answer = env.call("GET", "/internal/v1/masterdata/gap-reports/$id/export")
                assertThat(answer.status).isEqualTo(200)
                val lines = answer.body.trimEnd().split("\r\n")
                assertThat(lines.first())
                    .isEqualTo("counterpartyRef,findingClass,errorCode,uen,legalName,addressLine1,postalCode")
                assertThat(lines).hasSize(1 + s.expected().size + 1)
                assertThat(lines).contains("${s.invalid},UEN_INVALID,MDM-003,5301234X,ACME,1 Raffles Place,048616")
                assertThat(lines.last()).startsWith("${s.outage},,,")
                assertThat(
                    env.call("GET", "/internal/v1/masterdata/gap-reports/$id/export", actor = "IDN-NOBODY").status,
                ).isEqualTo(403)
                assertThat(
                    env.call("GET", "/internal/v1/masterdata/gap-reports/GAP-unknown/export").status,
                ).isEqualTo(404)
            }
        }

    private companion object {
        const val LATE_MILLIS = 2500L
        const val POLLS = 40
        const val POLL_MILLIS = 250L
    }
}
