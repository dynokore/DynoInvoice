package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.AuthoriseController
import com.dynokore.gst.access.contract.model.AuthorityIncarnationResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.ResponseEntity
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * `bumpAuthorityIncarnation` over the real IdempotencyStore bean on the access schema (INV-AC-22070, ruling (2):
 * API-15, TPL-04). The permission is decided from `access.installation-roles` (user:eng-1 is platform.engineer, the
 * SAAS key holder; user:op-1 is deployment.operator, refused in SAAS) and the catalogue matrix of the deployed form.
 */
class BumpAuthorityIncarnationReplayTest {
    private val engineer = "user:eng-1"
    private val operator = "user:op-1"

    private fun run(block: (AssertableWebApplicationContext) -> Unit) {
        BoundaryFixture
            .runner(catalogueMatrix = true)
            .run { context ->
                context.startupFailure?.let { throw it }
                block(context)
            }
    }

    private fun sql(statement: String) =
        BoundaryFixture.asSuperuser { it.createStatement().use { s -> s.execute(statement) } }

    private fun <T> query(
        statement: String,
        read: (java.sql.ResultSet) -> T,
    ): T =
        BoundaryFixture.asSuperuser { c ->
            c.createStatement().use { s ->
                s.executeQuery(statement).use {
                    it.next()
                    read(it)
                }
            }
        }

    private fun stored(): Long = query("select incarnation from access.authority_incarnation") { it.getLong(1) }

    private fun keyRows(key: UUID): Long =
        query("select count(*) from access.idempotency_key where idempotency_key = '$key'") { it.getLong(1) }

    private fun given(incarnation: Long) {
        sql("delete from access.authority_incarnation")
        sql("insert into access.authority_incarnation values (true, $incarnation, 0)")
    }

    private fun bump(
        c: AssertableWebApplicationContext,
        actor: String?,
        key: UUID,
    ): AuthorityIncarnationResponse =
        call(c, actor, key).let { response ->
            assertThat(response.statusCode.value()).isEqualTo(200)
            response.body!!
        }

    /** The platform tenant is bound only through PlatformTenantBinding (INV-Q-2927); no actor means none bound. */
    private fun call(
        c: AssertableWebApplicationContext,
        actor: String?,
        key: UUID,
    ): ResponseEntity<AuthorityIncarnationResponse> {
        val controller = c.getBean(AuthoriseController::class.java)
        return if (actor == null) {
            controller.bumpAuthorityIncarnation(key)
        } else {
            c
                .getBean(
                    PlatformTenantBinding::class.java,
                ).bindPlatform(actor) { controller.bumpAuthorityIncarnation(key) }
        }
    }

    @Test
    fun `INV-AC-22070 the idempotency store is the real bean`() =
        run { c ->
            val store = c.getBean(IdempotencyStore::class.java)
            assertThat(store.javaClass.name).doesNotContain("Mockito").doesNotContain("Mock")
        }

    @Test
    fun `INV-AC-22070 the same key twice by the permitted actor answers twice alike and raises once`() =
        run { c ->
            given(1_000)
            val key = UUID.randomUUID()
            val first = bump(c, engineer, key)
            val afterFirst = stored()
            val second = bump(c, engineer, key)
            assertThat(second).isEqualTo(first)
            assertThat(first.previousIncarnation).isEqualTo(1_000L)
            assertThat(first.incarnation).isGreaterThanOrEqualTo(Instant.now().epochSecond - 60)
            assertThat(stored()).isEqualTo(first.incarnation).isEqualTo(afterFirst)
            assertThat(keyRows(key)).isEqualTo(1L)
        }

    @Test
    fun `INV-AC-22070 a new key by the same actor raises again to max of previous plus one and now`() =
        run { c ->
            val ahead = Instant.now().epochSecond + 100_000
            given(ahead)
            val first = bump(c, engineer, UUID.randomUUID())
            assertThat(first.incarnation).isEqualTo(ahead + 1)
            val second = bump(c, engineer, UUID.randomUUID())
            assertThat(second.previousIncarnation).isEqualTo(ahead + 1)
            assertThat(second.incarnation).isEqualTo(ahead + 2)
            assertThat(stored()).isEqualTo(ahead + 2)
        }

    @Test
    fun `INV-AC-22070 refused actors get 403 with the value unchanged and no key row`() =
        run { c ->
            given(1_000)
            listOf(operator, "user:nobody").forEach { actor ->
                val key = UUID.randomUUID()
                val refusal =
                    assertThrows<ApiRefusal>(actor) { call(c, actor, key) }
                assertThat(refusal.code).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
                assertThat(stored()).isEqualTo(1_000L)
                assertThat(keyRows(key)).isZero()
            }
            // control: the permitted actor succeeds on the same context
            assertThat(bump(c, engineer, UUID.randomUUID()).previousIncarnation).isEqualTo(1_000L)
        }

    @Test
    fun `INV-AC-22070 the same key by two calls at once ends in one raise`() =
        run { c ->
            given(1_000)
            val key = UUID.randomUUID()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CountDownLatch(1)
                val calls =
                    List(2) {
                        Callable<Result<AuthorityIncarnationResponse>> {
                            start.await()
                            runCatching { bump(c, engineer, key) }
                        }
                    }
                val futures = calls.map { pool.submit(it) }
                start.countDown()
                val results = futures.map { it.get() }
                val bodies = results.mapNotNull { it.getOrNull() }
                assertThat(bodies).isNotEmpty()
                results.mapNotNull { it.exceptionOrNull() }.forEach {
                    assertThat(it).isInstanceOf(ApiRefusal::class.java)
                    assertThat((it as ApiRefusal).code).isEqualTo(ClosedErrorCode.IN_FLIGHT)
                }
                assertThat(bodies.toSet()).hasSize(1)
                assertThat(stored()).isEqualTo(bodies.first().incarnation)
                assertThat(bodies.first().previousIncarnation).isEqualTo(1_000L)
                assertThat(keyRows(key)).isEqualTo(1L)
            } finally {
                pool.shutdownNow()
            }
        }
}
