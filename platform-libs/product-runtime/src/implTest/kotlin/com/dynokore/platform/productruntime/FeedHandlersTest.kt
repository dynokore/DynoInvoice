package com.dynokore.platform.productruntime

import com.dynokore.gst.productRuntimeAuthorityEvents.adapters.events.model.AuthorityChangedPayloadConsentRecord
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.productruntime.Fixtures.CLOCK
import com.dynokore.platform.productruntime.Fixtures.TENANT
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.productruntime.feed.OperatorSessionKeysFeedHandler
import com.dynokore.platform.productruntime.feed.StructureFeedHandler
import com.dynokore.platform.serviceruntime.identity.OperatorSession
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.ConsentKey
import com.dynokore.platform.serviceruntime.replica.ConsentState
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.Lookup
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.OperatorAccessState
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrantKey
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.replica.StructureEnvironment
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.serviceruntime.resilience.Deadline
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Duration
import java.time.LocalDate
import java.util.Base64

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

    private fun environmentNode(environment: String?) =
        node(5, "Acme").replace(
            """"structureVersion":5""",
            """"structureVersion":5""" + (environment?.let { ""","environment":"$it"""" } ?: ""),
        )

    private fun environmentOf(
        eventType: String,
        payload: String,
    ): StructureEnvironment? {
        val replica = StructureReplica(CLOCK)
        StructureFeedHandler(replica).handle(envelope(eventType, payload))
        replica.onSnapshotComplete(5)
        return held(replica).environment
    }

    private fun structureEnvironment(environment: String?) =
        environmentOf(
            ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
            structureChanged(environmentNode(environment)),
        )

    @Test
    fun `OrganisationStructureChanged SANDBOX gives SANDBOX`() {
        structureEnvironment("SANDBOX") shouldBe StructureEnvironment.SANDBOX
    }

    @Test
    fun `OrganisationStructureChanged PRODUCTION gives PRODUCTION`() {
        structureEnvironment("PRODUCTION") shouldBe StructureEnvironment.PRODUCTION
    }

    @Test
    fun `OrganisationStructureChanged without environment gives null`() {
        structureEnvironment(null).shouldBeNull()
    }

    @Test
    fun `OrganisationStructureChanged sandbox gives UNKNOWN`() {
        structureEnvironment("sandbox") shouldBe StructureEnvironment.UNKNOWN
    }

    @Test
    fun `OrganisationStructureChanged STAGING gives UNKNOWN`() {
        structureEnvironment("STAGING") shouldBe StructureEnvironment.UNKNOWN
    }

    @Test
    fun `EntityProfileChanged node gives null environment`() {
        val profile =
            """{"entityRef":"E-001","changedAttributes":[],"effectiveDate":"2026-07-01","node":${node(5, "Acme")}}"""
        environmentOf(ProductEventTypes.ENTITY_PROFILE_CHANGED, profile).shouldBeNull()
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
        """{"changeKind":"ROLE_GRANT","principalRef":"P-1","authorityVersion":7,
        |"roleGrantRecord":{"grantRef":"GRT-1","role":"external.auditor","state":"ACTIVE","scopeLevel":"ENTITY_PERIOD",
        |"scopeRef":"E-001","validFrom":"2026-10-01T00:00:00Z","periodFrom":"2026-07-01","periodTo":"2026-09-30"}}
        """.trimMargin()

    @Test
    fun `AuthorityChanged feeds the role grant, the consent coverage and the principal record`() {
        val replica = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })
        val handler = AuthorityFeedHandler(replica)
        val grantEvent = handler.decode(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, grants))
        grantEvent.authorityVersion shouldBe 7
        val record = grantEvent.record.shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>()
        record.state shouldBe RoleGrantState.ACTIVE
        record.grant.scope shouldBe GrantScope.ENTITY_PERIOD
        record.grant.periodTo shouldBe LocalDate.parse("2026-09-30")
        handler.handle(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, grants))
        replica.roleGrants.peek(RoleGrantKey(TENANT, "P-1", "GRT-1")) shouldBe record.grant

        val consent =
            """{"changeKind":"CONSENT","principalRef":"agent-1","authorityVersion":8,
            |"consentRecord":{"clientEntityRef":"E-001","state":"LIVE","scopeItems":["${firstScopeItem()}"],
            |"validFrom":"2026-10-01T00:00:00Z"}}
            """.trimMargin()
        handler.handle(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, consent))
        replica.consents.peek(ConsentKey(TENANT, "E-001"))?.state shouldBe ConsentState.LIVE

        val revoked =
            """{"changeKind":"PRINCIPAL","principalRef":"P-1","authorityVersion":9,
            |"principalRecord":{"state":"REVOKED"}}
            """.trimMargin()
        handler
            .decode(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, revoked))
            .record
            .shouldBeInstanceOf<AuthorityRecord.PrincipalRecord>()
    }

    private fun firstScopeItem(): String =
        AuthorityChangedPayloadConsentRecord.ScopeItems.entries
            .first()
            .value

    @Test
    fun `an AuthorityChanged without a record passes no record, one with two records is a ContractViolation`() {
        val handler = AuthorityFeedHandler(AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() }))
        val bare = """{"changeKind":"REVOCATION","principalRef":"P-1","authorityVersion":3}"""
        handler.decode(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, bare)).record.shouldBeNull()
        val two =
            grants.dropLast(1) + ""","principalRecord":{"state":"REVOKED"}}"""
        assertThrows<ContractViolation> {
            handler.decode(
                envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, two),
            )
        }
    }

    @Test
    fun `INV-AC-9359 the old access type is a ContractViolation, the platform type is decoded`() {
        val handler = AuthorityFeedHandler(AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() }))
        val bare = """{"changeKind":"REVOCATION","principalRef":"P-1","authorityVersion":3}"""
        assertThrows<ContractViolation> {
            handler.decode(envelope("com.dynokore.gst.access.AuthorityChanged.v1", bare))
        }
        handler.decode(envelope("com.dynokore.gst.platform.AuthorityChanged.v1", bare)).record.shouldBeNull()
    }

    private val keysType = PlatformEventCatalogue.OPERATOR_SESSION_KEYS_CHANGED.eventType
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val rsaMachine = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun keyItem(
        kid: String,
        use: String?,
        pair: java.security.KeyPair = rsa,
    ) = """{"kid":"$kid",${use?.let { """"use":"$it",""" } ?: ""}"alg":"RS256",""" +
        """"publicKey":"${Base64.getEncoder().encodeToString(pair.public.encoded)}",""" +
        """"validFrom":"2026-10-01T00:00:00Z","validUntil":"2026-11-15T00:00:00Z"}"""

    private fun keys(vararg items: String) = """{"keys":[${items.joinToString(",")}]}"""

    private fun newReplica() = AuthorityReplica(CLOCK, OperationRoleMap { emptyMap() })

    /** A replica that has finished its cold start, so a decision is local and not AUTHORITY_UNAVAILABLE. */
    private fun syncedReplica() = newReplica().also { it.onSnapshotComplete(0) }

    private fun verifies(
        replica: AuthorityReplica,
        kid: String,
    ): Boolean {
        val key = replica.operatorSessionKeys.valid(kid) ?: return false
        val publicKey =
            java.security.KeyFactory
                .getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey)))
        val signed = "header.claims".toByteArray()
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(rsa.private)
                update(signed)
                sign()
            }
        return Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(signed)
            verify(signature)
        }
    }

    @Test
    fun `INV-AC-16966 after the key event the replica holds the operator items and not the machine ones`() {
        val replica = newReplica()
        OperatorSessionKeysFeedHandler(replica)
            .handle(
                envelope(
                    keysType,
                    keys(keyItem("op1", "OPERATOR_SESSION"), keyItem("m1", "MACHINE_ACCESS", rsaMachine)),
                ),
            )
        (replica.operatorSessionKeys.valid("op1") != null) shouldBe true
        replica.operatorSessionKeys.valid("m1") shouldBe null
        (replica.machineAccessKeys.valid("m1") != null) shouldBe true
        replica.machineAccessKeys.valid("op1") shouldBe null
    }

    @Test
    fun `INV-AC-16966 a token signed by a published key verifies, an unpublished kid is refused`() {
        val replica = newReplica()
        OperatorSessionKeysFeedHandler(replica).handle(envelope(keysType, keys(keyItem("op1", null))))
        verifies(replica, "op1") shouldBe true
        verifies(replica, "other") shouldBe false
    }

    @Test
    fun `INV-AC-16966 before any key event an operator token is refused`() {
        verifies(newReplica(), "op1") shouldBe false
    }

    private val operatorRecord =
        """{"changeKind":"OPERATOR_ACCESS","principalRef":"op-1","authorityVersion":4,
        |"operatorAccessRecord":{"caseRef":"CASE-1","role":"platform.support","state":"ACTIVE",
        |"permissions":["doc.view.metadata"],"validUntil":"2026-10-12T02:15:00Z",
        |"elevation":{"elevationRef":"EL-1","validFrom":"2026-10-12T01:00:00Z","validUntil":"2026-10-12T03:00:00Z"}}}
        """.trimMargin()

    private fun askOperator(
        replica: AuthorityReplica,
        action: String,
    ) = replica.decide(
        AuthorizationRequest(
            TENANT,
            Principal("op-1", PrincipalKind.HUMAN),
            "E-001",
            action,
            operatorSession = OperatorSession("platform.support", "CASE-1"),
        ),
        Deadline.after(Duration.ofSeconds(1), CLOCK),
    )

    @Test
    fun `INV-AC-16966 the operator access envelope is held and a matching session request is GRANTED`() {
        val replica = syncedReplica()
        val handler = AuthorityFeedHandler(replica)
        handler.handle(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, operatorRecord))
        handler
            .decode(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, operatorRecord))
            .record
            .shouldBeInstanceOf<AuthorityRecord.OperatorAccessRecord>()
            .access.state shouldBe OperatorAccessState.ACTIVE
        askOperator(replica, "doc.view.metadata").reason shouldBe DecisionReason.GRANTED
        askOperator(replica, "doc.view.content").reason shouldBe DecisionReason.GRANTED
        askOperator(replica, "doc.other").reason shouldBe DecisionReason.NO_GRANT
    }

    @Test
    fun `INV-AC-16966 the access envelope with two records is a ContractViolation and changes nothing`() {
        val replica = syncedReplica()
        val two = operatorRecord.dropLast(1) + ""","principalRecord":{"state":"REVOKED"}}"""
        assertThrows<ContractViolation> {
            AuthorityFeedHandler(replica).handle(envelope(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, two))
        }
        askOperator(replica, "doc.view.metadata").reason shouldBe DecisionReason.NO_GRANT
    }

    @Test
    fun `INV-AC-16966 look-alike types, an unbindable payload and five key items are ContractViolations`() {
        val replica = newReplica()
        val handler = OperatorSessionKeysFeedHandler(replica)
        val one = keys(keyItem("op1", null))
        listOf(
            "com.dynokore.gst.access.OperatorSessionKeysChanged.v1",
            "com.dynokore.gst.platform.OperatorSessionKeyChanged.v1",
            "com.dynokore.gst.platform.OperatorSessionKeysChanged.v10",
        ).forEach { assertThrows<ContractViolation> { handler.handle(envelope(it, one)) } }
        assertThrows<ContractViolation> { handler.handle(envelope(keysType, """{"keys":"x"}""")) }
        assertThrows<ContractViolation> { handler.handle(envelope(keysType, "{}")) }
        assertThrows<ContractViolation> {
            handler.handle(envelope(keysType, keys(*Array(5) { keyItem("k$it", "OPERATOR_SESSION") })))
        }
        replica.operatorSessionKeys.valid("op1") shouldBe null
        replica.operatorSessionKeys.valid("k0") shouldBe null
    }

    @Test
    fun `INV-AC-16966 ReplicaFeeds holds six entries, AUTHORITY takes the two platform types`() {
        ReplicaFeeds.ALL.size shouldBe 6
        ReplicaFeeds.of(ReplicaKind.AUTHORITY).map { it.eventType } shouldBe
            listOf(
                "com.dynokore.gst.platform.AuthorityChanged.v1",
                "com.dynokore.gst.platform.OperatorSessionKeysChanged.v1",
            )
        ReplicaFeeds.forType(keysType)!!.fallbackService shouldBe null
    }
}
