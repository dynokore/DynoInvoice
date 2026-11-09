package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.authorise.MachineAuthorise
import com.dynokore.gst.access.application.authorise.MachineDecision
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.principal.PrincipalRepository
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.principal.MachinePermissionSets
import com.dynokore.gst.access.domain.principal.PrincipalStatus
import com.dynokore.gst.access.domain.principal.PrincipalType
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The fixed permission sets and the scope of the six principal types (INV-REQ-584, INV-REQ-867, INV-REQ-911; INV-Q-8779
 * A), and the refusals they give at the authorisation decision. The rows are read from the staged contract bundle.
 */
class MachinePermissionTest {
    private val staged =
        Path
            .of("..", "access-contract", "build", "contracts-repo", ".bundle", "access", "schemas")
            .resolve("machine-principal-permissions.tsv")

    /** The (principalType, permission) pairs of the data file; `#` lines and the header are not rows. */
    private fun fileRows(): Set<Pair<PrincipalType, String>> =
        Files
            .readAllLines(staged)
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("principalType\t") }
            .map { it.split('\t') }
            .map { PrincipalType.valueOf(it[0]) to it[1] }
            .toSet()

    private fun differences(
        service: Set<Pair<PrincipalType, String>>,
        file: Set<Pair<PrincipalType, String>>,
    ): List<String> =
        PrincipalType.entries.mapNotNull { type ->
            val a = service.filter { it.first == type }.map { it.second }.toSet()
            val b = file.filter { it.first == type }.map { it.second }.toSet()
            if (a == b) null else "$type: service $a, file $b"
        }

    @Test
    fun `the permission keys the service enforces equal the rows of the staged data file for each of the six types`() {
        val service = MachinePermissionSets.STANDARD.pairs()
        assertThat(differences(service, fileRows())).isEmpty()
        PrincipalType.entries.forEach { assertThat(MachinePermissionSets.STANDARD.permissionsOf(it)).isNotEmpty() }
    }

    @Test
    fun `probe adding the pair reporting drain run to the service set makes the comparison fail`() {
        val widened = MachinePermissionSets.STANDARD.pairs() + (PrincipalType.REPORTING to "drain.run")
        assertThat(differences(widened, fileRows()))
            .hasSize(1)
            .first()
            .asString()
            .startsWith("REPORTING")
    }

    @Test
    fun `probe deleting the MONITORING rows stops the start-up and the set cannot be built`() {
        val without = MachinePermissionSets.STANDARD.pairs().filter { it.first != PrincipalType.MONITORING }
        assertThatThrownBy { MachinePermissionSets.of(without) }.hasMessageContaining("MONITORING")
        BoundaryFixture
            .runner()
            .withBean(MachinePermissionSets::class.java, { MachinePermissionSets.of(without) })
            .run { context -> assertThat(context.startupFailure).isNotNull() }
    }

    @Test
    fun `INV-AC-920 and INV-AC-8615 the set test is the replica's, a foreign type's key is not refused here`() =
        PrincipalKit.run { kit ->
            val workflow = kit.create("FILING_WORKFLOW", scope = "entity:ENT-1")
            assertThat(kit.decide(workflow.principalRef, "readiness.run", "entity:ENT-1")).isEqualTo("ALLOW")
            // not refused here: the serving services refuse ingest keys for this type through AuthorityReplica.decide
            assertThat(kit.decide(workflow.principalRef, "ingestion.channels.write", "entity:ENT-1")).isEqualTo("ALLOW")
            // another entity is outside its scope, which this class still refuses
            assertThat(kit.decide(workflow.principalRef, "readiness.run", "entity:ENT-2")).isEqualTo("DENY")
            assertThat(kit.decide("PRN-0000000000000000", "readiness.run", "entity:ENT-1")).isEqualTo("DENY")
        }

    @Test
    fun `INV-AC-13561 and INV-AC-1779 a reporting principal asking drain is not refused here`() =
        PrincipalKit.run { kit ->
            val reporting = kit.create("REPORTING", scope = "entity:ENT-1")
            assertThat(kit.decide(reporting.principalRef, "machine.drain.run", "entity:ENT-1")).isEqualTo("ALLOW")
            assertThat(kit.decide(reporting.principalRef, "machine.drain.run", "entity:ENT-2")).isEqualTo("DENY")
        }

    @Test
    fun `INV-AC-8615 a revoked principal is still refused as REVOKED`() =
        PrincipalKit.run { kit ->
            val reporting = kit.create("REPORTING", scope = "entity:ENT-1")
            kit.revoke(reporting.principalRef)
            assertThat(kit.decide(reporting.principalRef, "submissions.read", "entity:ENT-1")).isEqualTo("DENY")
        }

    private fun authoriser(principal: ServicePrincipal?): MachineAuthorise {
        val repository =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(PrincipalRepository::class.java),
            ) { _, method, _ ->
                check(method.name == "find") { "unexpected ${method.name}" }
                principal
            } as PrincipalRepository
        val directory =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(EntityDirectory::class.java),
            ) { _, method, _ -> error("unexpected ${method.name}") } as EntityDirectory
        val tx =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        return MachineAuthorise(tx, repository, directory)
    }

    private fun principal(
        type: PrincipalType,
        scope: String,
        status: PrincipalStatus = PrincipalStatus.ACTIVE,
    ) = ServicePrincipal("PRN-1", TenantRef("T-1"), type, scope, null, status, 1)

    @Test
    fun `INV-AC-8615 the reason of the decision is by equality for each remaining denial and a set miss is not one`() {
        val target = EntityRef("E-001")

        fun reason(
            p: ServicePrincipal?,
            permission: String = "machine.drain.run",
        ) = (authoriser(p).handle("PRN-1", permission, target) as? MachineDecision.Deny)?.reasonCode
        assertThat(reason(null)).isEqualTo("NO_PRINCIPAL")
        assertThat(
            reason(principal(PrincipalType.REPORTING, "entity:E-001", PrincipalStatus.REVOKED)),
        ).isEqualTo("REVOKED")
        assertThat(reason(principal(PrincipalType.REPORTING, "entity:E-002"))).isEqualTo("OUT_OF_SCOPE")
        assertThat(reason(principal(PrincipalType.MONITORING, "installation "))).isEqualTo("OUT_OF_SCOPE")
        assertThat(reason(principal(PrincipalType.REPORTING, "entity:E-001"))).isNull()
        assertThat(reason(principal(PrincipalType.MONITORING, "installation"), "submissions.read")).isNull()
    }

    @Test
    fun `INV-AC-1945 and INV-AC-8619 a monitoring principal reads metrics and health, tenant data is the replica`() =
        PrincipalKit.run { kit ->
            val monitoring = kit.create("MONITORING", scope = "installation")
            assertThat(kit.decide(monitoring.principalRef, "metrics.read", "entity:ENT-1")).isEqualTo("ALLOW")
            assertThat(kit.decide(monitoring.principalRef, "health.read", "entity:ENT-1")).isEqualTo("ALLOW")
            // the set test is the replica's (INV-T-13569): this class no longer refuses a key outside the set
            assertThat(kit.decide(monitoring.principalRef, "submissions.read", "entity:ENT-1")).isEqualTo("ALLOW")
            assertThat(kit.decide("PRN-0000000000000000", "metrics.read", "entity:ENT-1")).isEqualTo("DENY")
        }

    @Test
    fun `a monitoring principal has the scope installation and nothing else, an ingest principal never has it`() =
        PrincipalKit.run { kit ->
            fun status(
                type: String,
                scope: String,
            ) = PrincipalKit.refusal { kit.create(type, scope = scope) }

            assertThat(kit.create("MONITORING", scope = "installation").principalType).isEqualTo("MONITORING")
            listOf(
                "installation ",
                "INSTALLATION",
                "Installation",
                "tenant:${kit.tenant}",
                " installation",
                "installation\n",
            ).forEach { assertThat(status("MONITORING", it)).describedAs(it).contains("SRT-001") }
            assertThat(status("INGEST", "installation")).contains("SRT-001")
            assertThat(status("INGEST", "entity:ENT-3F2A9C")).isNull()
            assertThat(kit.principalCount()).isEqualTo(2)
        }
}
