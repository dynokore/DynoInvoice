package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.link.Sha256PasscodeDigests
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * INV-AC-2224 (INV-Q-9222 A): a verified link principal sets VP-04 at CLIENT_ENTITY through the mounted servlet and
 * the stored value is read back; the look-alike principal is refused with SRT-004 and stores nothing. The context is
 * the service as composed in production over the real database.
 */
class LinkSignatorySetPolicyTest {
    private fun ApprovalEnv.link(ref: String) {
        env(this, ref)
    }

    private fun env(
        env: ApprovalEnv,
        ref: String,
    ) {
        env.consents.grant(EntityRef("CLI-1"))
        BoundaryFixture.asSuperuser { c ->
            val consent =
                c
                    .prepareStatement("select consent_ref from access.consent where tenant_id = ?")
                    .use { s ->
                        s.setObject(1, UUID.fromString(env.tenant.toString()))
                        s.executeQuery().use {
                            check(it.next())
                            it.getString(1)
                        }
                    }
            c
                .prepareStatement(
                    "insert into access.signed_link (id, tenant_id, link_ref, purpose, client_ref, consent_ref, " +
                        "alert_entity, recipient_email, expires_at, passcode_digest, status, wrong_entries, " +
                        "verified_at, invalidated_at, source, source_event_id, created_at, created_by, " +
                        "updated_at, updated_by, version) values (?, ?, ?, 'CONFIRM_FILING', 'CLI-1', ?, 'CLI-1', " +
                        "'signatory@client.example', ?, ?, 'ACTIVE', 0, ?, null, 'CALL', null, now(), " +
                        "'test', now(), 'test', 0)",
                ).use { s ->
                    s.setObject(1, UUID.randomUUID())
                    s.setObject(2, UUID.fromString(env.tenant.toString()))
                    s.setString(3, ref)
                    s.setString(4, consent)
                    s.setTimestamp(5, Timestamp.from(Instant.now().plusSeconds(3600)))
                    s.setString(6, Sha256PasscodeDigests().digestOf(LinkRef(ref), "482913"))
                    s.setTimestamp(7, Timestamp.from(Instant.now().minusSeconds(5)))
                    s.executeUpdate()
                }
        }
    }

    private fun stored(
        env: ApprovalEnv,
        vp: String,
    ): List<String> =
        env.tenants.bind(env.tenant, "reader") {
            env.context.getBean(TransactionRunner::class.java).inTransaction {
                runCatching {
                    env.context
                        .getBean(PolicyReader::class.java)
                        .resolve(
                            VariationPointKey(vp),
                            PolicyScope.entity(env.tenant, "CLI-1", clientEntity = "CLI-1"),
                            BusinessDate.today(env.clock),
                        ).selected
                        .map { it.key }
                }.getOrDefault(emptyList())
            }
        }

    @Test
    fun `INV-AC-2224 a verified link principal sets VP-10 and VP-04 at CLIENT_ENTITY, its look-alike stores nothing`() =
        approvalRun { env ->
            env.link("LNK-771A")
            listOf("VP-10" to "SUBMIT_INVOICE_DATA", "VP-04" to "CLIENT_APPROVER_ALONE").forEach { (vp, option) ->
                val before = stored(env, vp)
                val denied = env.set(vp, "CLI-1", option, actor = "link:LNK-771A ", level = "CLIENT_ENTITY")
                assertThat(denied.status).describedAs(denied.contentAsString).isEqualTo(403)
                assertThat(denied.contentAsString).contains("POL-004")
                assertThat(stored(env, vp)).isEqualTo(before)
                val ok = env.set(vp, "CLI-1", option, actor = "link:LNK-771A", level = "CLIENT_ENTITY")
                assertThat(ok.status).describedAs(ok.contentAsString).isEqualTo(200)
                assertThat(stored(env, vp)).containsExactly(option)
            }
        }
}
