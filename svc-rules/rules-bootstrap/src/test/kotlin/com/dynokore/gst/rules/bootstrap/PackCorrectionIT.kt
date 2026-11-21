package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.rules.adapters.persistence.ArmedPackLookup
import com.dynokore.gst.rules.adapters.persistence.PersistedPackRegistry
import com.dynokore.gst.rules.adapters.web.PacksController
import com.dynokore.gst.rules.application.PackCorrectionPermissionPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.served.MovableClock
import com.dynokore.gst.rules.served.PolicyFixtures
import com.dynokore.gst.rules.served.subjectOf
import com.dynokore.gst.rules.served.workloadToken
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

private const val CALLER = "console-bff"
private const val ALT = "$CALLER-alt"
private const val ROLLBACK_KEY = "regulatory-pack.rollback"
private const val PLATFORM = "ffffffff-ffff-ffff-ffff-ffffffffffff"

/** Counts the calls the use cases receive: every call of either use case starts with this port. */
object UseCaseCalls {
    val permissionChecks = AtomicInteger()
}

/** The evaluation of the correction tests: the bad pack classifies SR; the superseding pack changes tax code CHANGE. */
@TestConfiguration(proxyBeanMethods = false)
class CorrectionFixtures {
    @Bean
    @Primary
    fun correctionEvaluation(): PackEvaluationPort =
        object : PackEvaluationPort {
            override fun evaluate(
                tenant: TenantId,
                packType: String,
                packVersion: String,
                document: SampledDocument,
            ) = DocumentOutcome(
                if (packVersion.endsWith("-rollback") && document.internalTaxCode == "CHANGE") "ZR" else "SR",
            )

            override fun effectiveFrom(
                tenant: TenantId,
                packType: String,
                packVersion: String,
            ): LocalDate = LocalDate.MIN
        }

    @Bean
    fun countingPermissionPorts(): BeanPostProcessor =
        object : BeanPostProcessor {
            override fun postProcessAfterInitialization(
                bean: Any,
                beanName: String,
            ): Any =
                if (bean is PackCorrectionPermissionPort) {
                    PackCorrectionPermissionPort { user, key ->
                        UseCaseCalls.permissionChecks.incrementAndGet()
                        bean.mayDo(user, key)
                    }
                } else {
                    bean
                }
        }
}

/** The harness of the correction and reprocessing tests: the arming harness, seeded packs and the served calls. */
@Suppress("TooManyFunctions")
open class CorrectionHarness(
    port: Int,
    replica: AuthorityReplica,
    clock: MovableClock,
    context: ApplicationContext,
    policy: PolicyReplica,
) : ArmingHarness(port, replica, clock, context, policy) {
    protected val type =
        "CORR_" +
            UUID
                .randomUUID()
                .toString()
                .take(8)
                .uppercase()
    protected val d0: LocalDate = LocalDate.now(clock).minusDays(30)
    protected val earlier = "e" + UUID.randomUUID().toString().take(5)
    protected val bad = "b" + UUID.randomUUID().toString().take(5)

    companion object {
        fun registerCorrection(registry: DynamicPropertyRegistry) {
            register(registry)
            registry.add("gst.form") { "saas" }
            // the tests drive the worker, and no tick moves a table
            registry.add("rules.reprocessing.worker") { "false" }
            // the holder copy is a reference table, as in application.yaml: a tenant reads the platform artefact rows
            registry.add("inv.tenancy.reference-tables") {
                "rules.pack_registry,rules.pack_entry_source,rules.pack_artefact"
            }
            registry.add("service-runtime.workload-identity.callers[1].service") { ALT }
            registry.add("service-runtime.workload-identity.callers[1].subject") { subjectOf(ALT) }
        }
    }

    protected fun raw(vararg sql: String) =
        superuser { c ->
            c.createStatement().use { st ->
                st.execute("set session_replication_role = replica")
                sql.forEach { st.execute(it) }
            }
        }

    /** An ARMED platform pack row (a seeded fixture) and, when [artefact], its signed artefact row. */
    protected fun seedArmed(
        version: String,
        effective: LocalDate,
        supersedes: String?,
        artefact: Boolean,
    ) {
        raw(
            "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
                "effective_from, " +
                "min_engine_version, supersedes, registered_by) values (gen_random_uuid(), '$PLATFORM', " +
                "'$type-pack', '$type', " +
                "'$version', 'REGULATORY', 'ARMED', '$effective', '1.0.0', ${supersedes?.let { "'$it'" }}, 'seed')",
        )
        if (artefact) {
            raw(
                "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, " +
                    "min_engine_version, manifest, checksums, signature, contents, trusted_anchor, " +
                    "registered_by) values " +
                    "(gen_random_uuid(), '$type-pack', '$type', '$version', '$PLATFORM', '$effective', '1.0.0', " +
                    "'{\"version\":\"$version\"}', '{\"contents\":\"${"ab".repeat(32)}\"}', '\\x0701ff', " +
                    "'{\"rules\":[\"$version\"]}', 'anchor', 'seed')",
            )
        }
    }

    /** The earlier pack (with its artefact) and the bad pack that superseded it, armed from [d0]. */
    protected fun seedBadPack() {
        seedArmed(earlier, d0.minusDays(150), null, artefact = true)
        seedArmed(bad, d0, earlier, artefact = false)
    }

    protected fun rollback(
        user: String?,
        date: LocalDate = d0,
        token: String? = workloadToken(CALLER),
    ) = call("$type/rollback", user, """{"effectiveFrom":"$date"}""", token)

    protected fun reprocess(
        user: String?,
        token: String? = workloadToken(CALLER),
    ): HttpResponse<String> {
        val builder =
            HttpRequest
                .newBuilder(URI("http://localhost:$port/internal/v1/rules/regulatory-packs/$type/reprocessing"))
                .header("Idempotency-Key", UUID.randomUUID().toString())
        if (user != null) builder.header("Authorization", "Bearer $user|$tenant|")
        if (token != null) builder.header("X-Workload-Token", token)
        return http.send(
            builder.POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    protected fun registryRow(version: String): String? =
        scalar(
            "select to_jsonb(p)::text from rules.pack_registry p where pack_type = ? and pack_version = ?",
            type,
            version,
        )

    protected fun <T> inTenant(block: () -> T): T =
        context.getBean(TenantContext::class.java).bind(tenant, "rules-test") {
            context.getBean(TransactionRunner::class.java).inTransaction(block)
        }

    protected fun resolvedAt(date: LocalDate): PackVersion? =
        inTenant { ArmedPackLookup(context.getBean(DSLContext::class.java)).resolve(PackType(type), tenant, date) }

    protected fun artefactBytes(version: PackVersion): List<String> =
        inTenant {
            val registry =
                PersistedPackRegistry(
                    context.getBean(DSLContext::class.java),
                    context.getBean(TenantContext::class.java),
                )
            val a = registry.artefactOf(version)!!
            listOf(String(a.manifest), String(a.checksums), a.signature.joinToString(","), String(a.contents))
        }

    protected fun packVersion(label: String) =
        PackVersion(
            PackManifest(
                PackId("$type-pack"),
                PackType(type),
                PackVersionLabel(label),
                d0.minusDays(150),
                null,
                "1.0.0",
            ),
            null,
        )

    protected fun grantRollback(user: String) = grant(user, roles(DeploymentForm.SAAS, ROLLBACK_KEY).first())
}

@SpringBootTest(
    classes = [
        PolicyFixtures::class,
        ImportFixtures::class,
        ArmingFixtures::class,
        CorrectionFixtures::class,
        RulesApplication::class,
    ],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class PackCorrectionIT(
    @LocalServerPort port: Int,
    @Autowired replica: AuthorityReplica,
    @Autowired clock: MovableClock,
    @Autowired context: ApplicationContext,
    @Autowired policy: PolicyReplica,
) : CorrectionHarness(port, replica, clock, context, policy) {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) = registerCorrection(registry)
    }

    private fun assertActivationRecorded(label: String) {
        // the acting user ID, not a role, is stored; the workload principal stays in registered_by (INV-Q-10647)
        scalar(
            "select activated_by from rules.pack_registry where pack_type = ? and pack_version = ?",
            type,
            label,
        ) shouldBe
            "u-roll"
        scalar(
            "select activated_by from rules.pack_registry where pack_type = ? and pack_version = ?",
            type,
            bad,
        ) shouldBe
            null
        // RulePackActivated for the superseding row went through the outbox, once, as a platform-class event
        scalar(
            "select count(*)::text from rules.outbox where type = 'com.dynokore.gst.platform.RulePackActivated.v1' " +
                "and topic = 'gst.platform.rule-pack-activated.v1' and subject = ?",
            "$type@$label",
        ) shouldBe "1"
    }

    @Test
    fun `INV-AC-23932 rollbackPack arms the superseding pack from the bad pack's date and refuses another date`() {
        seedBadPack()
        grantRollback("u-roll")
        val badBefore = registryRow(bad)
        val earlierBefore = registryRow(earlier)
        val answer = rollback("u-roll")
        answer.statusCode() shouldBe 200
        answer.body() shouldContain "ARMED"
        answer.body() shouldContain d0.toString()
        registryRow(bad) shouldBe badBefore
        registryRow(earlier) shouldBe earlierBefore
        scalar("select count(*) from rules.pack_registry where pack_type = ?", type) shouldBe "3"
        val label = "$bad-rollback"
        scalar(
            "select effective_from::text || '/' || status || '/' || artefact_version from rules.pack_registry " +
                "where pack_type = ? and pack_version = ?",
            type,
            label,
        ) shouldBe
            "$d0/ARMED/$earlier"
        assertActivationRecorded(label)
        // resolution by transaction date: the superseding row, not the bad row (equal effective_from, later
        // registered_at)
        val mid = d0.plusDays(60)
        resolvedAt(mid)!!.version.value shouldBe label
        resolvedAt(d0)!!.version.value shouldBe label
        resolvedAt(d0.minusDays(1))!!.version.value shouldBe earlier
        // the artefact of the superseding pack equals the earlier pack's by equality; its own label has no artefact row
        artefactBytes(resolvedAt(mid)!!) shouldBe artefactBytes(packVersion(earlier))
        scalar("select count(*) from rules.pack_artefact where pack_type = ?", type) shouldBe "1"
        // any other date: 400 SRT-001, nothing stored; the clock date and one day later than D0
        listOf(java.time.LocalDate.now(clock), d0.plusDays(1)).forEach { other ->
            val before = snapshot()
            val refused = rollback("u-roll", other)
            refused.statusCode() shouldBe 400
            refused.body() shouldContain "SRT-001"
            snapshot() shouldBe before
        }
        // look-alike: the label of another version is not the superseding row
        scalar(
            "select count(*) from rules.pack_registry where pack_type = ? and pack_version = ?",
            type,
            "$bad-rollbacks",
        ) shouldBe "0"
    }

    @Test
    fun `INV-AC-6925 a wrong armed pack is corrected by a superseding pack effective from the bad pack's date`() {
        seedBadPack()
        grantRollback("u-roll")
        rollback("u-roll").statusCode() shouldBe 200
        scalar(
            "select status || '/' || effective_from from rules.pack_registry where pack_type = ? and pack_version = ?",
            type,
            bad,
        ) shouldBe "ARMED/$d0"
        // a repeat of the same call stores no second row
        rollback("u-roll").statusCode() shouldBe 200
        scalar("select count(*) from rules.pack_registry where pack_type = ?", type) shouldBe "3"
    }

    @Test
    fun `INV-AC-1055 correction part the superseding reversal is effective-dated from the original date`() {
        seedBadPack()
        grantRollback("u-roll")
        rollback("u-roll").statusCode() shouldBe 200
        scalar(
            "select effective_from::text from rules.pack_registry where pack_type = ? and pack_version = ?",
            type,
            "$bad-rollback",
        ) shouldBe d0.toString()
    }

    @Test
    fun `INV-AC-23932 refused caller on rollbackPack leaves every table of schema rules equal`() {
        seedBadPack()
        grantRollback("u-roll")
        val before = snapshot()
        val calls = UseCaseCalls.permissionChecks.get()
        val alt = rollback("u-roll", token = workloadToken(ALT))
        alt.statusCode() shouldBe 403
        alt.body() shouldContain "SRT-004"
        rollback("u-roll", token = null).statusCode() shouldBe 401
        snapshot() shouldBe before
        UseCaseCalls.permissionChecks.get() shouldBe calls
        // the named caller reaches the use case: a user without the key is refused by the use case, not by the edge
        rollback("u-nobody").statusCode() shouldBe 403
        UseCaseCalls.permissionChecks.get() shouldBe calls + 1
        snapshot() shouldBe before
        // the comparison can fail: the named caller with the key changes the tables
        rollback("u-roll").statusCode() shouldBe 200
        (snapshot() == before) shouldBe false
    }

    @Test
    fun `INV-AC-23932 the permission constants equal the x-operation permission of the pinned contract`() {
        val contract =
            String(PacksController::class.java.getResourceAsStream("/contract/openapi.yaml")!!.readAllBytes())
        contract shouldContain "permission: ${PacksController.REGULATORY_PACK_ROLLBACK}"
        contract shouldContain "permission: ${PacksController.REGULATORY_PACK_REPROCESS}"
        PacksController.REGULATORY_PACK_ROLLBACK shouldBe "regulatory-pack.rollback"
        PacksController.REGULATORY_PACK_REPROCESS shouldBe "regulatory-pack.reprocess"
    }
}
