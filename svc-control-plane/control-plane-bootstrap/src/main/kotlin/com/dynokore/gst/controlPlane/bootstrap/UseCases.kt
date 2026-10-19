package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.persistence.PackRepositoryJooq
import com.dynokore.gst.controlPlane.adapters.persistence.StagingEvidenceReaderJooq
import com.dynokore.gst.controlPlane.adapters.persistence.jooq.Controlplane
import com.dynokore.gst.controlPlane.application.pack.AuthorRegulatoryPack
import com.dynokore.gst.controlPlane.application.pack.GetPack
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.PackRepository
import com.dynokore.gst.controlPlane.application.pack.SignPack
import org.jooq.DSLContext
import org.jooq.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** The only place a use case is constructed; `application` stays annotation-free (ARC-03). */
@Configuration(proxyBeanMethods = false)
class UseCases {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** The generated jOOQ schema, which service-runtime classifies at start-up (TableClasses.verify, INV-Q-4815). */
    @Bean
    fun controlplaneSchema(): Schema = Controlplane.CONTROLPLANE

    @Bean
    fun packRepository(
        dsl: DSLContext,
        clock: Clock,
    ): PackRepository = PackRepositoryJooq(dsl, clock)

    @Bean
    fun stagingEvidenceReader(dsl: DSLContext) = StagingEvidenceReaderJooq(dsl)

    @Bean
    fun authorRegulatoryPack(
        packs: PackRepository,
        roles: OperatorRoles,
    ) = AuthorRegulatoryPack(packs, roles)

    @Bean
    fun signPack(
        packs: PackRepository,
        roles: OperatorRoles,
        clock: Clock,
    ) = SignPack(packs, roles, clock)

    @Bean
    fun getPack(packs: PackRepository) = GetPack(packs)
}
