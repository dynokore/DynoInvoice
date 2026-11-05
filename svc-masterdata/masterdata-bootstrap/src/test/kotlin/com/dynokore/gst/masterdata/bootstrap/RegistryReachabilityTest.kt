package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.contract.model.ResolutionResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * INV-T-4946 at the service boundary, directory half: the real context over real PostgreSQL, bound to the
 * certification-kit Peppol directory simulator through the adapter-runtime executor. The clock moves, so "09:00" and
 * "08:59 the next day" are ages (INV-REQ-318, INV-REQ-319, INV-REQ-801, INV-REQ-802, INV-REQ-1299).
 */
class RegistryReachabilityTest {
    private val directory = Simulators.directory

    @Test
    fun `INV-AC-9499 the resolution returns the reachability fact and no submission type`() =
        runRegistry { env ->
            val entity = env.entity()
            val found = env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(directory.calls()).isEqualTo(1)
            val notFound = env.reach(entity, Simulators.PEPPOL_NOT_FOUND)
            assertThat(directory.calls()).isEqualTo(2)
            assertThat(found.onNetwork).isTrue()
            assertThat(found.degradationCode).isNull()
            assertThat(notFound.onNetwork).isFalse()
            assertThat(notFound.degradationCode).isNull()
            val result = env.ce.resolve(entity, "PEPPOL_PARTICIPANT_ID", Simulators.PEPPOL_FOUND)
            val body = JsonMapper.builder().build().writeValueAsString(result)
            assertThat(body).doesNotContain("submissionType").doesNotContain("Type 1A").doesNotContain("TYPE_2")
            assertThat(ResolutionResult::class.java.declaredFields.map { it.name.lowercase() })
                .noneMatch { it.contains("submission") || it.contains("routing") }
        }

    @Test
    fun `INV-AC-341 the resolution part is a not-reachable fact for a Peppol ID the directory does not hold`() =
        runRegistry { env ->
            val entity = env.entity()
            val fact = env.reach(entity, Simulators.PEPPOL_NOT_FOUND)
            assertThat(fact.onNetwork).isFalse()
            assertThat(fact.degradationCode).isNull()
        }

    @Test
    fun `INV-AC-1611 a second lookup inside the positive lifetime is served from the cache`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.reach(entity, Simulators.PEPPOL_FOUND).onNetwork).isTrue()
            val before = directory.calls()
            env.advance(Duration.ofHours(23))
            assertThat(env.reach(entity, Simulators.PEPPOL_FOUND).onNetwork).isTrue()
            assertThat(directory.calls()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-1612 a negative result is queried again after the negative lifetime`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.reach(entity, Simulators.PEPPOL_NOT_FOUND).onNetwork).isFalse()
            val before = directory.calls()
            env.advance(Duration.ofMinutes(61))
            assertThat(env.reach(entity, Simulators.PEPPOL_NOT_FOUND).onNetwork).isFalse()
            assertThat(directory.calls()).isEqualTo(before + 1)
        }

    @Test
    fun `INV-AC-2285 a cached directory result is refreshed after 24 hours`() =
        runRegistry { env ->
            val entity = env.entity()
            env.reach(entity, Simulators.PEPPOL_FOUND)
            val cached = directory.calls()
            env.advance(Duration.ofHours(23).plusMinutes(59))
            env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(directory.calls()).isEqualTo(cached)
            env.advance(Duration.ofMinutes(2))
            env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(directory.calls()).isEqualTo(cached + 1)
        }

    @Test
    fun `INV-AC-2033 a negative answer is cached for its lifetime`() =
        runRegistry { env ->
            val entity = env.entity()
            env.reach(entity, Simulators.PEPPOL_NOT_FOUND)
            val before = directory.calls()
            env.advance(Duration.ofMinutes(30))
            env.reach(entity, Simulators.PEPPOL_NOT_FOUND)
            assertThat(directory.calls()).isEqualTo(before)
        }

    @Test
    fun `INV-AC-1611 INV-AC-342 a cached result is served in an outage, an expired one routes off-network`() =
        RegistryStub().use { stub ->
            val a = "0195:A-FOUND"
            val b = "0195:B-NOT-FOUND"
            val c = "0195:C-FOUND"
            stub.found(a, "{\"peppolId\":\"$a\"}")
            stub.found(c, "{\"peppolId\":\"$c\"}")
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                env.reach(entity, c)
                env.advance(Duration.ofHours(2))
                env.reach(entity, a)
                env.advance(Duration.ofHours(22).plusMinutes(30))
                env.reach(entity, b)
                env.advance(Duration.ofMinutes(30))
                stub.down(true)
                val before = stub.gets()
                assertThat(env.reach(entity, a).onNetwork).isTrue()
                assertThat(env.reach(entity, b).onNetwork).isFalse()
                assertThat(stub.gets()).isEqualTo(before)
                val expired = env.reach(entity, c)
                assertThat(expired.onNetwork).isFalse()
                assertThat(expired.degradationCode).isEqualTo("MDM-008")
                assertThat(env.records("DIRECTORY").last().degradation).isEqualTo("MDM-008")
                assertThat(env.records("DIRECTORY").last().queried).isEqualTo(c)
            }
        }

    @Test
    fun `INV-AC-342 an outage with no cached result routes off-network with MDM-008 and does not block`() =
        runRegistry { env ->
            val entity = env.entity()
            directory.outage(true)
            val fact = env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(fact.onNetwork).isFalse()
            assertThat(fact.degradationCode).isEqualTo("MDM-008")
        }
}
