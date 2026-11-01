package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.accept
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.movableClock
import com.dynokore.gst.access.bootstrap.AcceptFirstAdministratorFixture.token
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.CONTROL_PLANE
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.FIRST
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.adminGrants
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.json
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.newTenant
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.provisionBody
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.snapshot
import com.dynokore.gst.access.bootstrap.FirstAdministratorFixture.withService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * INV-AC-8281 (INV-T-8282; INV-Q-15859 A, INV-Q-15861 A): the repeat of a reissue with the same Idempotency-Key and
 * the repeat of the original provisioning key after a reissue. The key and the fingerprint are stored on the
 * invitation row; no response body and no plaintext code is stored.
 */
class ReissueRepeatTest {
    private fun same(
        a: MockHttpServletResponse,
        b: MockHttpServletResponse,
    ) {
        assertThat(b.status).isEqualTo(a.status)
        val x = json(a)
        val y = json(b)
        listOf("grantRef", "status", "invitationRef", "invitationCode").forEach {
            assertThat(y[it].asString()).describedAs(it).isEqualTo(x[it].asString())
        }
    }

    @Test
    fun `INV-AC-8281 the repeat with the same key equals the first response and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            val first = served.reissue(tenant, key = key)
            assertThat(first.status).isEqualTo(200)
            val before = snapshot(tenant)
            val events = served.stored()
            val audit = auditEntries(tenant)
            // the clock moves on: the repeat is admitted whatever the expiry or the clock are
            served.movableClock().advance(Duration.ofDays(4000))
            same(first, served.reissue(tenant, key = key))
            assertThat(snapshot(tenant)).isEqualTo(before)
            assertThat(served.stored()).isEqualTo(events)
            assertThat(auditEntries(tenant)).isEqualTo(audit)
        }

    @Test
    fun `INV-AC-8281 no column of the four tables holds the returned code`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            val code = json(served.reissue(tenant, key = key))["invitationCode"].asString()
            assertThat(served.reissue(tenant, key = key).status).isEqualTo(200)
            assertThat(snapshot(tenant).values.flatten().joinToString("\n")).doesNotContain(code)
        }

    @Test
    fun `INV-AC-8281 the same key with another expiry is 422 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            assertThat(served.reissue(tenant, key = key).status).isEqualTo(200)
            val before = snapshot(tenant)
            val events = served.stored()
            val other = served.reissue(tenant, key = key, expires = "2032-02-03T04:05:06Z")
            assertThat(other.status).isEqualTo(422)
            assertThat(other.contentAsString).contains("SRT-009")
            assertThat(snapshot(tenant)).isEqualTo(before)
            assertThat(served.stored()).isEqualTo(events)
        }

    @Test
    fun `INV-AC-8281 the same instant written with another offset is a repeat`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            val first = served.reissue(tenant, key = key, expires = "2031-02-03T04:05:06Z")
            val before = snapshot(tenant)
            same(first, served.reissue(tenant, key = key, expires = "2031-02-03T12:05:06+08:00"))
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8281 the same key in another tenant is a new call`() =
        withService { served ->
            val a = newTenant()
            val b = newTenant()
            served.expired(a)
            served.expired(b)
            val key = UUID.randomUUID()
            val first = served.reissue(a, key = key)
            val second = served.reissue(b, key = key)
            assertThat(first.status).isEqualTo(200)
            assertThat(second.status).isEqualTo(200)
            assertThat(json(second)["grantRef"].asString()).isNotEqualTo(json(first)["grantRef"].asString())
            assertThat(json(second)["invitationRef"].asString()).isNotEqualTo(json(first)["invitationRef"].asString())
        }

    @Test
    fun `INV-AC-8281 a key equal to the provisioning key is a new reissue call judged by the state rule`() =
        withService { served ->
            val tenant = newTenant()
            val provisioningKey = UUID.randomUUID()
            val expiry = served.movableClock().freeze().plusSeconds(10)
            val body = FirstAdministratorFixture.body(tenant.toString(), expires = "$expiry")
            assertThat(served.provisionBody(FIRST, CONTROL_PLANE, body, provisioningKey).status).isEqualTo(201)
            val before = snapshot(tenant)
            assertThat(served.reissue(tenant, key = provisioningKey).status).isEqualTo(409)
            assertThat(snapshot(tenant)).isEqualTo(before)
            served.movableClock().advance(Duration.ofSeconds(11))
            assertThat(served.reissue(tenant, key = provisioningKey).status).isEqualTo(200)
        }

    @Test
    fun `INV-AC-8281 a key equal to the accept key is a new reissue call judged by the state rule`() =
        withService { served ->
            val tenant = newTenant()
            val (invitation, _) = served.provisionedExpiring(3600, tenant)
            val acceptKey = UUID.randomUUID()
            val accepted = served.accept(invitation.ref, invitation.code, token("invitee-1"), acceptKey)
            assertThat(accepted.status).isEqualTo(200)
            val before = snapshot(tenant)
            assertThat(served.reissue(tenant, key = acceptKey).status).isEqualTo(409)
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8281 the repeat after the invitation was accepted still equals the first response`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            val newExpiry =
                served
                    .movableClock()
                    .instant()
                    .plusSeconds(600)
                    .toString()
            val first = served.reissue(tenant, key = key, expires = newExpiry)
            val body = json(first)
            val accepted =
                served.accept(body["invitationRef"].asString(), body["invitationCode"].asString(), token("invitee-1"))
            assertThat(accepted.status).isEqualTo(200)
            val before = snapshot(tenant)
            same(first, served.reissue(tenant, key = key, expires = newExpiry))
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8281 a second reissue replaces the key, its repeat equals it and the first key is 409`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val k1 = UUID.randomUUID()
            val k2 = UUID.randomUUID()
            val e1 =
                served
                    .movableClock()
                    .instant()
                    .plusSeconds(60)
                    .toString()
            val first = served.reissue(tenant, key = k1, expires = e1)
            assertThat(first.status).isEqualTo(200)
            served.movableClock().advance(Duration.ofSeconds(120))
            val e2 =
                served
                    .movableClock()
                    .instant()
                    .plusSeconds(60)
                    .toString()
            val second = served.reissue(tenant, key = k2, expires = e2)
            assertThat(second.status).isEqualTo(200)
            assertThat(json(second)["invitationRef"].asString()).isNotEqualTo(json(first)["invitationRef"].asString())
            same(second, served.reissue(tenant, key = k2, expires = e2))
            // the first key is a new call now: the invitation is not expired, so 409 and nothing stored
            val before = snapshot(tenant)
            assertThat(served.reissue(tenant, key = k1, expires = e1).status).isEqualTo(409)
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8281 a repeat whose key version is unreadable is 503 SRT-011 and stores nothing`() {
        val v1 = TestSecrets.INVITATION_KEY
        val v2 = "test-invitation-code-key-v2-0123456789abcdef"
        val tenant = newTenant()
        val key = UUID.randomUUID()
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v1" to v1))) { served ->
            served.expired(tenant)
            assertThat(served.reissue(tenant, key = key).status).isEqualTo(200)
        }
        withService(mounted = FirstAdministratorFixture.mount(mapOf("v2" to v2)), version = "v2") { served ->
            val before = snapshot(tenant)
            val repeat = served.reissue(tenant, key = key)
            assertThat(repeat.status).isEqualTo(503)
            assertThat(repeat.contentAsString).contains("SRT-011")
            assertThat(snapshot(tenant)).isEqualTo(before)
        }
    }

    @Test
    fun `INV-AC-8281 two concurrent calls with the same key store one change and carry the same reference`() =
        withService { served ->
            val tenant = newTenant()
            served.expired(tenant)
            val key = UUID.randomUUID()
            val pool = Executors.newFixedThreadPool(2)
            try {
                val start = CountDownLatch(1)
                val calls =
                    List(2) {
                        pool.submit(
                            Callable {
                                start.await()
                                served.reissue(tenant, key = key)
                            },
                        )
                    }
                start.countDown()
                val responses = calls.map { it.get() }
                assertThat(responses.map { it.status }).containsOnly(200)
                assertThat(responses.map { json(it)["invitationRef"].asString() }.toSet()).hasSize(1)
            } finally {
                pool.shutdownNow()
            }
            assertThat(invitationRows(tenant).single()["version"].asLong()).isEqualTo(1L)
            assertThat(auditEntries(tenant)).hasSize(1)
        }

    // ---- the provisioning repeat after a reissue (INV-Q-15861 A) ----

    @Test
    fun `INV-AC-8281 the original provisioning call repeated after a reissue is 409 and stores nothing`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            val expiry = served.movableClock().freeze().plusSeconds(10)
            val body = FirstAdministratorFixture.body(tenant.toString(), expires = "$expiry")
            assertThat(served.provisionBody(FIRST, CONTROL_PLANE, body, key).status).isEqualTo(201)
            served.movableClock().advance(Duration.ofSeconds(11))
            assertThat(served.reissue(tenant).status).isEqualTo(200)
            val before = snapshot(tenant)
            val repeat = served.provisionBody(FIRST, CONTROL_PLANE, body, key)
            assertThat(repeat.status).isEqualTo(409)
            assertThat(repeat.contentAsString).doesNotContain("invitationCode").doesNotContain("invitationRef")
            assertThat(snapshot(tenant)).isEqualTo(before)
            // a different request with that key stays 422
            val other =
                FirstAdministratorFixture.body(tenant.toString(), email = "other@example.sg", expires = "$expiry")
            assertThat(served.provisionBody(FIRST, CONTROL_PLANE, other, key).status).isEqualTo(422)
            assertThat(snapshot(tenant)).isEqualTo(before)
        }

    @Test
    fun `INV-AC-8281 the provisioning repeat before a reissue and after an acceptance is as INV-T-8226 rules`() =
        withService { served ->
            val tenant = newTenant()
            val key = UUID.randomUUID()
            val body = FirstAdministratorFixture.body(tenant.toString())
            val first = served.provisionBody(FIRST, CONTROL_PLANE, body, key)
            assertThat(first.status).isEqualTo(201)
            same(first, served.provisionBody(FIRST, CONTROL_PLANE, body, key))
            val parsed = json(first)
            val accepted =
                served.accept(
                    parsed["invitationRef"].asString(),
                    parsed["invitationCode"].asString(),
                    token("invitee-1"),
                )
            assertThat(accepted.status).isEqualTo(200)
            same(first, served.provisionBody(FIRST, CONTROL_PLANE, body, key))
            assertThat(adminGrants(tenant)).hasSize(1)
        }
}
