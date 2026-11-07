package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.http.ApiRefusal
import com.dynokore.gst.tenancy.contract.model.CertificationResponse
import com.dynokore.gst.tenancy.contract.model.CertifySandboxRequest
import com.dynokore.gst.tenancy.contract.model.EntityResponse
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.serviceruntime.http.Problems
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

private typealias Req = CertifySandboxRequest
private typealias Out = CertifySandboxRequest.Outcome

/**
 * certifySandbox through the controller of the service (INV-T-16409): the status and the stored rows at the boundary.
 * The generated Jakarta annotations do not enforce the `not` clause, so the use case refuses (INV-Q-16403).
 */
class CertifySandboxHttpTest {
    private fun run(block: (CertificationEnv, ProfileEnv) -> Unit) {
        val log = TopicLog()
        BoundaryFixture
            .runner()
            .withBean(EventLogReader::class.java, { log })
            .run { context ->
                context.startupFailure?.let { throw it }
                val env = ProfileEnv(context)
                block(CertificationEnv(env, log), env)
            }
    }

    private fun post(
        env: ProfileEnv,
        entity: EntityResponse,
        request: Req,
    ): Int =
        try {
            env.api
                .certifySandbox(entity.entityRef, UUID.randomUUID(), request)
                .statusCode
                .value()
        } catch (refusal: ApiRefusal) {
            Problems.render(refusal.code, "corr-test", refusal.detail).status
        }

    private fun refused(
        request: Req,
        recorder: String = CERTIFIER,
        keys: Array<String> = arrayOf(RECORD_KEY),
    ) = run { e, env ->
        env.withEntity { _, entity ->
            e.actAs(recorder, entity, *keys)
            val before = e.rows(entity).size
            assertThat(post(env, entity, request)).isEqualTo(HttpStatus.BAD_REQUEST.value())
            assertThat(e.rows(entity).size).isEqualTo(before)
            assertThat(e.state(entity)).isNull()
        }
    }

    @Test
    fun `INV-AC-1148 certifySandbox answers 201 and the stored row equals the request`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                val scenarios = listOf("SC-1", "SC-2")
                val response =
                    env.api.certifySandbox(
                        entity.entityRef,
                        UUID.randomUUID(),
                        Req(Out.FAILED, "2026.2", scenarios),
                    )
                assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
                val body: CertificationResponse = response.body!!
                assertThat(body.entityRef).isEqualTo(entity.entityRef)
                assertThat(body.outcome).isEqualTo(CertificationResponse.Outcome.FAILED)
                assertThat(body.failedScenarios).isEqualTo(scenarios)
                val rows = e.rows(entity).map { e.payload(it) }
                assertThat(rows).hasSize(1)
                assertThat(rows[0]["outcome"].asString()).isEqualTo("FAILED")
                assertThat(rows[0]["suite_version"].asString()).isEqualTo("2026.2")
                assertThat(rows[0]["failed_scenarios"].values().map { it.asString() }).isEqualTo(scenarios)
            }
        }
    }

    @Test
    fun `probe http a caller with only entity-config manage is 403 and no row is stored`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CONFIG_ONLY, entity, "entity-config.manage")
                assertThat(post(env, entity, Req(Out.PASSED, "2026.1"))).isEqualTo(HttpStatus.FORBIDDEN.value())
                assertThat(e.rows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe http a caller with the key sandbox-certification record gets 201`() {
        run { e, env ->
            env.withEntity { _, entity ->
                e.actAs(CERTIFIER, entity, RECORD_KEY)
                assertThat(post(env, entity, Req(Out.PASSED, "2026.1"))).isEqualTo(HttpStatus.CREATED.value())
                assertThat(e.rows(entity)).hasSize(1)
            }
        }
    }

    @Test
    fun `probe http FAILED with failedScenarios absent is 400 and no row`() = refused(Req(Out.FAILED, "2026.1"))

    @Test
    fun `probe http FAILED with failedScenarios empty is 400 and no row`() =
        refused(Req(Out.FAILED, "2026.1", emptyList()))

    @Test
    fun `probe http PASSED with one failedScenarios item is 400 and no row`() =
        refused(Req(Out.PASSED, "2026.1", listOf("SC-1")))

    @Test
    fun `probe http PASSED with failedScenarios empty is 400 and no row`() =
        refused(Req(Out.PASSED, "2026.1", emptyList()))

    @Test
    fun `probe http suiteVersion absent is refused by the request reader and no row is stored`() {
        val mapper = JsonMapper.builder().build()
        assertThatThrownBy { mapper.readValue("{\"outcome\":\"PASSED\"}", Req::class.java) }
            .isInstanceOf(Exception::class.java)
        run { e, env ->
            env.withEntity { _, entity ->
                assertThat(e.rows(entity)).isEmpty()
            }
        }
    }

    @Test
    fun `probe http outcome of the look-alike value passed is refused by the request reader and no row is stored`() {
        val mapper = JsonMapper.builder().build()
        assertThatThrownBy { mapper.readValue("{\"outcome\":\"passed\",\"suiteVersion\":\"1\"}", Req::class.java) }
            .isInstanceOf(Exception::class.java)
        run { e, env ->
            env.withEntity { _, entity ->
                assertThat(e.rows(entity)).isEmpty()
            }
        }
    }
}
