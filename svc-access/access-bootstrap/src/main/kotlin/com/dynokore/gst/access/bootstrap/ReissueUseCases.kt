package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.invitation.FirstAdministratorEffects
import com.dynokore.gst.access.application.invitation.InvitationSettings
import com.dynokore.gst.access.application.invitation.ReissueFirstAdministratorInvitation
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the wiring of `reissueFirstAdministratorInvitation` (INV-Q-8232 A). */
@Configuration(proxyBeanMethods = false)
class ReissueUseCases {
    @Bean
    fun reissueFirstAdministratorInvitation(
        tx: TransactionRunner,
        effects: FirstAdministratorEffects,
        clock: Clock,
        properties: InvitationProperties,
    ) = ReissueFirstAdministratorInvitation(tx, effects, clock, InvitationSettings(properties.codeKeyVersion))
}
