package com.dynokore.gst.access.application.token

import com.dynokore.gst.access.application.operatorsession.OperatorKeyPublication
import com.dynokore.gst.access.domain.revocation.KeyUse
import com.dynokore.gst.access.domain.revocation.VerificationKey

/**
 * INV-Q-9028 A: a machine signing key is published as a `MACHINE_ACCESS` key through the one publication path that
 * also carries the operator-session keys, so the row and the OperatorSessionKeysChanged record that holds it are
 * committed together, before the first token the key signs. A kid already stored under another use is refused.
 */
class PublishingVerificationKeyRegistry(
    private val publication: OperatorKeyPublication,
) : VerificationKeyRegistry {
    override fun register(candidate: VerificationKey): VerificationKey =
        publication.publish(listOf(candidate.copy(use = KeyUse.MACHINE_ACCESS))).single()
}
