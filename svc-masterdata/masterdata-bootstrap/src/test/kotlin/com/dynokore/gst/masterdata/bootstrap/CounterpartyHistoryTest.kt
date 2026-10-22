package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** Last-write-wins updates with version history and no lost-update protection (INV-REQ-2576). */
class CounterpartyHistoryTest {
    @Test
    fun `INV-AC-2577 INV-AC-8379 two updates both remain in the history and the later is current`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val uen = unique("UEN")
            env.write(party, env.request("UEN" to uen, name = "First", address = ADDRESS))
            env.write(party, env.request("UEN" to uen, name = "Second"))
            val page = env.history(party)
            assertThat(page.items.map { Triple(it.version, it.current, it.counterparty.legalName) })
                .containsExactly(Triple(1, false, "First"), Triple(2, true, "Second"))
            assertThat(page.nextCursor).isNull()
        }

    @Test
    fun `INV-AC-2577 INV-AC-8379 concurrent updates are all kept and none is refused`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val pool = Executors.newFixedThreadPool(WRITERS)
            try {
                val results =
                    (1..WRITERS)
                        .map { n ->
                            pool.submit(
                                Callable {
                                    runCatching {
                                        env.write(
                                            party,
                                            env.request("UEN" to unique("UEN"), name = "W$n", address = ADDRESS),
                                        )
                                    }
                                },
                            )
                        }.map { it.get() }
                assertThat(results.filter { it.isFailure }.map { it.exceptionOrNull().toString() }).isEmpty()
            } finally {
                pool.shutdown()
            }
            val all = env.history(party).items
            assertThat(all.map { it.version }).containsExactly(1, 2, 3, 4, 5, 6, 7, 8)
            assertThat(all.map { it.current }).containsExactly(false, false, false, false, false, false, false, true)
            assertThat(all.map { it.counterparty.legalName }.toSet()).hasSize(WRITERS)
        }

    @Test
    fun `INV-AC-2577 a page of the history is followed by its cursor, an unknown party is 404 and a bad cursor 400`() =
        runCounterparty { env ->
            val party = unique("CTP")
            (1..8).forEach { n ->
                env.write(party, env.request("UEN" to unique("UEN"), name = "W$n", address = ADDRESS))
            }
            val first = env.history(party, limit = 3)
            assertThat(first.items.map { it.version }).containsExactly(1, 2, 3)
            assertThat(first.nextCursor).isEqualTo("3")
            assertThat(
                env.history(party, limit = 3, cursor = first.nextCursor).items.map { it.version },
            ).containsExactly(4, 5, 6)
            val last = env.history(party, limit = 3, cursor = "6")
            assertThat(last.items.map { it.version }).containsExactly(7, 8)
            assertThat(last.nextCursor).isNull()
            val unknown = runCatching { env.history(unique("NOPE")) }.exceptionOrNull()
            assertThat((unknown as ApiRefusal).code).isEqualTo(ClosedErrorCode.NOT_FOUND)
            val bad = runCatching { env.history(party, cursor = "x") }.exceptionOrNull()
            assertThat((bad as ApiRefusal).code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
        }

    private companion object {
        const val WRITERS = 8
    }
}
