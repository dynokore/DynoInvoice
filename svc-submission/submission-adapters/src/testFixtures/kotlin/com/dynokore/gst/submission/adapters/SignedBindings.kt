package com.dynokore.gst.submission.adapters

import com.dynokore.platform.tenancykernel.BindingSignature
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Binds a plain connection the way a signing runner does: tenant, actor and `app.binding_sig` for the transaction. */
object SignedBindings {
    const val ACTOR = "svc:submission-svc"

    fun signed(
        tenant: UUID,
        actor: String = ACTOR,
    ): String =
        BindingSignature.sign(
            SubmissionDb.bindingKey,
            TenantId(tenant),
            actor,
            BindingSignature.expiryFor(Instant.now(), Duration.ofSeconds(SECONDS)),
        )

    fun bind(
        connection: Connection,
        tenant: UUID,
        actor: String = ACTOR,
        signature: String = signed(tenant, actor),
    ) {
        connection
            .prepareStatement(
                "select set_config('app.tenant_id', ?, true), set_config('app.actor', ?, true), " +
                    "set_config('app.binding_sig', ?, true)",
            ).use {
                it.setString(1, tenant.toString())
                it.setString(2, actor)
                it.setString(3, signature)
                it.execute()
            }
    }

    private const val SECONDS = 30L
}
