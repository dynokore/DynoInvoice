package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.breakglass.BreakGlassPairConstraints
import com.dynokore.gst.access.adapters.breakglass.ValueConstraintsChain
import com.dynokore.gst.access.adapters.consent.AgentTenancyConstraints
import com.dynokore.gst.access.adapters.consent.VariationPointAgentTenancy
import com.dynokore.gst.access.adapters.landing.RolePrecedenceConstraints
import com.dynokore.gst.access.adapters.persistence.JooqLiveConsentProbe
import com.dynokore.gst.access.application.consent.AgentScope
import com.dynokore.gst.access.application.consent.AgentTenancy
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ScopeItemRequirements
import com.dynokore.gst.access.domain.role.RoleCatalogue
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.serviceruntime.binding.GstProperties
import org.jooq.DSLContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * TPL-18, continued: what an agent may do (INV-REQ-913) and agent tenancy (INV-REQ-2757): the scope item each
 * permission needs, the live check of it, VP-74 and the checks `setPolicy` asks of this service.
 */
@Configuration(proxyBeanMethods = false)
class AgentTenancyUseCases {
    /**
     * The scope item each permission of an agent needs, read from `agent-scope-items.tsv` (INV-Q-8874 A). A permission
     * the file does not list is refused to an agent. FILE_GST_RETURN has no row until the filing operations have
     * permission keys (INV-Q-8866).
     */
    @Bean
    @ConditionalOnMissingBean
    fun scopeItemRequirements(): ScopeItemRequirements = AgentScopeItemTable.production()

    @Bean
    fun agentScope(
        consents: ConsentRepository,
        requirements: ScopeItemRequirements,
    ) = AgentScope(consents, requirements)

    /** VP-74 at installation scope: on in SaaS and off in a private installation unless set (INV-REQ-2757). */
    @Bean
    @ConditionalOnMissingBean
    fun agentTenancy(
        reader: PolicyReader,
        clock: Clock,
        properties: GstProperties,
    ): AgentTenancy = VariationPointAgentTenancy(reader, clock, properties.form.name == "SAAS")

    /** The checks `setPolicy` asks of its owning service: VP-73 (INV-Q-3167), the pair of VP-08 and VP-74 (INV-REQ-2757
     * behaviour 4); no other variation point is checked. */
    @Bean
    fun valueConstraints(
        catalogue: RoleCatalogue,
        mapper: ObjectMapper,
        dsl: DSLContext,
    ): ValueConstraints =
        ValueConstraintsChain(
            listOf(
                RolePrecedenceConstraints(catalogue, mapper),
                BreakGlassPairConstraints(catalogue, mapper),
                AgentTenancyConstraints(JooqLiveConsentProbe(dsl)),
            ),
        )
}
