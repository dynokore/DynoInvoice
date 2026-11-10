package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.tenancyjooq.TenancyProbe
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

private val TENANT_ST: TenantId = TenantId.of("0e000000-0000-0000-0000-00000000000e")
private const val USER_ST_TOKEN = "Bearer token-user-st"
private const val ST_CALLER = "console-bff"
private const val ST_PACKS = "/internal/v1/rules/mapping-packs"
private const val ST_ENTITY = "E-1"
private const val ST_FROM = "2025-01-01"

private const val ST_INSERT_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
        "values (?::uuid, ?::uuid, ?, ?, ?, 'TENANT_MAPPING', 'ARMED', ?::date, null, " +
        "'1.0.0', null, 'tenancy-probe', 'E-1') returning id"

private const val ST_INSERT_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, effective_to, " +
        "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, registered_by) " +
        "values (?::uuid, ?, ?, ?, ?::uuid, ?::date, null, '1.0.0', '{}'::jsonb, '{}'::jsonb, " +
        "?, ?::jsonb, 'anchor', 'tenancy-probe') returning id"

/** Invented pack data of this test (no value here is IRAS data), owned by [TENANT_ST] so no other test sees it. */
private object StPacks {
    private fun code(
        code: String,
        group: String,
        side: String,
    ) = """{"code":"$code","group":"$group","side":"$side","annexERate":"0","sourceClass":"PRIMARY",""" +
        """"sourceReference":"invented"}"""

    val catalogue =
        """{"complete":true,"codes":[${code("ZR", "SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE", "SUPPLY")},""" +
            """${code("ZP", "PURCHASE_ZERO_RATED_NON_REPORTABLE", "PURCHASE")},""" +
            """${code("TXNA", "PURCHASE_STANDARD_RATED", "PURCHASE")}]}"""

    private fun side(
        tag: String,
        side: String,
    ) = """{"tag":"$tag","side":"$side","handling":"HANDLED","sourceClass":"PRIMARY","sourceReference":"s"}"""

    private fun treatment(
        tag: String,
        side: String,
        code: String,
    ) = """{"tag":"$tag","side":"$side","categoryCode":"$code","rateBasis":"FIXED","rate":"0",""" +
        """"sourceClass":"PRIMARY","sourceReference":"s"}"""

    /** SWS is declared for the supply side only; A3PL for the purchase side only. */
    val scenario =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},""" +
            """"schemeTagSides":[${side("SWS", "SUPPLY")},${side("A3PL", "PURCHASE")}],""" +
            """"treatments":[${treatment("SWS", "SUPPLY", "ZR")},${treatment("A3PL", "PURCHASE", "TXNA")}],""" +
            """"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""

    val packs =
        listOf(
            Triple("CODE_CATALOGUE", "st-catalogue", "c1") to StPacks.catalogue,
            Triple("SCENARIO", "st-scenario", "s1") to StPacks.scenario,
        )
}

@TestConfiguration(proxyBeanMethods = false)
class SchemeTagBoundaryDoubles {
    @Bean
    fun stEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            if (authorization == USER_ST_TOKEN) EndUserPrincipal("user-st", TENANT_ST, emptySet()) else null
        }

    @Bean
    fun stWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified("subject-of-" + value.removePrefix("wl-"))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }
}

/**
 * `mapTaxCode` with a scheme tag at the service boundary, on real PostgreSQL and the real composition (INV-REQ-2444;
 * INV-AC-16854, INV-AC-2502): a tag on a code of the wrong side is answered 422 RUL-013 with `errors[].field`
 * `/schemeTag` and the stored entry is unchanged; a tag on the right side is answered 201 and stored.
 */
@SpringBootTest(
    classes = [SchemeTagBoundaryDoubles::class, MappingAllowingPorts::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$ST_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$ST_CALLER",
    ],
)
class SchemeTagAuthoringBoundaryTest(
    @LocalServerPort private val port: Int,
) {
    private val mapper = JsonMapper()
    private val client = HttpClient.newHttpClient()
    private val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")

    private fun call(
        method: String,
        path: String,
        body: String? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        builder.header("Content-Type", "application/json")
        if (method == "POST") builder.header("Idempotency-Key", UUID.randomUUID().toString())
        val publisher = body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
        builder.method(method, publisher)
        builder.header("X-Workload-Token", workloadToken(ST_CALLER))
        builder.header("Authorization", USER_ST_TOKEN)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())

    private fun draft(): String {
        val label = "st-${UUID.randomUUID().toString().take(8)}"
        val answer = call("POST", ST_PACKS, """{"label":"$label","entityRef":"$ST_ENTITY"}""")
        withClue(answer.body()) { answer.statusCode() shouldBe 201 }
        return json(answer).get("packId").asString()
    }

    private fun mapCode(
        packId: String,
        code: String,
        iras: String,
        tag: String?,
    ) = call(
        "POST",
        "$ST_PACKS/$packId/entries",
        """{"internalTaxCode":"$code","irasCode":"$iras","sourceClass":"PRIMARY","entityRef":"$ST_ENTITY"""" +
            tag?.let { ""","schemeTag":"$it"""" }.orEmpty() + "}",
    )

    /** The stored row of the entry, in the tenant, as text (all columns; empty when there is none). */
    private fun stored(
        packId: String,
        code: String,
    ): List<String> =
        probe.inTenant(TENANT_ST) {
            fetchRows(
                "select e::text as r from rules.pack_entry_source e join rules.pack_registry p " +
                    "on p.pack_version = e.pack_version and p.pack_type = e.pack_type and p.tenant_id = e.tenant_id " +
                    "where p.pack_id = '$packId' and e.entry_id = '$code'",
            ).map { it["r"] as String }
        }

    private fun tagOf(
        packId: String,
        code: String,
    ): Any? =
        probe.inTenant(TENANT_ST) {
            fetchValue(
                "select coalesce(e.scheme_tag, '<null>') from rules.pack_entry_source e join rules.pack_registry p " +
                    "on p.pack_version = e.pack_version and p.pack_type = e.pack_type and p.tenant_id = e.tenant_id " +
                    "where p.pack_id = ? and e.entry_id = ?",
                packId,
                code,
            )
        }

    @Test
    fun `INV-AC-16854 INV-AC-2502 SWS on ZP is 422 RUL-013 on schemeTag pointer, entry unchanged`() {
        val packId = draft()
        withClue("the draft entry before the call") { mapCode(packId, "PURCH", "ZP", null).statusCode() shouldBe 201 }
        val before = stored(packId, "PURCH")
        before.size shouldBe 1
        val answer = mapCode(packId, "PURCH", "ZP", "SWS")
        withClue(answer.body()) {
            answer.statusCode() shouldBe 422
            answer.headers().firstValue("Content-Type").orElse("") shouldBe "application/problem+json"
            val body = json(answer)
            body.get("code").asString() shouldBe "RUL-013"
            body
                .get("errors")
                .get(0)
                .get("field")
                .asString() shouldBe "/schemeTag"
            body
                .get("errors")
                .get(0)
                .get("code")
                .asString() shouldBe "RUL-013"
        }
        stored(packId, "PURCH") shouldBe before
        tagOf(packId, "PURCH") shouldBe "<null>"
    }

    @Test
    fun `INV-AC-16854 a refused tag on a new entry stores no row at all`() {
        val packId = draft()
        mapCode(packId, "NEWONE", "ZP", "SWS").statusCode() shouldBe 422
        stored(packId, "NEWONE") shouldBe emptyList()
    }

    @Test
    fun `INV-AC-16854 INV-AC-2502 A3PL on the purchase code TXNA is 201 and the entry reads back with the tag`() {
        val packId = draft()
        val answer = mapCode(packId, "P3", "TXNA", "A3PL")
        withClue(answer.body()) { answer.statusCode() shouldBe 201 }
        tagOf(packId, "P3") shouldBe "A3PL"
        val listed = json(call("GET", "$ST_PACKS/$packId/entries?entityRef=$ST_ENTITY"))
        listed
            .get("items")
            .first { it.get("internalTaxCode").asString() == "P3" }
            .get("schemeTag")
            .asString() shouldBe "A3PL"
    }

    @Test
    fun `INV-AC-16854 SWS on the supply code ZR is accepted and an entry without a tag reads back without one`() {
        val packId = draft()
        mapCode(packId, "S1", "ZR", "SWS").statusCode() shouldBe 201
        tagOf(packId, "S1") shouldBe "SWS"
        mapCode(packId, "PLAIN", "ZP", null).statusCode() shouldBe 201
        tagOf(packId, "PLAIN") shouldBe "<null>"
    }

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) {
            PostgresRules.register(registry)
            val probe = TenancyProbe.connect(PostgresRules.pg.jdbcUrl, "rules_runtime", "rules_runtime_pw")
            StPacks.packs.forEach { (id, contents) ->
                val (type, packId, version) = id
                probe.inTenant(TENANT_ST) {
                    fetchValue(
                        ST_INSERT_PACK,
                        UUID.randomUUID().toString(),
                        TENANT_ST.toString(),
                        packId,
                        type,
                        version,
                        ST_FROM,
                    )
                    fetchValue(
                        ST_INSERT_ARTEFACT,
                        UUID.randomUUID().toString(),
                        packId,
                        type,
                        version,
                        TENANT_ST.toString(),
                        ST_FROM,
                        byteArrayOf(1),
                        contents,
                    )
                }
            }
        }
    }
}
