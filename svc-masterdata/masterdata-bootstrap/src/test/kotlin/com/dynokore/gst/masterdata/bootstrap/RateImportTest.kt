package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.adapters.events.ReferenceRateLoadedOutbox
import com.dynokore.gst.masterdata.adapters.rate.ApprovedSource
import com.dynokore.gst.masterdata.adapters.rate.ScopePackStore
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicySource
import com.dynokore.platform.configresolver.ResolvedSetting
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.LocalDate
import java.util.NavigableMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The policy replica as tenancy-svc's election feed fills it: VP-24 of an entity on a date is the latest election on or
 * before the date; any other question, and an entity with no election, goes to the real reader.
 */
private class ElectionReader(
    private val real: PolicyReader,
    private val elections: Map<String, NavigableMap<LocalDate, Pair<String, String>>>,
) : PolicyReader by real {
    override fun resolve(
        key: VariationPointKey,
        scope: PolicyScope,
        at: LocalDate,
    ): ResolvedSetting {
        val held = if (key.value == "VP-24") scope.entity?.let { elections[it]?.floorEntry(at)?.value } else null
        return if (held == null) {
            real.resolve(key, scope, at)
        } else {
            ResolvedSetting(
                key,
                at,
                listOf(SelectedOption("APPROVED_SOURCE", """{"source":"${held.first}","basis":"${held.second}"}""")),
                PolicySource.Override(ScopeLevel.ENTITY, scope.entity.orEmpty(), java.util.UUID.randomUUID()),
            )
        }
    }
}

private class ElectionReaderProcessor(
    private val elections: Map<String, NavigableMap<LocalDate, Pair<String, String>>>,
) : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any = if (bean is PolicyReader && bean !is ElectionReader) ElectionReader(bean, elections) else bean
}

/**
 * INV-T-18775 at the service boundary: `importRates` and `getRegisterAndRates` over the real context, real PostgreSQL,
 * the election read through `PolicyReader` and the approved sources replicated into the scope pack tables.
 * INV-REQ-506.
 */
class RateImportTest {
    private val elections = ConcurrentHashMap<String, NavigableMap<LocalDate, Pair<String, String>>>()
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private data class Answer(
        val status: Int,
        val body: String,
    ) {
        val node: JsonNode get() = JsonMapper.builder().build().readTree(body)
        val code: String? get() = node.get("code")?.asString()
    }

    private fun run(block: (RegistryEnv) -> Unit) =
        runRegistry(
            customize = {
                it.withBean(ElectionReaderProcessor::class.java, { ElectionReaderProcessor(elections) })
            },
        ) { env -> block(env) }

    /** Elects [source] with [basis] for [entity] from [from]: the replica answer VP-24 gives (entity scope). */
    private fun RegistryEnv.elect(
        entity: String,
        source: String,
        basis: String,
        from: LocalDate,
    ) {
        elections.getOrPut(entity) { java.util.concurrent.ConcurrentSkipListMap() }[from] = source to basis
    }

    /** The scope pack that approves [sources] with the basis `DAILY` from [from]. */
    private fun RegistryEnv.approve(
        from: LocalDate,
        vararg sources: String,
    ) = ce.inTenant {
        val packs = context.getBean(ScopePackStore::class.java)
        context.getBean(TransactionRunner::class.java).inTransaction {
            packs.stage("sg-scope", "1.0.0", sources.map { ApprovedSource(it, setOf("DAILY")) }, now)
            packs.activate("sg-scope", "1.0.0", from, now)
        }
    }

    private fun RegistryEnv.post(
        body: String,
        actor: String = OPERATOR,
    ): Answer =
        ce.inTenant(actor) {
            val response =
                MockMvcBuilders
                    .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                    .build()
                    .perform(
                        MockMvcRequestBuilders
                            .post("/internal/v1/masterdata/exchange-rates/import")
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body),
                    ).andReturn()
                    .response
            Answer(response.status, response.contentAsString)
        }

    private fun RegistryEnv.get(
        query: String,
        actor: String = OPERATOR,
        entityRef: String? = null,
    ): Answer =
        ce.inTenant(actor) {
            val response =
                MockMvcBuilders
                    .webAppContextSetup(context.sourceApplicationContext as WebApplicationContext)
                    .build()
                    .perform(
                        MockMvcRequestBuilders
                            .get("/internal/v1/masterdata/register-and-rates?$query")
                            .also { request -> entityRef?.let { request.param("entityRef", it) } },
                    ).andReturn()
                    .response
            Answer(response.status, response.contentAsString)
        }

    private fun body(
        source: String,
        from: LocalDate,
        vararg rows: Pair<LocalDate, String>,
        entity: String? = null,
        entityProperty: String = "entityRef",
    ): String {
        val entityPart = entity?.let { ""","$entityProperty":"$it"""" }.orEmpty()
        val rowJson = rows.joinToString(",") { """{"currency":"USD","date":"${it.first}","rate":${it.second}}""" }
        return """{"source":"$source","dateRange":{"from":"$from","to":"${from.plusDays(30)}"},""" +
            """"currencyPairs":["USD/SGD"],"rowCount":${rows.size},"rows":[$rowJson]$entityPart}"""
    }

    private fun RegistryEnv.rateRows(source: String): Int =
        count("select count(*) from masterdata.exchange_rate where tenant_id = '{tenant}'::uuid and source = '$source'")

    private fun RegistryEnv.loadedEvents() =
        ce.captured.envelopes.count { it.tenant == ce.tenant && it.type == ReferenceRateLoadedOutbox.EVENT_TYPE }

    @Test
    fun `INV-AC-8626 the elected approved source loads and publishes once, the unlisted source stores nothing`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val loaded = env.post(body("MAS", d0, d0.plusDays(1) to "1.35", d0.plusDays(2) to "1.36", entity = entity))
            assertThat(loaded.status).describedAs(loaded.body).isEqualTo(201)
            assertThat(loaded.node.get("appliedCount").asInt()).isEqualTo(2)
            assertThat(env.rateRows("MAS")).isEqualTo(2)
            assertThat(env.loadedEvents()).isEqualTo(1)
            val unlisted = env.post(body("MAS2", d0, d0.plusDays(1) to "1.35", entity = entity))
            assertThat(unlisted.status).isEqualTo(422)
            assertThat(unlisted.code).isEqualTo(ErrorCode.MDM_012.code)
            assertThat(env.rateRows("MAS2")).isZero()
            assertThat(env.loadedEvents()).isEqualTo(1)
        }

    @Test
    fun `INV-AC-8626 an elected source that is not on the approved list is refused MDM-013 and stores nothing`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS2", "DAILY", d0)
            env.approve(d0, "MAS")
            val refused = env.post(body("MAS2", d0, d0.plusDays(1) to "1.35", entity = entity))
            assertThat(refused.status).isEqualTo(422)
            assertThat(refused.code).isEqualTo(ErrorCode.MDM_013.code)
            assertThat(env.rateRows("MAS2")).isZero()
            assertThat(env.loadedEvents()).isZero()
        }

    @Test
    fun `INV-AC-8626 the election is that of the named entity only`() =
        run { env ->
            val d0 = env.businessDate()
            val e1 = env.entity()
            val e2 = env.entity()
            env.elect(e1, "MAS", "DAILY", d0)
            env.elect(e2, "MAS2", "DAILY", d0)
            env.approve(d0, "MAS", "MAS2")
            val wrong = env.post(body("MAS", d0, d0.plusDays(1) to "1.35", entity = e2))
            assertThat(wrong.status).isEqualTo(422)
            assertThat(wrong.code).isEqualTo(ErrorCode.MDM_012.code)
            assertThat(env.rateRows("MAS")).isZero()
            assertThat(env.post(body("MAS", d0, d0.plusDays(1) to "1.35", entity = e1)).status).isEqualTo(201)
            assertThat(env.rateRows("MAS")).isEqualTo(1)
        }

    @Test
    fun `INV-AC-8626 no entityRef, a blank one or the look-alike entityRefs answers 400 SRT-001 with nothing stored`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val row = d0.plusDays(1) to "1.35"
            listOf(
                body("MAS", d0, row),
                body("MAS", d0, row, entity = ""),
                body("MAS", d0, row, entity = " "),
                body("MAS", d0, row, entity = entity, entityProperty = "entityRefs"),
            ).forEach {
                val answer = env.post(it)
                assertThat(answer.status).describedAs(it).isEqualTo(400)
                assertThat(answer.code).isEqualTo(ErrorCode.SRT_001.code)
            }
            assertThat(env.rateRows("MAS")).isZero()
            assertThat(env.loadedEvents()).isZero()
        }

    @Test
    fun `INV-AC-8626 a caller without exchange-rate import is refused 403 SRT-004 and nothing changes`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val refused = env.post(body("MAS", d0, d0.plusDays(1) to "1.35", entity = entity), actor = "IDN-NOBODY")
            assertThat(refused.status).isEqualTo(403)
            assertThat(refused.code).isEqualTo(ErrorCode.SRT_004.code)
            assertThat(env.rateRows("MAS")).isZero()
            assertThat(env.loadedEvents()).isZero()
        }

    @Test
    fun `INV-AC-8626 rows that straddle an election change are refused MDM-012 with 0 records and 0 events`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.elect(entity, "MAS2", "DAILY", d0.plusDays(6))
            env.approve(d0, "MAS", "MAS2")
            val refused =
                env.post(body("MAS", d0, d0.plusDays(1) to "1.35", d0.plusDays(10) to "1.36", entity = entity))
            assertThat(refused.status).isEqualTo(422)
            assertThat(refused.code).isEqualTo(ErrorCode.MDM_012.code)
            assertThat(env.rateRows("MAS")).isZero()
            assertThat(env.loadedEvents()).isZero()
        }

    @Test
    fun `INV-AC-8626 a load that stores 0 records answers appliedCount 0 and publishes no event`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val answer = env.post(body("MAS", d0, d0.plusDays(1) to "0", entity = entity))
            assertThat(answer.status).describedAs(answer.body).isEqualTo(201)
            assertThat(answer.node.get("appliedCount").asInt()).isZero()
            assertThat(answer.node.get("failedCount").asInt()).isEqualTo(1)
            assertThat(env.rateRows("MAS")).isZero()
            assertThat(env.loadedEvents()).isZero()
        }

    @Test
    fun `INV-AC-8626 a row count that differs from the rows answers 400 SRT-001 with nothing stored`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            val valid = body("MAS", d0, d0.plusDays(1) to "1.35", entity = entity)
            val mismatch = valid.replace("\"rowCount\":1", "\"rowCount\":2")
            val answer = env.post(mismatch)
            assertThat(answer.status).isEqualTo(400)
            assertThat(answer.code).isEqualTo(ErrorCode.SRT_001.code)
            assertThat(env.rateRows("MAS")).isZero()
        }

    @Test
    fun `INV-AC-8626 getRegisterAndRates needs the entityRef and decides no person permission`() =
        run { env ->
            val d0 = env.businessDate()
            val entity = env.entity()
            env.elect(entity, "MAS", "DAILY", d0)
            env.approve(d0, "MAS")
            env.post(body("MAS", d0, d0.plusDays(1) to "1.35", entity = entity))
            val query = "counterpartyUen=${Simulators.UEN_REGISTERED}&currency=USD&transactionDate=${d0.plusDays(1)}"
            val missing = env.get(query)
            assertThat(missing.status).isEqualTo(400)
            assertThat(missing.code).isEqualTo(ErrorCode.SRT_001.code)
            assertThat(env.get(query, entityRef = " ").status).isEqualTo(400)
            val found = env.get(query, actor = "IDN-NOBODY", entityRef = entity)
            assertThat(found.status).describedAs(found.body).isEqualTo(200)
            assertThat(
                found.node
                    .get("rate")
                    .get("source")
                    .asString(),
            ).isEqualTo("MAS")
            assertThat(
                java.math.BigDecimal(
                    found.node
                        .get("rate")
                        .get("rate")
                        .asString(),
                ),
            ).isEqualByComparingTo("1.35")
            assertThat(mapper.readTree(found.body).has("register")).isTrue()
        }
}
