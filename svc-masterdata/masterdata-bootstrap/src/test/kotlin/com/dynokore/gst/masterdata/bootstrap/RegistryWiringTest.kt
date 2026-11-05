package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.registry.HttpDirectoryClient
import com.dynokore.gst.masterdata.adapters.registry.HttpRegisterClient
import com.dynokore.gst.masterdata.application.registry.DirectoryClient
import com.dynokore.gst.masterdata.application.registry.RegisterClient
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.serviceruntime.events.EvidenceArtefactRecorder
import com.dynokore.platform.serviceruntime.http.PolicyCommandServlet
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.web.servlet.ServletRegistrationBean

/**
 * TPL-18 (P109): the registry lookups are composed the way the service starts, with the same imports as production: the
 * connections go through adapter-runtime when a base URL is configured, a connection with none degrades instead of
 * failing the start, and service-runtime mounts the shared `setPolicy` once the service holds a `PolicyWriter`.
 */
class RegistryWiringTest {
    @Test
    fun `the connections are adapter-runtime clients, the policy ports and the setPolicy servlet are mounted`() =
        runRegistry(
            customize = {
                // the token resolver is the resource-server configuration in production; the mount waits for it
                it.withBean(EndUserPrincipalResolver::class.java, { EndUserPrincipalResolver { null } })
            },
        ) { env ->
            val c = env.context
            assertThat(c.getBean(DirectoryClient::class.java)).isInstanceOf(HttpDirectoryClient::class.java)
            assertThat(c.getBean(RegisterClient::class.java)).isInstanceOf(HttpRegisterClient::class.java)
            assertThat(c.getBean(PolicyReader::class.java)).isNotNull
            assertThat(c.getBean(PolicyWriter::class.java)).isNotNull
            assertThat(c.getBean(EvidenceArtefactRecorder::class.java)).isNotNull
            val servlets = c.getBeansOfType(ServletRegistrationBean::class.java).values.map { it.servlet }
            assertThat(servlets).anyMatch { it is PolicyCommandServlet }
        }

    @Test
    fun `a connection with no base URL degrades off-network and unavailable and never fails the start`() =
        runRegistry(directoryUrl = "", registerUrl = "") { env ->
            val entity = env.entity()
            val fact = env.reach(entity, Simulators.PEPPOL_FOUND)
            assertThat(fact.onNetwork).isFalse()
            assertThat(fact.degradationCode).isEqualTo("MDM-008")
            assertThat(env.lookup(entity, Simulators.UEN_REGISTERED).status).isEqualTo("REGISTER_UNAVAILABLE")
        }
}
