package com.dynokore.gst.access.application.grant

import com.dynokore.gst.access.application.alert.AccessAlerts
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class ScanGrantExpiryNoticesTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")
    private val filing = EntityRef("ENT-1")
    private val holder = IdentityRef("IDN-HOLDER")
    private val admin = IdentityRef("IDN-ADMIN")
    private val d6 = Instant.parse("2026-12-06T02:00:00Z")
    private val d17 = Instant.parse("2026-12-17T02:00:00Z")
    private val d21 = Instant.parse("2026-12-21T02:00:00Z")
    private val ends20 = Instant.parse("2026-12-20T08:00:00Z")

    private fun grant(
        ref: String,
        until: Instant?,
        identity: IdentityRef = holder,
    ) = Grant.issue(
        GrantHeader(GrantRef(ref), tenant, identity),
        Role.TAX_APPROVER,
        Scope.Entity(filing),
        ValidityWindow(Instant.parse("2026-11-01T00:00:00Z"), until),
    )

    private fun expiring(
        grant: Grant,
        by: IdentityRef = admin,
        entity: EntityRef? = filing,
        external: Boolean = false,
    ) = ExpiringGrant(grant, by, entity, external)

    private class Fakes {
        val requests = mutableListOf<ExpiryNoticeRequest>()
        val noticed = mutableMapOf<String, MutableSet<Int>>()
        val superseded = mutableMapOf<String, MutableSet<Int>>()
        val looked = mutableListOf<String>()
        val unresolved = mutableListOf<String>()
        val raised = mutableSetOf<String>()
        val alerts = mutableListOf<Pair<String, String>>()
        var alertFails = false
        var addresses: Map<String, String> = mapOf("IDN-HOLDER" to "h@x.sg", "IDN-ADMIN" to "a@x.sg")
        var items: List<ExpiringGrant> = emptyList()

        fun scan(now: Instant) =
            ScanGrantExpiryNotices(
                { items },
                object : ExpiryNoticeLog {
                    override fun noticedDays(grant: GrantRef) = noticed[grant.value].orEmpty()

                    override fun supersededDays(grant: GrantRef) = superseded[grant.value].orEmpty()

                    override fun recordNoticed(
                        grant: GrantRef,
                        daysBefore: Int,
                    ) {
                        noticed.getOrPut(grant.value) { mutableSetOf() } += daysBefore
                    }

                    override fun recordSuperseded(
                        grant: GrantRef,
                        daysBefore: Int,
                    ) {
                        superseded.getOrPut(grant.value) { mutableSetOf() } += daysBefore
                    }
                },
                { requests += it },
                ExpiryRecipients(
                    {
                        looked += it.value
                        addresses[it.value]
                    },
                    { unresolved += it },
                ),
                ExpiryAlerting(
                    object : ExpiryAlertLog {
                        override fun wasRaised(grant: GrantRef) = grant.value in raised

                        override fun markRaised(grant: GrantRef) {
                            raised += grant.value
                        }
                    },
                    AccessAlerts { c, s ->
                        check(!alertFails) { "down" }
                        alerts += c to s
                    },
                ),
            ).scan(now)
    }

    @Test
    fun `INV-AC-23831 grant ending on the 20th gets requests for the 6th and the 17th with holder first`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G1", ends20))) }
        f.scan(d6)
        f.scan(d17)
        assertEquals(2, f.requests.size)
        f.requests.forEach {
            assertEquals(listOf("h@x.sg", "a@x.sg"), it.recipients)
            assertEquals("grant-expiry-notice", it.template)
            assertEquals(ExpiryNoticeChannel.EMAIL, it.channel)
            assertEquals(filing, it.entity)
        }
        assertEquals(setOf(14, 3), f.noticed["G1"])
    }

    @Test
    fun `INV-AC-23831 late grant gets only the decided notice and the rest are superseded`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G2", ends20))) }
        f.scan(d17)
        assertEquals(1, f.requests.size)
        assertEquals(setOf(3), f.noticed["G2"])
        assertEquals(setOf(14), f.superseded["G2"])
    }

    @Test
    fun `INV-AC-23831 second scan on the same day requests nothing`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G1", ends20))) }
        f.scan(d17)
        f.scan(d17)
        assertEquals(1, f.requests.size)
    }

    @Test
    fun `INV-AC-23831 open-ended grant gets nothing`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G3", null))) }
        f.scan(d17)
        assertTrue(f.requests.isEmpty() && f.noticed.isEmpty() && f.superseded.isEmpty())
    }

    @Test
    fun `INV-AC-23831 only the administrator resolved gives one address`() {
        val f =
            Fakes().apply {
                items = listOf(expiring(grant("G1", ends20)))
                addresses = mapOf("IDN-ADMIN" to "a@x.sg")
            }
        f.scan(d17)
        assertEquals(listOf(listOf("a@x.sg")), f.requests.map { it.recipients })
        assertEquals(listOf("G1"), f.unresolved)
    }

    @Test
    fun `INV-AC-23831 no recipient resolved gives no request and no noticed entry`() {
        val f =
            Fakes().apply {
                items = listOf(expiring(grant("G1", ends20)))
                addresses = emptyMap()
            }
        f.scan(d17)
        assertTrue(f.requests.isEmpty())
        assertTrue(f.noticed.isEmpty())
        assertEquals(listOf("G1", "G1"), f.unresolved)
    }

    @Test
    fun `INV-AC-23831 holder equal to the administrator gives one address`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G1", ends20, admin))) }
        f.scan(d17)
        assertEquals(listOf(listOf("a@x.sg")), f.requests.map { it.recipients })
        assertTrue(f.unresolved.isEmpty())
    }

    @Test
    fun `INV-AC-23831 reserved holder is never looked up`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G1", ends20, IdentityRef("invitation:abc")))) }
        f.scan(d17)
        assertEquals(listOf("IDN-ADMIN"), f.looked)
        assertEquals(listOf(listOf("a@x.sg")), f.requests.map { it.recipients })
        assertEquals(listOf("G1"), f.unresolved)
    }

    @Test
    fun `INV-AC-23831 telemetry receives the grant reference once per unresolved recipient`() {
        val f =
            Fakes().apply {
                items = listOf(expiring(grant("G1", ends20)))
                addresses = mapOf("IDN-ADMIN" to "a@x.sg")
            }
        f.scan(d17)
        assertEquals(listOf("G1"), f.unresolved)
        assertTrue(f.unresolved.none { it.contains("@") || it.contains("IDN-") })
    }

    @Test
    fun `INV-AC-23831 no filing entity gives no request no alert and no log`() {
        val f =
            Fakes().apply {
                items =
                    listOf(
                        expiring(grant("G1", ends20), entity = null),
                        expiring(grant("G4", ends20), entity = null, external = true),
                    )
            }
        f.scan(d17)
        f.scan(d21)
        assertTrue(f.requests.isEmpty() && f.alerts.isEmpty() && f.noticed.isEmpty() && f.superseded.isEmpty())
    }

    @Test
    fun `INV-AC-23831 expired external grant raises ACC-003 once under the filing entity`() {
        val f = Fakes().apply { items = listOf(expiring(grant("G5", ends20), external = true)) }
        f.scan(d21)
        f.scan(d21)
        assertEquals(listOf("ACC-003" to "entity:ENT-1"), f.alerts)
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `INV-AC-23831 expired internal grant raises no alert and an already marked one raises none`() {
        val f =
            Fakes().apply {
                items = listOf(expiring(grant("G6", ends20)), expiring(grant("G7", ends20), external = true))
                raised += "G7"
            }
        f.scan(d21)
        assertTrue(f.alerts.isEmpty())
    }

    @Test
    fun `INV-AC-23831 a throwing alert port leaves the grant unmarked`() {
        val f =
            Fakes().apply {
                items = listOf(expiring(grant("G5", ends20), external = true))
                alertFails = true
            }
        assertThrows(IllegalStateException::class.java) { f.scan(d21) }
        assertTrue(f.raised.isEmpty())
    }

    @Test
    fun `INV-AC-23831 request refuses a third address and an empty list`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExpiryNoticeRequest("grant-expiry-notice", listOf("a", "b", "c"), ExpiryNoticeChannel.EMAIL, filing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpiryNoticeRequest("grant-expiry-notice", listOf("a", "a"), ExpiryNoticeChannel.EMAIL, filing)
        }
        assertEquals("grant-expiry-notice", ExpiryNoticeRequest.TEMPLATE)
    }
}
