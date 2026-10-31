package com.dynokore.gst.evidence.retention

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.application.retention.EffectiveRetention
import com.dynokore.gst.evidence.bootstrap.EvidenceContext
import com.dynokore.gst.evidence.domain.retention.RetentionTier
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.context.ConfigurableApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One answer of the running service: its status and JSON body. */
class Answer(
    val status: Int,
    val body: JsonNode,
) {
    val code: String? get() = body.get("code")?.asString()

    /** This answer, after a check that it is a 200; the failure shows the body. */
    fun accepted(): Answer {
        check(status == 200) { "expected 200, was $status: $body" }
        return this
    }
}

/**
 * The service as TPL-18 composes it, in an embedded servlet container over the Testcontainers database, called over
 * HTTP: `setPolicy` of service-runtime and the published `getMaxEvidenceRetention`. The tests of INV-T-6540 use it.
 */
class RetentionService(
    private val context: ConfigurableApplicationContext,
) : AutoCloseable {
    private val mapper = ObjectMapper()
    private val client = HttpClient.newHttpClient()
    private val port = context.environment.getProperty("local.server.port")
    private val scope = context.getBean(TenantScope::class.java)
    private val runner = context.getBean(TransactionRunner::class.java)
    private val effective = context.getBean(EffectiveRetention::class.java)

    /** A tenant of its own: the rows of one test never meet another's. */
    fun newTenant(): TenantId = TenantId(UUID.randomUUID())

    /** A tenant-scope value: `values` holds the years per tier, or null for the option YEARS_5. */
    fun setTenant(
        tenant: TenantId,
        years: Map<RetentionTier, Number>?,
        reason: String?,
    ): Answer = set(tenant, "TENANT", null, years, reason)

    /** An entity-scope value of [entity]. */
    fun setEntity(
        tenant: TenantId,
        entity: String,
        years: Map<RetentionTier, Number>?,
        reason: String?,
    ): Answer = set(tenant, "ENTITY", entity, years, reason)

    /** The record of a LONGER value: [RetentionTier.property] to its years, a tier left out being a missing field. */
    fun record(years: Map<RetentionTier, Number>): Map<String, Number> = years.mapKeys { it.key.property }

    /** A request body with a record whose field names are given as they are, a look-alike included. */
    fun setRaw(
        tenant: TenantId,
        level: String,
        entity: String?,
        record: Map<String, Number>?,
        reason: String?,
    ): Answer {
        val tenantLevel = level == "TENANT"
        val ref = if (tenantLevel) tenant.value.toString() else entity
        return post(tenant, body(level, ref, entity.takeUnless { tenantLevel }, record, reason))
    }

    private fun set(
        tenant: TenantId,
        level: String,
        entity: String?,
        years: Map<RetentionTier, Number>?,
        reason: String?,
    ): Answer = setRaw(tenant, level, entity, years?.let { record(it) }, reason)

    private fun body(
        level: String,
        ref: String?,
        entity: String?,
        record: Map<String, Number>?,
        reason: String?,
    ): String {
        val node = mapper.createObjectNode()
        node.put("scopeLevel", level)
        ref?.let { node.put("scopeRef", it) }
        entity?.let { node.put("entityRef", it) }
        val option = node.putArray("selected").addObject()
        if (record == null) {
            option.put("option", "YEARS_5")
        } else {
            option.put("option", "LONGER")
            val value = option.putObject("value")
            record.forEach { (name, years) -> value.put(name, years.toInt()) }
        }
        node.put("effectiveDate", today().toString())
        reason?.let { node.put("reason", it) }
        return mapper.writeValueAsString(node)
    }

    /** A `setPolicy` call of a tenant at tenant scope by the workload [caller] (`token-<caller>`), or none if null. */
    fun setAs(
        caller: String?,
        tenant: TenantId,
        years: Map<RetentionTier, Number>,
        reason: String?,
    ): Answer = post(tenant, body("TENANT", tenant.value.toString(), null, record(years), reason), caller)

    private fun post(
        tenant: TenantId,
        body: String,
        caller: String? = "console-bff",
    ): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/policies/VP-43"))
                .header("Authorization", "Bearer ${tenant.value}/admin-1")
                .apply { caller?.let { header("X-Workload-Token", "token-$it") } }
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        return answer(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    /** GET of the published operation, with the workload token `token-<caller>` (see RetentionBeans). */
    fun getMax(caller: String?): Answer {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/internal/v1/evidence/retention/maximum"))
                .header("Authorization", "Bearer ${UUID.randomUUID()}/ignored")
                .apply { caller?.let { header("X-Workload-Token", "token-$it") } }
                .GET()
                .build()
        return answer(client.send(request, HttpResponse.BodyHandlers.ofString()))
    }

    private fun answer(response: HttpResponse<String>) = Answer(response.statusCode(), mapper.readTree(response.body()))

    /** [EffectiveRetention.years] for [entity] on [tier], read as the tenant's own request would. */
    fun years(
        tenant: TenantId,
        entity: String,
        tier: RetentionTier,
    ): Int =
        scope.bind(tenant, "svc:evidence-svc") {
            runner.inTransaction { effective.years(entity, tier, today()) }
        }

    fun years(
        tenant: TenantId,
        entity: String,
    ): List<Int> = RetentionTier.entries.map { years(tenant, entity, it) }

    /** Rows config-resolver stored for [tenant] (a refusal stores nothing). */
    fun stored(tenant: TenantId): Long =
        count("select count(*) from evidence.config_setting where tenant_id = '${tenant.value}'")

    /** The reasons of the settings stored for [tenant]. */
    fun reasons(tenant: TenantId): List<String> =
        EvidenceDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement
                    .executeQuery(
                        "select reason from evidence.config_setting where tenant_id = '${tenant.value}' order by seq",
                    ).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }

    /** Every stored setting of every tenant is removed: the installation-wide figure is read from an empty table. */
    fun clearAll() {
        EvidenceDb.superuser().use { it.createStatement().use { s -> s.execute("truncate evidence.config_setting") } }
    }

    private fun count(sql: String): Long =
        EvidenceDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

    /** A bean of the running context. */
    fun <T : Any> bean(type: Class<T>): T = context.getBean(type)

    /**
     * [block] with the read of the installation figure broken: the function `config_option_value_maximum` renamed,
     * then restored. getMaxEvidenceRetention cannot read the settings meanwhile.
     */
    fun <T> withBrokenReads(block: () -> T): T {
        val signature = "evidence.config_option_value_maximum(text, text)"
        EvidenceDb.superuser().use {
            it.createStatement().use { s ->
                s.execute("alter function $signature rename to broken_max")
            }
        }
        try {
            return block()
        } finally {
            EvidenceDb.superuser().use {
                it.createStatement().use { s ->
                    s.execute("alter function evidence.broken_max(text, text) rename to config_option_value_maximum")
                }
            }
        }
    }

    override fun close() = context.close()

    companion object {
        fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Singapore"))

        /** The service for one test; the callers `control-plane-svc`, `access-svc`, `console-bff` have tokens. */
        fun start(
            dir: Path,
            beans: List<Class<*>> = listOf(WorkloadTokensOnly::class.java, RetentionBeans::class.java),
        ): RetentionService =
            RetentionService(
                EvidenceContext.startServlet(
                    dir,
                    beans,
                    arrayOf(
                        "service-runtime.workload-identity.callers[0].service=control-plane-svc",
                        "service-runtime.workload-identity.callers[0].subject=sa-control-plane-svc",
                        "service-runtime.workload-identity.callers[1].service=access-svc",
                        "service-runtime.workload-identity.callers[1].subject=sa-access-svc",
                        "service-runtime.workload-identity.callers[2].service=console-bff",
                        "service-runtime.workload-identity.callers[2].subject=sa-console-bff",
                    ),
                    RetentionService::class.java.classLoader,
                ),
            )
    }
}
