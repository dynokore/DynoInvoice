package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.election.CallerSource
import com.dynokore.gst.tenancy.application.election.ElectionCommands
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionRow
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElectionStore
import com.dynokore.gst.tenancy.application.election.ExemptPurchaseElections
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.contract.model.ElectionRequest
import com.dynokore.gst.tenancy.domain.ElectionKeys
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-18133, route side: the status each outcome of `recordElection` for the exempt-purchase election takes. The
 * controller is built over the real [ElectionCommands] and [ExemptPurchaseElections] with in-memory ports; the
 * permission is decided by the [ActionAuthority] port (never a role name).
 */
class ExemptPurchaseRouteTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val october = LocalDate.of(2026, 10, 1)
    private val basis = "Legal fees relate to exempt supplies"
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val store = FakeStore()
    private val published = mutableListOf<EntityProfileChange>()
    private var allowed = true

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private inline fun <reified T : Any> idle(): T =
        T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                if (method.returnType == List::class.java) emptyList<Any>() else null
            },
        )

    private val structure: StructureStore =
        StructureStore::class.java.cast(
            Proxy.newProxyInstance(StructureStore::class.java.classLoader, arrayOf(StructureStore::class.java)) {
                _,
                method,
                args,
                ->
                when (method.name) {
                    "entity" -> entity.takeIf { it.ref == args[0] }
                    "nextStructureVersion" -> 2L
                    "touchEntity" -> null
                    else -> error("${method.name} is not used")
                }
            },
        )

    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String = "actor"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val authority = ActionAuthority { _, _, _ -> allowed }
    private val clock = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC)
    private val commands =
        ElectionCommands(
            Direct,
            structure,
            idle(),
            idle(),
            object : EntityProfileChangePublisher {
                override fun publish(change: EntityProfileChange) {
                    published += change
                }
            },
            idle(),
            idle(),
            idle(),
            authority,
            { "user-1" },
            idle(),
            idle(),
            tenants,
            ExemptPurchaseElections(Direct, structure, store, authority, { "user-1" }, idle(), tenants, clock),
            store,
            clock,
        )

    /** The collaborators the route never touches are allocated without a constructor: a call on them would fail. */
    private inline fun <reified T : Any> unused(): T {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return T::class.java.cast((field.get(null) as sun.misc.Unsafe).allocateInstance(T::class.java))
    }

    private val controller =
        EntitiesController(
            unused(),
            unused(),
            unused(),
            unused(),
            unused(),
            tenants,
            commands,
            unused(),
            unused(),
            unused(),
            authority,
            unused(),
            unused(),
            CallerSource { "user-1" },
        )

    private fun request(
        election: String = ElectionKeys.EXEMPT_PURCHASE_CODES,
        taxCodes: Set<String>? = setOf("TX-N33", "TX-ESS"),
        basis: String? = this.basis,
    ) = ElectionRequest(election, october, taxCodes, basis)

    private fun record(request: ElectionRequest) = controller.recordElection("E1", UUID.randomUUID(), request)

    private fun refusal(request: ElectionRequest): ApiRefusal = assertThrows<ApiRefusal> { record(request) }

    @Test
    fun `INV-AC-18133 the route answers 201 for a first record with both properties`() {
        val response = record(request())
        response.statusCode shouldBe HttpStatus.CREATED
        response.body!!.taxCodes shouldBe setOf("TX-N33", "TX-ESS")
        response.body!!.basis shouldBe basis
        published.size shouldBe 1
    }

    @Test
    fun `INV-AC-18133 the route answers 200 for an identical repeat and publishes nothing more`() {
        record(request())
        val response = record(request())
        response.statusCode shouldBe HttpStatus.OK
        response.body!!.basis shouldBe basis
        store.rows.size shouldBe 1
        published.size shouldBe 1
    }

    @Test
    fun `INV-AC-18133 a missing taxCodes is 400 SRT-001 and stores nothing`() {
        refusal(request(taxCodes = null)).code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-18133 a missing basis is 400 SRT-001 and stores nothing`() {
        refusal(request(basis = null)).code shouldBe ClosedErrorCode.CONTRACT_VIOLATION
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-18133 taxCodes on another election key is 400 SRT-001 and stores nothing`() {
        listOf(
            ElectionKeys.INVOICE_DATE,
            ElectionKeys.CLAIMABLE_PORTION,
            ElectionKeys.EXEMPT_PURCHASE_CODES.lowercase(),
        ).forEach { refusal(request(election = it)).code shouldBe ClosedErrorCode.CONTRACT_VIOLATION }
        refusal(request(election = ElectionKeys.POSTING_DATE, taxCodes = null)).code shouldBe
            ClosedErrorCode.CONTRACT_VIOLATION
        store.rows.size shouldBe 0
        published.size shouldBe 0
    }

    @Test
    fun `INV-AC-18133 a caller without the permission is 403 SRT-004 and stores nothing`() {
        allowed = false
        refusal(request()).code shouldBe ClosedErrorCode.ACCESS_DENIED
        store.rows.size shouldBe 0
        published.size shouldBe 0
    }

    private class FakeStore : ExemptPurchaseElectionStore {
        val rows = mutableListOf<ExemptPurchaseElectionRow>()

        override fun history(entityRef: String) = rows.filter { it.entityRef == entityRef }

        override fun insert(row: ExemptPurchaseElectionRow) {
            rows += row
        }
    }
}
