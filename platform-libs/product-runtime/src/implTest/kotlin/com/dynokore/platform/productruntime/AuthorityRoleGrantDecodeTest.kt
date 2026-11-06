package com.dynokore.platform.productruntime

import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.productruntime.Fixtures.envelope
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * INV-T-9233, INV-Q-8972 A: `AuthorityFeedHandler.decode` on events built from the samples of INV-T-9231
 * (copied from platform-contracts `service-runtime/examples`, bundle 0fc8d662 which holds them).
 */
class AuthorityRoleGrantDecodeTest {
    private val handler = AuthorityFeedHandler(AuthorityReplica(Fixtures.CLOCK, OperationRoleMap { emptyMap() }))
    private val type = PlatformEventCatalogue.AUTHORITY_CHANGED.eventType

    private fun sample(
        kind: String,
        name: String,
    ): String =
        checkNotNull(
            javaClass.getResource("/authority-samples/$kind/authority-changed-payload-role-grant-record-$name.json"),
        ) {
            "no sample $kind/$name"
        }.readText()

    private fun decode(json: String): AuthorityChanged = handler.decode(envelope(type, json))

    private fun expected(
        ref: String,
        state: RoleGrantState,
        grant: RoleGrant,
    ) = AuthorityChanged(Fixtures.TENANT, "PRN-8C41", 90413, AuthorityRecord.RoleGrantRecord(ref, state, grant))

    private val from = Instant.parse("2026-11-01T00:00:00Z")

    @Test
    fun `each of the seven valid samples decodes to the expected record`() {
        File(javaClass.getResource("/authority-samples/valid")!!.toURI()).listFiles()!!.size shouldBe 7
        decode(sample("valid", "tenant")) shouldBe
            expected(
                "GRT-0001",
                RoleGrantState.ACTIVE,
                RoleGrant("tenant.admin", GrantScope.TENANT, "TEN-7B1004", from),
            )
        decode(sample("valid", "removed")) shouldBe
            expected(
                "GRT-0001",
                RoleGrantState.REMOVED,
                RoleGrant("tenant.admin", GrantScope.TENANT, "TEN-7B1004", from),
            )
        decode(sample("valid", "installation")) shouldBe
            expected(
                "GRT-0002",
                RoleGrantState.ACTIVE,
                RoleGrant("platform.operator", GrantScope.INSTALLATION, null, from),
            )
        decode(sample("valid", "entity-period")) shouldBe
            AuthorityChanged(
                Fixtures.TENANT,
                "PRN-8C41",
                90413,
                AuthorityRecord.RoleGrantRecord(
                    "GRT-0004",
                    RoleGrantState.ACTIVE,
                    RoleGrant(
                        "external.auditor",
                        GrantScope.ENTITY_PERIOD,
                        "ENT-3F2A9C",
                        Instant.parse("2027-01-15T00:00:00Z"),
                        Instant.parse("2027-09-30T23:59:59Z"),
                        LocalDate.parse("2027-01-01"),
                        LocalDate.parse("2027-06-30"),
                    ),
                ),
            )
        for (name in listOf("group", "consent", "client-entity")) {
            val record = decode(sample("valid", name)).record.shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>()
            record.state shouldBe RoleGrantState.ACTIVE
            record.grant.scope shouldBe GrantScope.valueOf(name.uppercase().replace('-', '_'))
        }
    }

    @Test
    fun `the invalid samples the generated type refuses are a ContractViolation`() {
        for (name in listOf("without-grant-ref", "without-role", "without-scope-level", "period-as-name")) {
            assertThrows<ContractViolation>(name) { decode(sample("invalid", name)) }
        }
    }

    @Test
    fun `the invalid samples the generated type binds decode fail closed`() {
        // an out-of-vocabulary state or scope is the member UNKNOWN: a removal and a scope that matches nothing
        val lowerCase = decode(sample("invalid", "state-lower-case")).record
        lowerCase.shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>().state shouldBe RoleGrantState.UNKNOWN
        val lookAlike = decode(sample("invalid", "scope-level-look-alike")).record
        lookAlike.shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>().grant.scope shouldBe GrantScope.UNKNOWN
    }

    private fun tenantWith(
        field: String,
        length: Int,
    ): String {
        val base = sample("valid", "tenant")
        val old = Regex("\"$field\"\\s*:\\s*\"[^\"]*\"").find(base)!!.value
        return base.replace(old, "\"$field\":\"" + "x".repeat(length) + "\"")
    }

    @Test
    fun `the invalid sample grant-ref-65-characters is a ContractViolation`() {
        assertThrows<ContractViolation> { decode(sample("invalid", "grant-ref-65-characters")) }
    }

    @Test
    fun `lengths over the schema bounds are a ContractViolation and the bounds themselves decode`() {
        assertThrows<ContractViolation> { decode(tenantWith("grantRef", 65)) }
        assertThrows<ContractViolation> { decode(tenantWith("role", 101)) }
        assertThrows<ContractViolation> { decode(tenantWith("scopeRef", 65)) }
        for (field in listOf("grantRef" to 64, "role" to 100, "scopeRef" to 64)) {
            decode(tenantWith(field.first, field.second)).record.shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>()
        }
    }

    @Test
    fun `the length constants equal the maxLength values of the staged schema`() {
        val root = File(System.getProperty("productruntime.contracts"))
        val version = System.getProperty("productruntime.contractsVersion")
        val file =
            File(root, "platform-contracts/service-runtime/$version/schemas/authority-changed-payload.schema.json")
        val props =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
                .readTree(file)
                .get("properties")
                .get("roleGrantRecord")
                .get("properties")
        props.get("grantRef").get("maxLength").asInt() shouldBe AuthorityFeedHandler.MAX_GRANT_REF
        props.get("role").get("maxLength").asInt() shouldBe AuthorityFeedHandler.MAX_ROLE
        props.get("scopeRef").get("maxLength").asInt() shouldBe AuthorityFeedHandler.MAX_SCOPE_REF
    }

    @Test
    fun `scopeLevel TENANTS decodes to UNKNOWN which matches nothing`() {
        val json = sample("valid", "tenant").replace("\"TENANT\"", "\"TENANTS\"")
        decode(json)
            .record
            .shouldBeInstanceOf<AuthorityRecord.RoleGrantRecord>()
            .grant.scope shouldBe
            GrantScope.UNKNOWN
    }

    @Test
    fun `an event with grantRecord and roleGrantRecord is a ContractViolation`() {
        val both =
            sample("valid", "tenant").trimEnd().dropLast(1) +
                ""","grantRecord":{"entityRef":"E-001","grants":[]}}"""
        assertThrows<ContractViolation> { decode(both) }
    }

    @Test
    fun `an event with two role-bearing records is a ContractViolation`() {
        val two = sample("valid", "tenant").trimEnd().dropLast(1) + ""","principalRecord":{"state":"REVOKED"}}"""
        assertThrows<ContractViolation> { decode(two) }
    }

    @Test
    fun `a legacy grantRecord decodes to LegacyGrant`() {
        val legacy =
            """{"changeKind":"GRANT","principalRef":"P-1","authorityVersion":7,
            |"grantRecord":{"entityRef":"E-001","grants":[{"action":"document.read","scopeLevel":"ENTITY",
            |"scopeRef":"E-001","validFrom":"2026-10-01T00:00:00Z"}]}}
            """.trimMargin()
        decode(legacy).record shouldBe AuthorityRecord.LegacyGrant("E-001")
    }
}
