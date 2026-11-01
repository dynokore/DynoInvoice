package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.provisioned
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import com.dynokore.gst.access.bootstrap.SealFixture.activated
import com.dynokore.gst.access.bootstrap.SealFixture.bytesOf
import com.dynokore.gst.access.bootstrap.SealFixture.capturedEvents
import com.dynokore.gst.access.bootstrap.SealFixture.digestOf
import com.dynokore.gst.access.bootstrap.SealFixture.everyStoredValue
import com.dynokore.gst.access.bootstrap.SealFixture.logged
import com.dynokore.gst.access.bootstrap.SealFixture.pair
import com.dynokore.gst.access.bootstrap.SealFixture.reseal
import com.dynokore.gst.access.bootstrap.SealFixture.signIn
import com.dynokore.gst.access.bootstrap.SealFixture.unlocked
import com.dynokore.gst.access.bootstrap.SealFixture.verifier
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * INV-AC-8899 (only a digest is stored, no plaintext is kept or logged) and INV-AC-8900 (a new credential is returned
 * once): an acceptance that created an account and then one `resealBreakGlass`, with the plaintext of each taken from
 * the two 200 bodies.
 */
class SealedAccountSecrecyTest {
    private val base64url = Regex("^[A-Za-z0-9_-]+$")

    /** The values that hold [secret] as a substring: the check every storage and log probe below uses. */
    private fun leaks(
        values: Collection<String>,
        secret: String,
    ): List<String> = values.filter { it.contains(secret) }

    @Test
    fun `INV-AC-8899 only the digest is stored and no table, event or log line holds the plaintext`() =
        withService { served ->
            val (outcome, lines) =
                logged {
                    val account = served.activated()
                    served.pair(account.tenant)
                    val (ref, _) = served.unlocked(account.tenant)
                    val first = checkNotNull(account.sealing)
                    val key = UUID.randomUUID()
                    val sealed = served.reseal(account.tenant, ref, key = key)
                    served.reseal(account.tenant, ref, key = key)
                    Triple(account, first, json(sealed)["sealingCredential"].asString())
                }
            val (account, first, second) = outcome
            val tenant = account.tenant
            // the format: at least 32 random bytes, base64url without padding
            listOf(first, second).forEach {
                assertThat(it).matches(base64url.pattern)
                assertThat(it).doesNotContain("=")
                assertThat(bytesOf(it)).isGreaterThanOrEqualTo(32)
            }
            assertThat(first).isNotEqualTo(second)
            val stored = everyStoredValue()
            val events = capturedEvents(served)
            listOf(first, second).forEach { secret ->
                assertThat(leaks(stored, secret)).describedAs("a table value holding the credential").isEmpty()
                assertThat(leaks(events, secret)).describedAs("an event holding the credential").isEmpty()
                assertThat(leaks(lines, secret)).describedAs("a log line holding the credential").isEmpty()
            }
            // the verifier is the lower-case hex SHA-256 digest of the current credential, by equality
            assertThat(verifier(tenant)).isEqualTo(digestOf(second)).matches("^[0-9a-f]{64}$")
            // the digest of the old credential is not stored any more, in any column of any table
            assertThat(verifier(tenant)).isNotEqualTo(digestOf(first))
            assertThat(leaks(stored, digestOf(first))).isEmpty()
            // a sign-in whose credential is the stored digest itself answers 403
            val (ref2, _) = served.unlocked(tenant)
            val digestSignIn = served.signIn(tenant, ref2, verifier(tenant)!!)
            assertThat(digestSignIn.status).isEqualTo(403)
            assertThat(served.signIn(tenant, ref2, second).status).isEqualTo(200)
            // probes: the check does see a plaintext column and a log line that holds the credential
            assertThat(leaks(stored + "{\"verifier\": \"$second\"}", second)).hasSize(1)
            assertThat(leaks(lines + "signed in with $first", first)).hasSize(1)
            assertThat(lines).describedAs("the capture saw the service logs").isNotEmpty()
        }

    @Test
    fun `INV-AC-8900 the acceptance and the reseal return the credential once and a repeat does not`() =
        withService { served ->
            val invitation = served.provisioned()
            val tenant = invitation.tenant
            // (a) the acceptance repeated with the same Idempotency-Key
            val key = UUID.randomUUID()
            val firstCall = served.accept(invitation.ref, invitation.code, token("invitee-r"), key)
            assertThat(firstCall.status).isEqualTo(200)
            assertThat(json(firstCall).has("sealingCredential")).isTrue()
            val credential = json(firstCall)["sealingCredential"].asString()
            val afterAcceptance = verifier(tenant)
            val repeatCall = served.accept(invitation.ref, invitation.code, token("invitee-r"), key)
            assertThat(repeatCall.status).isEqualTo(200)
            // absent: not null, not empty
            assertThat(repeatCall.contentAsString).doesNotContain("sealingCredential")
            assertThat(json(repeatCall).has("sealingCredential")).isFalse()
            assertThat(verifier(tenant)).isEqualTo(afterAcceptance)
            // the credential of the acceptance signs in on the first unlock
            served.pair(tenant)
            val (ref, _) = served.unlocked(tenant)
            assertThat(served.signIn(tenant, ref, credential).status).isEqualTo(200)
            // (b) reseal once and then again for the same unlock
            val beforeReseal = verifier(tenant)
            val resealKey = UUID.randomUUID()
            val first = served.reseal(tenant, ref, key = resealKey)
            assertThat(first.status).isEqualTo(200)
            val rotated = json(first)["sealingCredential"].asString()
            val afterReseal = verifier(tenant)
            assertThat(afterReseal).isNotEqualTo(beforeReseal).isEqualTo(digestOf(rotated))
            val again = served.reseal(tenant, ref)
            val sameKey = served.reseal(tenant, ref, key = resealKey)
            listOf(again, sameKey).forEach {
                assertThat(it.status).isEqualTo(200)
                assertThat(it.contentAsString).doesNotContain("sealingCredential")
                assertThat(json(it).has("sealingCredential")).isFalse()
                assertThat(json(it)["unlockRef"].asString()).isEqualTo(ref)
                assertThat(json(it)["status"].asString()).isEqualTo("SEALED")
                assertThat(verifier(tenant)).isEqualTo(afterReseal)
            }
            // the idempotency store keeps no credential
            val store = FirstAdministratorFixture.rows("access.idempotency_key", tenant)
            assertThat(leaks(store, rotated) + leaks(store, credential)).isEmpty()
            // after the reseal the old credential is refused on a new unlock and the new one signs in
            val (ref2, _) = served.unlocked(tenant)
            assertThat(served.signIn(tenant, ref2, credential).status).isEqualTo(403)
            assertThat(served.signIn(tenant, ref2, rotated).status).isEqualTo(200)
        }
}
