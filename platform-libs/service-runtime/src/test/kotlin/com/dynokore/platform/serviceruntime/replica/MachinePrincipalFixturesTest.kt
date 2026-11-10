package com.dynokore.platform.serviceruntime.replica

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.companionObject
import kotlin.reflect.full.declaredMembers

/** INV-AC-19620: the test-fixtures factory builds a MACHINE principal; no production code can. */
class MachinePrincipalFixturesTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-10T00:00:00Z"), ZoneOffset.UTC)
    private val roles =
        OperationRoleMap {
            if (it == "approve") mapOf("machine.filing-workflow" to CellAllowance.PERMITTED) else emptyMap()
        }
    private val replica = AuthorityReplica(clock, roles)

    init {
        FakeFeed(replica)
    }

    private fun decide(
        type: String,
        action: String = "approve",
    ) = replica.decide(
        AuthorizationRequest(
            TENANT_A,
            MachinePrincipalFixtures.machine("p1", TENANT_A, MachineEntityCoverage.Entity("e1"), type),
            "e1",
            action,
        ),
        deadline(clock),
    )

    @Test
    fun `INV-AC-19620 a fixture machine principal has kind MACHINE and the given type`() {
        val coverage = MachineEntityCoverage.Entity("e1")
        val p = MachinePrincipalFixtures.machine("p1", TENANT_A, coverage, "filing-workflow")
        p.kind shouldBe PrincipalKind.MACHINE
        p.machineType shouldBe "filing-workflow"
        p.id shouldBe "p1"
    }

    @Suppress("MaxLineLength", "ktlint:standard:max-line-length") // CS-50: the INV-T-19621 case name is 121 columns
    @Test
    fun `INV-AC-19620 the replica grants the listed machine type and refuses an unlisted type and an unlisted action`() {
        decide("filing-workflow") shouldBe AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
        val noGrant = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)
        decide("reporting") shouldBe noGrant
        decide("filing-workflow", "other") shouldBe noGrant
    }

    @Test
    fun `INV-AC-19620 the public constructor still refuses the machine kind`() {
        shouldThrow<IllegalArgumentException> { Principal("p1", PrincipalKind.MACHINE) }
    }

    @Test
    fun `INV-AC-19620 no public method named machine exists on Principal or its companion`() {
        val companion = Principal::class.companionObject ?: error("Principal has no companion object")
        // effective visibility: the most restrictive of the member and every containing class (Kotlin specification)
        val order = listOf(KVisibility.PRIVATE, KVisibility.INTERNAL, KVisibility.PROTECTED, KVisibility.PUBLIC)
        val rank = order.withIndex().associate { (i, v) -> v to i }
        val own = { c: KCallable<*> -> rank.getValue(c.visibility ?: KVisibility.PRIVATE) }
        val box = { k: KClass<*> -> rank.getValue(k.visibility ?: KVisibility.PRIVATE) }
        val onPrincipal =
            Principal::class
                .declaredMembers
                .filter { it.name == "machine" }
                .map { minOf(own(it), box(Principal::class)) }
        val onCompanion =
            companion.declaredMembers
                .filter { it.name == "machine" }
                .map { minOf(own(it), box(companion), box(Principal::class)) }
        val effective = onPrincipal + onCompanion
        effective.isEmpty() shouldBe false
        effective.filter { it >= rank.getValue(KVisibility.PROTECTED) } shouldBe emptyList()

        // a public top-level function named machine in AuthorityReplica.kt lives on the file facade class
        val facadeName = Principal::class.java.packageName + ".AuthorityReplicaKt"
        val facade = runCatching { Class.forName(facadeName) }
        val pattern = Regex("machine(-[A-Za-z0-9_]+)?")
        facade
            .getOrNull()
            ?.declaredMethods
            .orEmpty()
            .filter { Modifier.isPublic(it.modifiers) && Modifier.isStatic(it.modifiers) && pattern.matches(it.name) }
            .map { it.name } shouldBe emptyList()
    }
}
