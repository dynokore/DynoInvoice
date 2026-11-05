package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.registry.REGISTER_UNAVAILABLE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * INV-T-4946 at the service boundary, register half: the real context over real PostgreSQL, bound to the
 * certification-kit Check GST Register simulator. The clock moves, so "09:01 the next day" is an age (INV-REQ-477,
 * INV-REQ-801, INV-REQ-802, INV-REQ-1548).
 */
class RegistryRegisterTest {
    private val register = Simulators.register
    private val directory = Simulators.directory

    @Test
    fun `INV-AC-2314 a register result older than 24 hours is looked up again`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTERED")
            val cached = register.calls()
            env.advance(Duration.ofHours(23).plusMinutes(59))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached)
            env.advance(Duration.ofMinutes(2))
            env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached + 1)
        }

    @Test
    fun `INV-AC-2389 a not-found register result is looked up again after 1 hour`() =
        runRegistry { env ->
            val entity = env.entity()
            assertThat(env.lookup(entity, Simulators.UEN_NOT_REGISTERED).status).isEqualTo("NOT_REGISTERED")
            val cached = register.calls()
            env.advance(Duration.ofMinutes(59))
            env.lookup(entity, Simulators.UEN_NOT_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached)
            env.advance(Duration.ofMinutes(2))
            env.lookup(entity, Simulators.UEN_NOT_REGISTERED)
            assertThat(register.calls()).isEqualTo(cached + 1)
        }

    @Test
    fun `INV-AC-1855 INV-AC-342 both registries down gives off-network, REGISTER_UNAVAILABLE only`() =
        runRegistry { env ->
            val entity = env.entity()
            directory.outage(true)
            register.outage(true)
            val fact = env.reach(entity, Simulators.PEPPOL_FOUND)
            val answer = env.lookup(entity, Simulators.UEN_REGISTERED)
            assertThat(fact.onNetwork).isFalse()
            assertThat(fact.degradationCode).isEqualTo("MDM-008")
            assertThat(answer.status).isEqualTo(REGISTER_UNAVAILABLE)
            assertThat(env.records("DIRECTORY").map { it.degradation }).containsExactly("MDM-008")
        }

    @Test
    fun `INV-AC-1855 an unavailable register is never an error to the call and a later lookup recovers`() =
        runRegistry { env ->
            val entity = env.entity()
            register.outage(true)
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo(REGISTER_UNAVAILABLE)
            register.outage(false)
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTERED")
        }
}
