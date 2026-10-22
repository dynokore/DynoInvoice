package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.IssuedCredential
import com.dynokore.gst.access.contract.model.CredentialInfo
import com.dynokore.gst.access.contract.model.PrincipalResponse
import com.dynokore.gst.access.domain.principal.Credential
import com.dynokore.gst.access.domain.principal.ServicePrincipal
import java.time.ZoneOffset

/**
 * The response of a principal operation. [credential] is the one just issued (with its once-shown client secret) or,
 * for withdrawCredential, the credential concerned; [previous] is the one a rotation ends.
 */
internal fun principalResponse(
    principal: ServicePrincipal,
    issued: IssuedCredential? = null,
    previous: Credential? = null,
    credential: Credential? = null,
) = PrincipalResponse(
    principalRef = principal.ref,
    principalType = principal.type.name,
    status = PrincipalResponse.Status.valueOf(principal.status.name),
    callingSystem = principal.callingSystem,
    credential =
        issued?.let { credentialInfo(it.credential, it.clientSecret) } ?: credential?.let { credentialInfo(it, null) },
    previousCredential = previous?.let { credentialInfo(it, null) },
)

private fun credentialInfo(
    credential: Credential,
    clientSecret: String?,
) = CredentialInfo(
    credentialRef = credential.ref,
    method = CredentialInfo.Method.valueOf(credential.method.name),
    status = CredentialInfo.Status.valueOf(credential.status.name),
    validUntil = credential.validUntil?.atOffset(ZoneOffset.UTC),
    clientSecret = clientSecret,
)
