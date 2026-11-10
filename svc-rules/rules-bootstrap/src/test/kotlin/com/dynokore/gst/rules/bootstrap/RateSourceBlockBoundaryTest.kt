package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.packs.RefusingRateSourcePort
import com.dynokore.gst.rules.adapters.packs.ReplicaRateSourcePort
import com.dynokore.gst.rules.application.HolderCopyPort
import com.dynokore.gst.rules.application.HolderCopyResult
import com.dynokore.gst.rules.application.PackActionAuthority
import com.dynokore.gst.rules.application.PackApprovalDecision
import com.dynokore.gst.rules.application.PackApprovalDecisionPort
import com.dynokore.gst.rules.application.RateSourcePort
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.WorkloadToken
import com.dynokore.platform.serviceruntime.identity.WorkloadTokenVerifier
import com.dynokore.platform.serviceruntime.identity.WorkloadVerification
import com.dynokore.platform.serviceruntime.replica.EntityProfileChanged
import com.dynokore.platform.serviceruntime.replica.ProfileEntry
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.LocalDate
import java.util.UUID

private const val RS_CALLER = "console-bff"
private const val RS_USER_PREFIX = "Bearer rs-user-"
private const val RS_SCOPE_INSERT_PACK =
    "insert into rules.pack_registry (id, tenant_id, pack_id, pack_type, pack_version, layer, status, " +
        "effective_from, effective_to, min_engine_version, supersedes, registered_by, entity_ref) " +
        "values (?::uuid, ?::uuid, ?, 'SCOPE', ?, 'TENANT_MAPPING', 'ARMED', '2020-01-01'::date, null, " +
        "'1.0.0', null, 'tenancy-probe', null) returning id"
private const val RS_SCOPE_INSERT_ARTEFACT =
    "insert into rules.pack_artefact (id, pack_id, pack_type, pack_version, tenant_id, effective_from, " +
        "effective_to, min_engine_version, manifest, checksums, signature, contents, trusted_anchor, " +
        "registered_by) values (?::uuid, ?, 'SCOPE', ?, ?::uuid, '2020-01-01'::date, null, '1.0.0', " +
        "'{}'::jsonb, '{}'::jsonb, ?, ?::jsonb, 'anchor', 'tenancy-probe') returning id"

/**
 * Identity doubles: the end user of a request is `user-a` or `user-b` of the tenant the token names, so every test owns
 * a tenant and the replica and the registry never mix between tests. The three other ports of the sign-off allow; the
 * `RateSourcePort` is deliberately not a double: the bean of the context is the one under test.
 */
@TestConfiguration(proxyBeanMethods = false)
class RateSourceBoundaryDoubles {
    @Bean
    fun rsEndUserPrincipalResolver(): EndUserPrincipalResolver =
        EndUserPrincipalResolver { authorization ->
            val rest = authorization?.removePrefix(RS_USER_PREFIX)?.takeIf { authorization.startsWith(RS_USER_PREFIX) }
            rest?.split(":")?.takeIf { it.size == 2 }?.let { EndUserPrincipal(it[0], TenantId.of(it[1]), emptySet()) }
        }

    @Bean
    fun rsWorkloadTokenVerifier(): WorkloadTokenVerifier =
        WorkloadTokenVerifier { token: WorkloadToken? ->
            val value = token?.value
            if (value != null && value.startsWith("wl-")) {
                WorkloadVerification.Verified("subject-of-" + value.removePrefix("wl-"))
            } else {
                WorkloadVerification.Refused("no test token")
            }
        }

    @Bean
    fun packActionAuthority(): PackActionAuthority = PackActionAuthority { _, _, _ -> true }

    @Bean
    fun packApprovalDecisionPort(): PackApprovalDecisionPort =
        PackApprovalDecisionPort { PackApprovalDecision.Allow(false, null, "OPTION-1", emptySet()) }

    @Bean
    fun holderCopyPort(): HolderCopyPort = HolderCopyPort { HolderCopyResult.Written }
}

/**
 * The rate-source block of INV-REQ-504 in the real composition (INV-T-16546; INV-AC-1510, INV-AC-15992): the
 * `RateSourcePort` bean is [ReplicaRateSourcePort] over the structure replica and the persisted SCOPE pack, and
 * `approvePack` refuses with RUL-007 naming the INFERRED source an entity elected.
 */
@SpringBootTest(
    classes = [RateSourceBoundaryDoubles::class, VersionedBuild::class, RulesApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "service-runtime.idempotency.schema=rules",
        "service-runtime.workload-identity.callers[0].service=$RS_CALLER",
        "service-runtime.workload-identity.callers[0].subject=subject-of-$RS_CALLER",
    ],
)
class RateSourceBlockBoundaryTest(
    @Autowired private val context: ApplicationContext,
    @Autowired private val replica: StructureReplica,
    @org.springframework.boot.test.web.server.LocalServerPort port: Int,
) : MappingPackSupport(port) {
    private fun user(
        who: String,
        tenant: TenantId,
    ) = "$RS_USER_PREFIX$who:$tenant"

    private fun scopePack(
        tenant: TenantId,
        vararg sources: Pair<String, String>,
    ) {
        val version = "rs-${UUID.randomUUID().toString().take(8)}"
        val packId = "rs-scope-$version"
        val list =
            sources.joinToString(",") {
                """{"sourceId":"${it.first}","name":"n","bases":["b"],"sourceClass":"${it.second}",""" +
                    """"sourceReference":"invented"}"""
            }
        value(tenant, RS_SCOPE_INSERT_PACK, UUID.randomUUID().toString(), tenant.toString(), packId, version)
        value(
            tenant,
            RS_SCOPE_INSERT_ARTEFACT,
            UUID.randomUUID().toString(),
            packId,
            version,
            tenant.toString(),
            byteArrayOf(1),
            """{"exchangeRateSources":[$list]}""",
        )
    }

    /** One EntityProfileChanged of the fake feed: an entity electing [source] (or none) in force from 2026-01-01. */
    private fun feed(
        tenant: TenantId,
        source: String?,
    ) = replica.accept(entity(tenant, "rs-entity", source))

    private fun entity(
        tenant: TenantId,
        ref: String,
        source: String?,
    ) = EntityProfileChanged(
        tenant,
        ref,
        StructureNode(
            StructureNodeKind.ENTITY,
            null,
            false,
            "E",
            ref,
            listOfNotNull(source?.let { ProfileEntry("exchangeRateSource", it, LocalDate.of(2026, 1, 1)) }),
            1,
        ),
    )

    private fun submitted(tenant: TenantId): String {
        val version = "rs-v-${UUID.randomUUID().toString().take(8)}"
        val packId = seedPack(version, "SUBMITTED", tenant)
        deltaRow(tenant, version, "COMPLETE")
        return packId
    }

    private fun approveAs(
        packId: String,
        tenant: TenantId,
    ) = approve(packId, user("user-b", tenant))

    private fun statusOf(
        tenant: TenantId,
        packId: String,
    ) = rows(tenant, "select status from rules.pack_registry where pack_id = '$packId'").single()["status"]

    private fun tenant() = TenantId.of(UUID.randomUUID().toString())

    @Test
    fun `the bean of type RateSourcePort is the replica port and the refusing class stays in main`() {
        context.getBean(RateSourcePort::class.java)::class.java shouldBe ReplicaRateSourcePort::class.java
        RefusingRateSourcePort().blocking(tenant(), LocalDate.of(2026, 1, 1)) shouldBe null
    }

    @Test
    fun `INV-AC-1510 an elected INFERRED source in the pack in force refuses approvePack with RUL-007 naming the id`() {
        val tenant = tenant()
        replica.onSnapshotComplete(1)
        scopePack(tenant, "RS-INF" to "INFERRED", "RS-PRI" to "PRIMARY")
        feed(tenant, "RS-INF")
        val packId = submitted(tenant)
        val answer = approveAs(packId, tenant)
        answer.statusCode() shouldBe 422
        json(answer).get("code").asString() shouldBe "RUL-007"
        answer.body() shouldContain "RS-INF"
        statusOf(tenant, packId) shouldBe "SUBMITTED"
    }

    @Test
    fun `INV-AC-15992 an elected INFERRED source refuses approvePack with RUL-007 naming only that source id`() {
        val tenant = tenant()
        replica.onSnapshotComplete(1)
        scopePack(tenant, "RS-INF" to "INFERRED", "RS-PRI" to "PRIMARY")
        replica.accept(entity(tenant, "rs-entity-a", "RS-INF"))
        replica.accept(entity(tenant, "rs-entity-b", "RS-PRI"))
        val packId = submitted(tenant)
        val answer = approveAs(packId, tenant)
        answer.statusCode() shouldBe 422
        json(answer).get("code").asString() shouldBe "RUL-007"
        val detail = answer.body()
        detail shouldContain "RS-INF"
        detail shouldNotContain "RS-PRI"
        statusOf(tenant, packId) shouldBe "SUBMITTED"
    }

    @Test
    fun `INV-AC-15992 a tenant with no election is not refused for the rate source`() {
        val tenant = tenant()
        replica.onSnapshotComplete(1)
        scopePack(tenant, "RS-INF" to "INFERRED")
        feed(tenant, null)
        val packId = submitted(tenant)
        val answer = approveAs(packId, tenant)
        withClueOf(answer.body()) { answer.statusCode() shouldBe 200 }
        statusOf(tenant, packId) shouldBe "APPROVED"
    }

    @Test
    fun `an election of another tenant does not block this tenant`() {
        val tenant = tenant()
        val other = tenant()
        replica.onSnapshotComplete(1)
        scopePack(tenant, "RS-INF" to "INFERRED")
        feed(other, "RS-INF")
        val packId = submitted(tenant)
        withClueOf("other tenant") { approveAs(packId, tenant).statusCode() shouldBe 200 }
    }

    @Test
    fun `a replica that is not synced refuses approvePack`() {
        val tenant = tenant()
        scopePack(tenant, "RS-INF" to "INFERRED")
        feed(tenant, null)
        val packId = submitted(tenant)
        replica.onDisconnected()
        try {
            val answer = approveAs(packId, tenant)
            answer.statusCode() shouldBe 503
            json(answer).get("code").asString() shouldBe "SRT-013"
            statusOf(tenant, packId) shouldBe "SUBMITTED"
        } finally {
            replica.onSnapshotComplete(1)
        }
    }

    private fun withClueOf(
        clue: String,
        block: () -> Unit,
    ) = io.kotest.assertions.withClue(clue, block)

    companion object {
        @DynamicPropertySource
        @JvmStatic
        fun props(registry: DynamicPropertyRegistry) = registerCatalogue(registry)
    }
}
