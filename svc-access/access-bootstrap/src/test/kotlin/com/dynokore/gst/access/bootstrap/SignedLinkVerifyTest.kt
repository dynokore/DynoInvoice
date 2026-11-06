package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessAlertOutbox
import com.dynokore.gst.access.adapters.http.ApiLinkInvalidated
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.adapters.http.SignedLinksController
import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.contract.model.VerifySignatoryLinkRequest
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * `verifySignatoryLink` at the service boundary (INV-T-4940; INV-REQ-899, INV-REQ-2758, INV-AC-2765): the real
 * application context over a real PostgreSQL, the controller called with no tenant bound (the tenant is found from the
 * link reference). Links are seeded by a superuser, as the issuing operations belong to the same task's later part.
 */
class SignedLinkVerifyTest {
    private val digests = Sha256PasscodeDigests()
    private val passcode = "482913"

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenant = TenantId(UUID.randomUUID())
        val api = context.getBean(SignedLinksController::class.java)

        fun seed(
            ref: String =
                "LNK-" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(22),
            client: String? = "CLI-A",
            expiresAt: Instant = Instant.now().plusSeconds(3600),
            state: Pair<String, String> = "ACTIVE" to passcode,
            owner: TenantId = tenant,
        ): String {
            val (status, code) = state
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "insert into access.signed_link (id, tenant_id, link_ref, purpose, client_ref, consent_ref, " +
                            "alert_entity, recipient_email, expires_at, passcode_digest, status, wrong_entries, " +
                            "invalidated_at, source, source_event_id, created_at, created_by, updated_at, " +
                            "updated_by, version) " +
                            "values (?, ?, ?, 'CONFIRM_FILING', ?, ?, ?, 'signatory@client.example', ?, ?, ?, 0, ?, " +
                            "?, ?, now(), 'test', now(), 'test', 0)",
                    ).use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, UUID.fromString(owner.toString()))
                        statement.setString(3, ref)
                        statement.setString(4, client)
                        statement.setString(5, client?.let { "CNS-1" })
                        statement.setString(6, client ?: EVENT_ENTITY)
                        statement.setTimestamp(7, Timestamp.from(expiresAt))
                        statement.setString(8, digests.digestOf(LinkRef(ref), code))
                        statement.setString(9, status)
                        statement.setTimestamp(10, if (status == "INVALIDATED") Timestamp.from(Instant.now()) else null)
                        statement.setString(11, if (client == null) "EVENT" else "CALL")
                        statement.setString(12, if (client == null) "evt-$ref" else null)
                        statement.executeUpdate()
                    }
            }
            return ref
        }

        fun enter(
            ref: String,
            entry: String,
        ) = api.verifySignatoryLink(ref, UUID.randomUUID(), VerifySignatoryLinkRequest(entry))

        fun row(ref: String): Map<String, Any?> =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "select status, wrong_entries, verified_at, invalidated_at from access.signed_link " +
                            "where link_ref = ?",
                    ).use { statement ->
                        statement.setString(1, ref)
                        statement.executeQuery().use { rows ->
                            check(rows.next()) { "no link $ref" }
                            mapOf(
                                "status" to rows.getString(1),
                                "wrong" to rows.getInt(2),
                                "verified" to rows.getTimestamp(3),
                                "invalidated" to rows.getTimestamp(4),
                            )
                        }
                    }
            }

        fun alerts(): List<String> =
            BoundaryFixture.asSuperuser { connection ->
                connection
                    .prepareStatement(
                        "select (data->>'accCode') || '|' || (data->>'scope') from access.outbox " +
                            "where tenant_id = ?::uuid and type = ?",
                    ).use { statement ->
                        statement.setString(1, tenant.toString())
                        statement.setString(2, AccessAlertOutbox.EVENT_TYPE)
                        statement.executeQuery().use { rows ->
                            buildList { while (rows.next()) add(rows.getString(1)) }
                        }
                    }
            }
    }

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner(catalogueMatrix = true, requirements = null).run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private fun wrong(passcode: String) = if (passcode == "000000") "000001" else "000000"

    @Test
    fun `INV-AC-2765 the fifth wrong passcode invalidates the link and raises ACC-004`() =
        run { env ->
            val ref = env.seed()
            (1..4).forEach { n ->
                val answer = env.enter(ref, wrong(passcode)).body!!
                assertThat(answer.verified).isFalse()
                assertThat(answer.wrongEntryCount).isEqualTo(n)
                assertThat(answer.invalidated).isFalse()
            }
            assertThat(env.alerts()).isEmpty()
            val fifth = env.enter(ref, wrong(passcode)).body!!
            assertThat(fifth.verified).isFalse()
            assertThat(fifth.wrongEntryCount).isEqualTo(5)
            assertThat(fifth.invalidated).isTrue()
            assertThat(env.row(ref)["status"]).isEqualTo("INVALIDATED")
            // the correct passcode is refused (410 ACC-004), now and later, and nothing changes
            repeat(2) {
                assertThatThrownBy { env.enter(ref, passcode) }.isInstanceOf(ApiLinkInvalidated::class.java)
            }
            assertThat(env.row(ref)["verified"]).isNull()
            assertThat(env.row(ref)["wrong"]).isEqualTo(5)
            // ACC-004 was raised once, to the sender, under the client entity of the link
            val alerts = env.alerts()
            assertThat(alerts).hasSize(1)
            assertThat(alerts.single()).isEqualTo("ACC-004|entity:CLI-A")
        }

    @Test
    fun `INV-AC-2765 a correct passcode within the limit opens the verified session and keeps the wrong count`() =
        run { env ->
            val ref = env.seed()
            repeat(4) { env.enter(ref, wrong(passcode)) }
            val ok = env.enter(ref, passcode).body!!
            assertThat(ok.verified).isTrue()
            assertThat(ok.wrongEntryCount).isEqualTo(4)
            assertThat(ok.invalidated).isFalse()
            val stored = env.row(ref)
            assertThat(stored["status"]).isEqualTo("ACTIVE")
            assertThat(stored["verified"]).isNotNull()
            assertThat(env.alerts()).isEmpty()
        }

    @Test
    fun `INV-AC-2765 an unknown, look-alike or expired link is one 401 and changes nothing`() =
        run { env ->
            val ref = env.seed()
            val expired = env.seed(expiresAt = Instant.now().minusSeconds(1))
            listOf(ref.lowercase(), "$ref ", ref.dropLast(1), "LNK-UNKNOWN", expired).forEach { reference ->
                assertThatThrownBy { env.enter(reference, passcode) }
                    .describedAs(reference)
                    .isInstanceOfSatisfying(ApiRefusal::class.java) {
                        assertThat(it.code).isEqualTo(ClosedErrorCode.UNAUTHENTICATED)
                    }
            }
            assertThat(env.row(expired)["wrong"]).isEqualTo(0)
            assertThat(env.row(expired)["verified"]).isNull()
            assertThat(env.row(ref)["verified"]).isNull()
        }

    @Test
    fun `INV-AC-2765 a revoked link is 410 and the digest is bound to its link`() =
        run { env ->
            val revoked = env.seed(state = "REVOKED" to passcode)
            assertThatThrownBy { env.enter(revoked, passcode) }.isInstanceOf(ApiLinkInvalidated::class.java)
            // the same six digits on two links give two digests: a link seeded with another passcode refuses this one
            val other = env.seed(state = "ACTIVE" to "111111")
            assertThat(env.enter(other, passcode).body!!.verified).isFalse()
            assertThat(env.enter(other, "111111").body!!.verified).isTrue()
        }

    @Test
    fun `INV-AC-2765 a link issued on the event files its ACC-004 under the envelope entity of the event`() =
        run { env ->
            val ref = env.seed(client = null)
            repeat(5) { env.enter(ref, wrong(passcode)) }
            assertThat(env.row(ref)["status"]).isEqualTo("INVALIDATED")
            assertThat(env.alerts()).containsExactly("ACC-004|entity:$EVENT_ENTITY")
        }

    @Test
    fun `INV-AC-2765 the link of another tenant is never touched by this tenant's alerts`() =
        run { env ->
            val other = TenantId(UUID.randomUUID())
            val mine = env.seed()
            val theirs = env.seed(owner = other)
            repeat(5) { env.enter(theirs, wrong(passcode)) }
            assertThat(env.row(mine)["wrong"]).isEqualTo(0)
            assertThat(env.alerts()).isEmpty()
        }

    private companion object {
        const val EVENT_ENTITY = "ENV-1"
    }
}
