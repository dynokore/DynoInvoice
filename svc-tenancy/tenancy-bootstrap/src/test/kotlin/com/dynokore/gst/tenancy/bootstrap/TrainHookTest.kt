package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.InstallationController
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.gst.tenancy.contract.model.InstallationFactsRequest
import com.dynokore.platform.outboxandqueue.EventLogReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private const val STALE_NEW_TRAIN = "STALE"

/**
 * The train hook of recordInstallationFacts reaches the NEW_TRAIN mark of every certified entity, each in the tenant of
 * its entity (INV-REQ-1135, ARC-21), through the real application context over PostgreSQL: the changed `trainVersion`
 * writes the mark request in the fact transaction and the wired consumer carries it out.
 */
class TrainHookTest {
    private class Env(
        val e: CertificationEnv,
        val env: ProfileEnv,
    ) {
        val api: InstallationController = env.context.getBean(InstallationController::class.java)

        init {
            BoundaryFixture.asSuperuser { c ->
                c.createStatement().use { it.execute("delete from tenancy.installation_fact") }
            }
        }

        fun facts(
            train: String,
            vararg window: String,
        ) = api.recordInstallationFacts(UUID.randomUUID(), InstallationFactsRequest(train, window.toList()))

        /** The mark requests written by [block], delivered to the consumer. */
        fun marks(block: () -> Unit): Int {
            val before = e.markRequestedIds()
            block()
            val ids = e.markRequestedIds() - before.toSet()
            ids.forEach { e.deliverMarkRequested(it) }
            if (ids.isNotEmpty()) e.poll(MARK_CONSUMER)
            return ids.size
        }
    }

    private fun run(block: (Env) -> Unit) {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                block(Env(CertificationEnv(env, log), env))
            }
    }

    /** Two certified entities in two tenants and one never certified; [block] gets them. */
    private fun withThree(
        t: Env,
        block: (EntityResponse, EntityResponse, EntityResponse) -> Unit,
    ) = t.env.withEntity { _, a ->
        t.e.passed(a)
        t.env.withEntity { _, b ->
            t.e.passed(b)
            t.env.withEntity { _, c -> block(a, b, c) }
        }
    }

    @Test
    fun `INV-AC-18066 a changed trainVersion marks the two certified entities stale and the uncertified one not`() {
        run { t ->
            withThree(t) { a, b, c ->
                assertThat(t.marks { t.facts("2026.10", "2026.10") }).isZero()
                t.marks { t.facts("2026.11", "2026.10", "2026.11") }
                assertThat(t.e.state(a)).isEqualTo(STALE_NEW_TRAIN to "NEW_TRAIN")
                assertThat(t.e.state(b)).isEqualTo(STALE_NEW_TRAIN to "NEW_TRAIN")
                assertThat(t.e.state(c)).isNull()
                assertThat(t.e.stored(a)).hasSize(1)
                assertThat(t.e.stored(b)).hasSize(1)
            }
        }
    }

    @Test
    fun `INV-AC-18066 the same trainVersion stored again makes zero marks`() {
        run { t ->
            withThree(t) { a, _, _ ->
                t.facts("2026.11", "2026.11")
                assertThat(t.marks { t.facts("2026.11", "2026.11") }).isZero()
                assertThat(t.e.state(a)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `INV-AC-18066 the first report on a new installation makes zero marks`() {
        run { t ->
            withThree(t) { a, _, _ ->
                assertThat(t.marks { t.facts("2026.10", "2026.10") }).isZero()
                assertThat(t.e.state(a)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `INV-AC-18066 a change of supportedTrainVersions alone makes zero marks`() {
        run { t ->
            withThree(t) { a, _, _ ->
                t.facts("2026.11", "2026.11")
                assertThat(t.marks { t.facts("2026.11", "2026.10", "2026.11") }).isZero()
                assertThat(t.e.state(a)).isEqualTo("CURRENT" to null)
            }
        }
    }

    @Test
    fun `INV-AC-18066 the look-alike member trainVersions in the plural is rejected or ignored with zero marks`() {
        run { t ->
            withThree(t) { a, _, _ ->
                t.facts("2026.10", "2026.10")
                val body = """{"trainVersions":"2026.11","supportedTrainVersions":["2026.11"]}"""
                val outcome =
                    runCatching {
                        val request = JsonMapper.builder().build().readValue(body, InstallationFactsRequest::class.java)
                        t.api.recordInstallationFacts(UUID.randomUUID(), request)
                    }
                assertThat(t.marks { }).isZero()
                assertThat(outcome.isFailure).isTrue()
                assertThat(t.e.state(a)).isEqualTo("CURRENT" to null)
                assertThat(t.marks { runCatching { t.facts("2026.10", "2026.10") } }).isZero()
            }
        }
    }
}
