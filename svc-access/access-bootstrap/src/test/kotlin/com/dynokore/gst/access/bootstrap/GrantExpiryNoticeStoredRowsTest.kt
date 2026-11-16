package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.application.grant.ExpiryAlertLog
import com.dynokore.gst.access.application.grant.ExpiryNoticeLog
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** The tables of the expiry scan and its claim function (INV-T-15799; CS-19, ARC 4.3 step 1a). */
class GrantExpiryNoticeStoredRowsTest {
    private fun withRows(block: (ExpiryNoticeLog, ExpiryAlertLog, (TenantId, () -> Unit) -> Unit, GrantRef) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val tenants = context.getBean(TenantContext::class.java)
            val tx = context.getBean(TransactionRunner::class.java)
            val tenant = TenantId(UUID.randomUUID())
            val ref = Seeds.grant(context, tenant, SeedSpec("ext-x", Role.AUDITOR))
            block(
                context.getBean(ExpiryNoticeLog::class.java),
                context.getBean(ExpiryAlertLog::class.java),
                { t, body -> tenants.bind(t, "test") { tx.inTransaction { body() } } },
                GrantRef(ref),
            )
        }
    }

    @Test
    fun `a second insert for the same grant and day is refused by the constraint`() {
        withRows { notices, _, inTenant, grant ->
            val tenant = TenantId(UUID.fromString(grantTenant(grant)))
            inTenant(tenant) { notices.recordNoticed(grant, 14) }
            assertThatThrownBy { inTenant(tenant) { notices.recordNoticed(grant, 14) } }
                .isInstanceOf(Exception::class.java)
            assertThatThrownBy { inTenant(tenant) { notices.recordSuperseded(grant, 14) } }
                .isInstanceOf(Exception::class.java)
            inTenant(tenant) { notices.recordSuperseded(grant, 3) }
        }
    }

    @Test
    fun `a second alert record for the same grant is refused`() {
        withRows { _, alerts, inTenant, grant ->
            val tenant = TenantId(UUID.fromString(grantTenant(grant)))
            inTenant(tenant) { alerts.markRaised(grant) }
            assertThatThrownBy { inTenant(tenant) { alerts.markRaised(grant) } }.isInstanceOf(Exception::class.java)
        }
    }

    @Test
    fun `a read under another tenant returns no row`() {
        withRows { notices, alerts, inTenant, grant ->
            val tenant = TenantId(UUID.fromString(grantTenant(grant)))
            inTenant(tenant) {
                notices.recordNoticed(grant, 14)
                alerts.markRaised(grant)
            }
            inTenant(TenantId(UUID.randomUUID())) {
                assertThat(notices.noticedDays(grant)).isEmpty()
                assertThat(alerts.wasRaised(grant)).isFalse()
            }
            inTenant(tenant) {
                assertThat(notices.noticedDays(grant)).containsExactly(14)
                assertThat(alerts.wasRaised(grant)).isTrue()
            }
        }
    }

    @Test
    fun `the claim returns the tenants with an ACTIVE grant that has an end date, executable by access_runtime only`() {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            val timed = TenantId(UUID.randomUUID())
            val open = TenantId(UUID.randomUUID())
            val revoked = TenantId(UUID.randomUUID())
            Seeds.grant(context, timed, SeedSpec("u1", Role.AUDITOR))
            val openEnded: (java.time.Instant) -> ValidityWindow = { ValidityWindow(it.minusSeconds(60), null) }
            Seeds.grant(context, open, SeedSpec("u2", Role.AUDITOR, window = openEnded))
            Seeds.grant(context, revoked, SeedSpec("u3", Role.AUDITOR, status = GrantStatus.REVOKED))
            val claimed =
                BoundaryFixture.asRuntime { c ->
                    c.createStatement().use { s ->
                        s.executeQuery("SELECT access.claim_grant_expiry_tenants()").use { rows ->
                            generateSequence { if (rows.next()) rows.getObject(1, UUID::class.java) else null }.toSet()
                        }
                    }
                }
            assertThat(claimed).contains(timed.value).doesNotContain(open.value, revoked.value)
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "SELECT p.prosecdef, pg_get_userbyid(p.proowner)," +
                            " has_function_privilege('public', p.oid, 'EXECUTE')," +
                            " has_function_privilege('access_runtime', p.oid, 'EXECUTE') FROM pg_proc p" +
                            " WHERE p.proname = 'claim_grant_expiry_tenants'",
                    ).use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getBoolean(1)).isTrue()
                        assertThat(r.getString(2)).isEqualTo("access_sys")
                        assertThat(r.getBoolean(3)).isFalse()
                        assertThat(r.getBoolean(4)).isTrue()
                    }
            }
        }
    }

    private fun grantTenant(grant: GrantRef): String =
        BoundaryFixture.asSuperuser { c ->
            c.prepareStatement("SELECT tenant_id FROM access.role_grant WHERE grant_ref = ?").use {
                it.setString(1, grant.value)
                it.executeQuery().use { r ->
                    check(r.next())
                    r.getString(1)
                }
            }
        }
}
