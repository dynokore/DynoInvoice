package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadConsentRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityKey
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.ConsentKey
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.ScopeLevel
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.LocalDate

/** INV-T-2876: the replica feeds (INV-REQ-1200, INV-REQ-1201; INV-Q-3259, INV-Q-3850). */
class FeedHandlersTest {
    private fun node(
        version: Int,
        name: String,
    ) = """{"kind":"ENTITY","parentRef":"G-1","retired":false,"name":"$name","code":"E1",
        |"profile":[{"attribute":"filingFrequency","value":"QUARTERLY","effectiveDate":"2026-01-01"}],
        |"structureVersion":$version}
        """.trimMargin()

    private fun structureChanged(nodeJson: String?) =
        """{"nodeType":"ENTITY","nodeRef":"E-001","changeType":"UPDATED","parentRef":"G-1"""" +
            (nodeJson?.let { ""","node":$it}""" } ?: "}")

    private fun held(replica: StructureReplica) =
        (replica.node(TENANT, "E-001", Deadline.after(Duration.ofSeconds(1), CLOCK)) as Lookup.Local).value

    @Test
    fun `structure events replace the node whole and are ordered by the tenancy structure version`() {
        val replica = StructureReplica(CLOCK)
        val handler = StructureFeedHandler(replica)
        handler.handle(
            envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, structureChanged(node(5, "Acme"))),
        ) shouldBe
            HandleOutcome.APPLIED
        val profile =
            """{"entityRef":"E-001","changedAttributes":[{"attribute":"filingFrequency","newValue":"MONTHLY"}],
            |"effectiveDate":"2026-07-01","node":${node(4, "Older")}}
            """.trimMargin()
        handler.handle(envelope(ProductEventTypes.ENTITY_PROFILE_CHANGED, profile))
        replica.onSnapshotComplete(5)
        val node = held(replica)
        node.name shouldBe "Acme"
        node.kind shouldBe StructureNodeKind.ENTITY
        node.structureVersion shouldBe 5
        node.attribute("filingFrequency", LocalDate.parse("2026-10-12")) shouldBe "QUARTERLY"
    }

    @Test
    fun `a structure event without node is a ContractViolation and the replica keeps the node it holds (INV-Q-3850)`() {
        val replica = StructureReplica(CLOCK)
        val handler = StructureFeedHandler(replica)
        handler.handle(envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, structureChanged(node(3, "Acme"))))
        replica.onSnapshotComplete(3)
        assertThrows<ContractViolation> {
            handler.handle(envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, structureChanged(null)))
        }
        assertThrows<ContractViolation> {
            handler.handle(
                envelope(
                    ProductEventTypes.ENTITY_PROFILE_CHANGED,
                    """{"entityRef":"E-001","changedAttributes":[],"effectiveDate":"2026-07-01"}""",
                ),
            )
        }
        held(replica).name shouldBe "Acme"
    }

    @Test
    fun `a malformed structure payload is a ContractViolation`() {
        assertThrows<ContractViolation> {
            StructureFeedHandler(StructureReplica(CLOCK))
                .handle(envelope(ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED, """{"nodeRef":"E-001"}"""))
        }
    }

    private val grants =
        """{"changeKind":"GRANT","principalRef":"P-1","authorityVersion":7,
        |"grantRecord":{"entityRef":"E-001","grants":[{"action":"document.read","scopeLevel":"ENTITY",
        |"scopeRef":"E-001","validFrom":"2026-10-01T00:00:00Z","periodFrom":"2026-07-01","periodTo":"2026-09-30"}]}}
        """.trimMargin()

    @Test
    fun `AuthorityChanged feeds the grant set, the consent coverage and the principal record`() {
        val replica = AuthorityReplica(CLOCK)
        val handler = AuthorityFeedHandler(replica)
        val grantEvent = handler.decode(envelope(ProductEventTypes.AUTHORITY_CHANGED, grants))
        grantEvent.authorityVersion shouldBe 7
        val record = grantEvent.record.shouldBeInstanceOf<AuthorityRecord.GrantRecord>()
        record.grants.single().scopeLevel shouldBe ScopeLevel.ENTITY
        record.grants.single().periodTo shouldBe LocalDate.parse("2026-09-30")
        handler.handle(envelope(ProductEventTypes.AUTHORITY_CHANGED, grants))
        replica.grants.peek(AuthorityKey(TENANT, "P-1", "E-001")).shouldBeInstanceOf<Any>()

        val consent =
            """{"changeKind":"CONSENT","principalRef":"agent-1","authorityVersion":8,
            |"consentRecord":{"clientEntityRef":"E-001","state":"LIVE","scopeItems":["${firstScopeItem()}"],
            |"validFrom":"2026-10-01T00:00:00Z"}}
            """.trimMargin()
        handler.handle(envelope(ProductEventTypes.AUTHORITY_CHANGED, consent))
        replica.consents.peek(ConsentKey(TENANT, "E-001"))?.state shouldBe ConsentState.LIVE

        val revoked =
            """{"changeKind":"PRINCIPAL","principalRef":"P-1","authorityVersion":9,
            |"principalRecord":{"state":"REVOKED"}}
            """.trimMargin()
        handler
            .decode(envelope(ProductEventTypes.AUTHORITY_CHANGED, revoked))
            .record
            .shouldBeInstanceOf<AuthorityRecord.PrincipalRecord>()
    }

    private fun firstScopeItem(): String =
        AuthorityChangedPayloadConsentRecord.ScopeItems.entries
            .first()
            .value

    @Test
    fun `an AuthorityChanged without a record passes no record, one with two records is a ContractViolation`() {
        val handler = AuthorityFeedHandler(AuthorityReplica(CLOCK))
        val bare = """{"changeKind":"REVOCATION","principalRef":"P-1","authorityVersion":3}"""
        handler.decode(envelope(ProductEventTypes.AUTHORITY_CHANGED, bare)).record.shouldBeNull()
        val two =
            grants.dropLast(1) + ""","principalRecord":{"state":"REVOKED"}}"""
        assertThrows<ContractViolation> { handler.decode(envelope(ProductEventTypes.AUTHORITY_CHANGED, two)) }
    }
}
