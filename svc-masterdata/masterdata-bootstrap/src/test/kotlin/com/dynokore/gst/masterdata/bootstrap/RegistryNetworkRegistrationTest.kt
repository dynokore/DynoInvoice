package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.contract.model.NetworkRegistration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

/**
 * INV-T-4946: the entity's own registration on the SG Peppol Directory and SMP is an onboarding checklist item
 * confirmed by lookup (INV-REQ-320). The directory is a contract stub for the entry that changes and carries the AP
 * reference. The refusal of Relay issuance and of network receipt is made by the services of Wave 4; this service
 * produces the item they read (coordinator rulings INV-Q-4876 and INV-Q-9653).
 */
class RegistryNetworkRegistrationTest {
    private fun ownId(entity: String) = "0195:U-$entity"

    private fun read(
        env: RegistryEnv,
        entity: String,
    ) = env.ce.inTenant { env.networkRegistration.getNetworkRegistration(entity).body!! }

    private fun confirm(
        env: RegistryEnv,
        entity: String,
    ) = env.ce.inTenant { env.networkRegistration.confirmNetworkRegistration(entity, UUID.randomUUID()).body!! }

    @Test
    fun `INV-AC-360 an entity whose own UEN is not found has the item outstanding and a miss keeps it so`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                assertThat(read(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                assertThat(stub.gets()).isZero()
                val after = confirm(env, entity)
                assertThat(after.state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                assertThat(after.apReference).isNull()
                assertThat(after.confirmedAt).isNull()
                assertThat(after.lastCheckedAt).isNotNull()
                assertThat(read(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
            }
        }

    @Test
    fun `INV-AC-360 INV-AC-1613 an entity with no identity triple has nothing to look up and stays outstanding`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = unique("ENT-NOTRIPLE")
                assertThat(confirm(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                assertThat(stub.gets()).isZero()
            }
        }

    @Test
    fun `INV-AC-1614 INV-AC-1613 the item stays outstanding until a lookup finds the entity`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                // a negative answer cached by a reachability lookup does not keep the entity outstanding
                assertThat(env.reach(entity, ownId(entity)).onNetwork).isFalse()
                assertThat(read(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                stub.found(ownId(entity), "{\"peppolId\":\"${ownId(entity)}\",\"apReference\":\"AP-7731\"}")
                val gets = stub.gets()
                val confirmed = confirm(env, entity)
                assertThat(stub.gets()).isEqualTo(gets + 1)
                assertThat(confirmed.state).isEqualTo(NetworkRegistration.State.CONFIRMED)
                assertThat(confirmed.apReference).isEqualTo("AP-7731")
                assertThat(confirmed.confirmedAt).isNotNull()
                assertThat(read(env, entity).apReference).isEqualTo("AP-7731")
                assertThat(stub.writes()).isZero()
            }
        }

    @Test
    fun `INV-AC-360 INV-AC-1614 a lookup that finds the entity confirms the item and writes nothing`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                assertThat(read(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                stub.found(ownId(entity), "{\"apReference\":\"AP-1\"}")
                assertThat(confirm(env, entity).apReference).isEqualTo("AP-1")
                confirm(env, entity)
                assertThat(stub.writes()).isZero()
            }
        }

    @Test
    fun `INV-AC-1614 a confirmation in an outage turns neither OUTSTANDING into CONFIRMED nor back`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val entity = env.entity()
                stub.down(true)
                val outage = confirm(env, entity)
                assertThat(outage.state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                assertThat(outage.degradationCode).isEqualTo("MDM-008")
                stub.down(false)
                stub.found(ownId(entity), "{\"apReference\":\"AP-2\"}")
                assertThat(confirm(env, entity).state).isEqualTo(NetworkRegistration.State.CONFIRMED)
                stub.down(true)
                val stillConfirmed = confirm(env, entity)
                assertThat(stillConfirmed.state).isEqualTo(NetworkRegistration.State.CONFIRMED)
                assertThat(stillConfirmed.apReference).isEqualTo("AP-2")
                assertThat(stillConfirmed.degradationCode).isEqualTo("MDM-008")
                // the entry leaves the directory: the next successful lookup says OUTSTANDING again
                stub.down(false)
                stub.notFound(ownId(entity))
                env.advance(Duration.ofMinutes(1))
                assertThat(confirm(env, entity).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
            }
        }

    @Test
    fun `INV-AC-360 the item is the entity's own and another entity's confirmation does not confirm it`() =
        RegistryStub().use { stub ->
            runRegistry(directoryUrl = stub.baseUrl) { env ->
                val confirmed = env.entity()
                val other = env.entity()
                stub.found(ownId(confirmed), "{\"apReference\":\"AP-3\"}")
                confirm(env, confirmed)
                assertThat(read(env, other).state).isEqualTo(NetworkRegistration.State.OUTSTANDING)
                assertThat(read(env, confirmed).state).isEqualTo(NetworkRegistration.State.CONFIRMED)
            }
        }
}
