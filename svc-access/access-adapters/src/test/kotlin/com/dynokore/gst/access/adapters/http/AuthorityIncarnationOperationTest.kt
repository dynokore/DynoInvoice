package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.adapters.elevation.CommandKey
import com.dynokore.gst.access.adapters.elevation.ElevationCommands
import com.dynokore.gst.access.adapters.persistence.AuthorityIncarnationStore
import com.dynokore.gst.access.adapters.persistence.Bumped
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.contract.model.AuthorityIncarnationResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `bumpAuthorityIncarnation` (INV-AC-22070): 200 with the raised value for the key holder, else 403, no change. */
class AuthorityIncarnationOperationTest {
    private val now = Instant.parse("2026-11-15T01:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val audits = mutableListOf<GrantAuditEntry>()
    private val recording =
        object : GrantAudit {
            override fun record(entry: GrantAuditEntry) {
                audits += entry
            }
        }

    private class Store(
        var value: Long,
        private val clock: Clock,
    ) : AuthorityIncarnationStore {
        var bumps = 0

        override fun current() = value

        override fun start() = value

        override fun bump(): Bumped {
            bumps++
            val previous = value
            value = maxOf(value + 1, clock.instant().epochSecond)
            return Bumped(previous, value)
        }
    }

    /** The real [ElevationCommands.once] stores the first answer under the key; this one does it in memory. */
    private val commands: ElevationCommands =
        Mockito.mock(ElevationCommands::class.java) { call ->
            if (call.method.name != "once") return@mock null
            @Suppress("UNCHECKED_CAST")
            val run = call.arguments[3] as () -> ElevationCommands.Reply<Any>
            val id = call.arguments[0] as CommandKey
            first.getOrPut(id) {
                val reply = run()
                ResponseEntity.status(reply.status).body(reply.body)
            }
        }
    private val first = mutableMapOf<CommandKey, ResponseEntity<*>>()

    private fun tenants(actor: String?) =
        object : TenantContext {
            override fun current() = TenantId.PLATFORM

            override fun currentActor() = actor

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private fun operation(
        store: Store,
        actor: String? = "user:eng",
        allowed: Boolean = true,
    ) = AuthorityIncarnationOperation(
        store,
        { who -> allowed && who == actor },
        commands,
        tenants(actor),
        recording,
    )

    private fun refusalOf(block: () -> Unit): ApiRefusal = assertThrows<ApiRefusal> { block() }

    @Test
    fun `INV-AC-22070 the key holder gets 200 with max of previous plus one and now, and the audit names the user`() {
        val store = Store(1_000, clock)
        val response = operation(store).bump(UUID.randomUUID())
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body as AuthorityIncarnationResponse
        assertThat(body.previousIncarnation).isEqualTo(1_000)
        assertThat(body.incarnation).isEqualTo(now.epochSecond)
        assertThat(store.value).isEqualTo(body.incarnation)
        assertThat(audits).hasSize(1)
        assertThat(audits.single().actor).isEqualTo("user:eng")
        // above the clock the value rises by one
        val high = Store(now.epochSecond + 50, clock)
        val raised = operation(high).bump(UUID.randomUUID()).body as AuthorityIncarnationResponse
        assertThat(raised.incarnation).isEqualTo(now.epochSecond + 51)
        assertThat(raised.previousIncarnation).isEqualTo(now.epochSecond + 50)
    }

    @Test
    fun `INV-AC-22070 a caller without the key gets 403 and the stored value stays`() {
        val store = Store(1_000, clock)
        val refusal = refusalOf { operation(store, allowed = false).bump(UUID.randomUUID()) }
        assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
        assertThat(store.value).isEqualTo(1_000)
        assertThat(store.bumps).isZero()
        assertThat(audits).isEmpty()
        // control: the same operation with the key succeeds
        assertThat(operation(store).bump(UUID.randomUUID()).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `INV-AC-22070 a call with no bound actor gets 403 and the stored value stays`() {
        val store = Store(1_000, clock)
        refusalOf { operation(store, actor = null).bump(UUID.randomUUID()) }
        assertThat(store.value).isEqualTo(1_000)
    }

    @Test
    fun `INV-AC-22070 a throwing check is not turned into an allowance by the operation`() {
        val store = Store(1_000, clock)
        val throwing =
            AuthorityIncarnationOperation(store, { error("boom") }, commands, tenants("user:eng"), recording)
        assertThrows<IllegalStateException> { throwing.bump(UUID.randomUUID()) }
        assertThat(store.value).isEqualTo(1_000)
    }

    @Test
    fun `INV-AC-22070 a replay with the same Idempotency-Key answers the first value and raises once`() {
        val store = Store(1_000, clock)
        val operation = operation(store)
        val key = UUID.randomUUID()
        val firstBody = operation.bump(key).body as AuthorityIncarnationResponse
        val replay = operation.bump(key).body as AuthorityIncarnationResponse
        assertThat(replay).isEqualTo(firstBody)
        assertThat(store.bumps).isEqualTo(1)
        // control: another key raises again
        operation.bump(UUID.randomUUID())
        assertThat(store.bumps).isEqualTo(2)
    }
}
