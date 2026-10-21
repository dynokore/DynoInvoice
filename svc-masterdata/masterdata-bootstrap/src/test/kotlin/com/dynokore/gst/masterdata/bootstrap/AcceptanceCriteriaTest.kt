package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.masterdata.adapters.http.IdentityTriplesController
import com.dynokore.gst.masterdata.adapters.http.MasterdataRefusal
import com.dynokore.gst.masterdata.adapters.http.ResolutionController
import com.dynokore.gst.masterdata.application.identity.ConfirmCommand
import com.dynokore.gst.masterdata.application.identity.ConfirmIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ConfirmOutcome
import com.dynokore.gst.masterdata.application.identity.ProposeCommand
import com.dynokore.gst.masterdata.application.identity.ProposeIdentityTripleChange
import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.contract.model.ConfigureIdentityTripleRequest
import com.dynokore.gst.masterdata.contract.model.PendingIdentityTripleChange
import com.dynokore.gst.masterdata.contract.model.SecondConfirmation
import com.dynokore.gst.masterdata.domain.identity.ChangeState
import com.dynokore.gst.masterdata.domain.identity.ConfirmationRefusal
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.gst.masterdata.domain.identity.SubmittingIdentity
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.util.UUID

/**
 * The acceptance criteria of INV-T-4942 at the service boundary: the real application context over a real PostgreSQL
 * with forced row-level security and the signed binding, the use cases called under a bound tenant. Test names
 * carry the criterion key (acKeyCoverage). The constitution codes, Peppol IDs, UENs and GSTNs are test data: the
 * published contract carries the constitution as a free string and publishes no value list. See the merge request,
 * "Gaps", for the parts that wait for a ruling (the wire form of MDM-005, a pending change, the reprocessing request).
 */
class AcceptanceCriteriaTest {
    private val tenantA = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val tenantB = TenantId(UUID.fromString("0b000000-0000-4000-8000-00000000000b"))
    private val admin = "IDN-ADMIN"
    private val otherAdmin = "IDN-ADMIN-2"
    private val advisor = "IDN-ADVISOR"
    private val outsider = "IDN-OUTSIDER"
    private val nobody = "IDN-NOBODY"
    private val sole = "IDN-SOLE"

    /** The roles each authenticated subject holds, answered through the real PolicyRoleResolver wiring. */
    private val roles = TestRoles.roles

    private val companyViaSoleProp = Constitution("COMPANY_VIA_SOLE_PROPRIETORSHIP")
    private val soleProprietor = Constitution("SOLE_PROPRIETOR")
    private val agent = Constitution("SECTION_33_2_AGENT")
    private val groupMember = Constitution("GST_GROUP_MEMBER")
    private val longAgo = LocalDate.of(2026, 1, 1)

    private fun run(block: (Env) -> Unit) {
        BoundaryFixture.runner().run { context ->
            context.startupFailure?.let { throw it }
            block(Env(context))
        }
    }

    private inner class Env(
        val context: AssertableWebApplicationContext,
    ) {
        val tenants = context.getBean(TenantContext::class.java)
        val resolve = context.getBean(ResolveIdentityTriple::class.java)
        val propose = context.getBean(ProposeIdentityTripleChange::class.java)
        val confirm = context.getBean(ConfirmIdentityTripleChange::class.java)
        val identityApi = context.getBean(IdentityTriplesController::class.java)
        val resolutionApi = context.getBean(ResolutionController::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            actor: String = admin,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        /** Proposes through the use case as [proposer]; nothing is put in force. */
        fun proposed(
            tenant: TenantId,
            entity: String,
            constitution: Constitution,
            triple: IdentityTriple,
            from: LocalDate = longAgo,
        ): PendingChange =
            inTenant(tenant, proposer) {
                propose.handle(ProposeCommand(EntityRef(entity), constitution, triple, from, PersonRef(proposer)))
            }

        /** The subject that proposes; [admin] unless a test says otherwise. */
        var proposer: String = admin

        /** Confirms through the controller as the authenticated subject [actor]: the HTTP status or the refusal. */
        fun confirmedAs(
            tenant: TenantId,
            actor: String,
            entity: String,
            changeRef: String,
        ): Any =
            inTenant(tenant, actor) {
                try {
                    identityApi.confirmIdentityTripleChange(entity, changeRef, UUID.randomUUID()).statusCode
                } catch (refusal: ApiRefusal) {
                    refusal.code
                }
            }

        /** Proposes as [admin] and confirms as [otherAdmin] (a `tenant.admin`): the triple is in force from [from]. */
        fun configured(
            tenant: TenantId,
            entity: String,
            constitution: Constitution,
            triple: IdentityTriple,
            from: LocalDate = longAgo,
        ) {
            val change = proposed(tenant, entity, constitution, triple, from)
            assertThat(confirmedAs(tenant, otherAdmin, entity, change.changeRef)).isEqualTo(HttpStatus.OK)
        }

        fun resolved(
            tenant: TenantId,
            entity: String,
            constitution: Constitution,
            date: LocalDate = LocalDate.of(2026, 6, 1),
            submitter: SubmittingIdentity? = null,
        ): Resolution =
            inTenant(tenant) { resolve.handle(ResolveCommand(EntityRef(entity), constitution, date, submitter)) }

        fun triple(
            tenant: TenantId,
            entity: String,
            constitution: Constitution,
            date: LocalDate = LocalDate.of(2026, 6, 1),
        ): IdentityTriple = (resolved(tenant, entity, constitution, date) as Resolution.Resolved).version.triple

        /** The rows of [table] about [entity], counted as the superuser (every tenant). */
        fun rows(
            table: String,
            entity: String,
        ): Int =
            BoundaryFixture.asSuperuser { c ->
                c
                    .createStatement()
                    .executeQuery(
                        "select count(*) from masterdata.$table where entity_ref = '$entity'",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
            }
    }

    private fun triple(
        sender: String,
        supplier: String,
        gstn: String,
    ) = IdentityTriple(sender, supplier, gstn)

    @Test
    fun `INV-AC-1599 a company issuing through a sole-proprietorship it owns carries the company's UEN`() =
        run { env ->
            val company = triple("0195:U-C", "U-C", "G-C")
            env.configured(tenantA, "ENT-C", companyViaSoleProp, company)
            assertThat(env.triple(tenantA, "ENT-C", companyViaSoleProp)).isEqualTo(company)
        }

    @Test
    fun `INV-AC-1600 a sole proprietor with no active sole-proprietorship falls back to the GSTN`() =
        run { env ->
            env.configured(tenantA, "ENT-S", soleProprietor, triple("0195:G-S", "G-S", "G-S"))
            val resolved = env.triple(tenantA, "ENT-S", soleProprietor)
            assertThat(resolved).isEqualTo(IdentityTriple("0195:G-S", "G-S", "G-S"))
        }

    @Test
    fun `INV-AC-1601 a section 33(2) agent carries the local agent's UEN and GSTN`() =
        run { env ->
            env.configured(tenantA, "ENT-A", agent, triple("0195:U-A", "U-A", "G-A"))
            assertThat(env.triple(tenantA, "ENT-A", agent)).isEqualTo(IdentityTriple("0195:U-A", "U-A", "G-A"))
        }

    @Test
    fun `INV-AC-1599 INV-AC-1600 INV-AC-1601 each entity gets its own values, swapped constitution differs`() =
        run { env ->
            env.configured(tenantA, "ENT-C2", companyViaSoleProp, triple("0195:U-C", "U-C", "G-C"))
            env.configured(tenantA, "ENT-S2", soleProprietor, triple("0195:G-S", "G-S", "G-S"))
            env.configured(tenantA, "ENT-A2", agent, triple("0195:U-A", "U-A", "G-A"))
            val values = listOf("ENT-C2" to companyViaSoleProp, "ENT-S2" to soleProprietor, "ENT-A2" to agent)
            val triples = values.map { (entity, constitution) -> env.triple(tenantA, entity, constitution) }
            assertThat(triples.map { listOf(it.senderId, it.supplierId, it.supplierGstn) }).containsExactly(
                listOf("0195:U-C", "U-C", "G-C"),
                listOf("0195:G-S", "G-S", "G-S"),
                listOf("0195:U-A", "U-A", "G-A"),
            )
            assertThat(triples.toSet()).hasSize(3)
            // probe: the sole proprietor under another constitution is a different (here: absent) configuration
            assertThat(env.resolved(tenantA, "ENT-S2", agent)).isInstanceOf(Resolution.Blocked::class.java)
        }

    @Test
    fun `INV-AC-1602 an entity with no triple for its constitution is blocked with MDM-005`() =
        run { env ->
            env.configured(tenantA, "ENT-OK", soleProprietor, triple("0195:G-S", "G-S", "G-S"))
            val blocked = env.resolved(tenantA, "ENT-NONE", agent)
            assertThat(blocked).isInstanceOf(Resolution.Blocked::class.java)
            assertThat((blocked as Resolution.Blocked).code).isEqualTo("MDM-005")
            assertThat(contractErrorCodes("resolveCounterparty")).contains(blocked.code)
            // over the controller: 422 with the code text of the published contract, rendered by service-runtime
            val refusal =
                runCatching {
                    env.inTenant(tenantA) {
                        env.resolutionApi.resolveCounterparty(
                            "ENT-NONE",
                            agent.code,
                            LocalDate.of(2026, 6, 1),
                            null,
                            null,
                        )
                    }
                }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(MasterdataRefusal::class.java)
            val response = ApiRefusalAdvice().refuse(refusal as MasterdataRefusal)
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.body).contains("\"code\":\"${blocked.code}\"")
            // the second entity of the tenant resolves with its triple and is not blocked
            val ok =
                env.inTenant(tenantA) {
                    env.resolutionApi.resolveCounterparty(
                        "ENT-OK",
                        soleProprietor.code,
                        LocalDate.of(2026, 6, 1),
                        null,
                        null,
                    )
                }
            assertThat(ok.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(env.resolved(tenantA, "ENT-OK", soleProprietor)).isInstanceOf(Resolution.Resolved::class.java)
            // a triple that starts after the document's date is no triple for that document
            env.configured(tenantA, "ENT-LATE", agent, triple("0195:U-A", "U-A", "G-A"), LocalDate.of(2027, 1, 1))
            assertThat(env.resolved(tenantA, "ENT-LATE", agent)).isInstanceOf(Resolution.Blocked::class.java)
        }

    @Test
    fun `INV-AC-1604 INV-AC-2275 a group with no election resolves per member, UEN of the member, GSTN of the group`() =
        run { env ->
            val groupGstn = "G-GRP"
            env.configured(tenantA, "ENT-M1", groupMember, triple("0195:U-M1", "U-M1", groupGstn))
            env.configured(tenantA, "ENT-M2", groupMember, triple("0195:U-M2", "U-M2", groupGstn))
            val first = env.triple(tenantA, "ENT-M1", groupMember)
            val second = env.triple(tenantA, "ENT-M2", groupMember)
            assertThat(first.senderId).isEqualTo("0195:U-M1")
            assertThat(second.senderId).isEqualTo("0195:U-M2")
            assertThat(listOf(first.supplierId, second.supplierId)).containsExactly("U-M1", "U-M2")
            assertThat(listOf(first.supplierGstn, second.supplierGstn)).containsOnly(groupGstn)
            // the member's own entity GSTN (never a triple field) and a representative's UEN never appear
            assertThat(listOf(first, second).map { it.supplierGstn }).doesNotContain("G-M1", "G-M2")
        }

    @Test
    fun `INV-AC-2034 the supplier triple and the submitting identity are separate fields`() =
        run { env ->
            env.configured(tenantA, "ENT-SUB", agent, triple("0195:U-A", "U-A", "G-A"))
            val viaCentre = SubmittingIdentity("CONN-SSC", "PRINCIPAL-SSC")
            val viaAgent = SubmittingIdentity("CONN-AGENT", "PRINCIPAL-AGENT")
            val first = env.resolved(tenantA, "ENT-SUB", agent, submitter = viaCentre) as Resolution.Resolved
            val second = env.resolved(tenantA, "ENT-SUB", agent, submitter = viaAgent) as Resolution.Resolved
            assertThat(first.version.triple).isEqualTo(second.version.triple)
            assertThat(first.submitter).isEqualTo(viaCentre)
            assertThat(second.submitter).isEqualTo(viaAgent)
            val tripleFields = fieldNames(IdentityTriple::class.java)
            val submitterFields = fieldNames(SubmittingIdentity::class.java)
            assertThat(tripleFields).containsExactlyInAnyOrder("senderId", "supplierId", "supplierGstn")
            assertThat(submitterFields).containsExactlyInAnyOrder("connectionRef", "principalRef")
            assertThat(tripleFields.intersect(submitterFields)).isEmpty()
            assertThat(migrationColumns()).doesNotContain("connection_ref", "principal_ref", "submitting_connection")
        }

    @Test
    fun `INV-AC-2254 the person who changed the identity triple cannot confirm it, under any role`() =
        run { env ->
            val before = triple("0195:U-A", "U-A", "G-A")
            env.configured(tenantA, "ENT-SOLE", agent, before)
            val change = triple("0195:U-A2", "U-A2", "G-A2")
            val from = LocalDate.of(2026, 7, 1)
            roles[sole] = setOf("entity.admin", "tax.approver")
            env.proposer = sole
            val pending = env.proposed(tenantA, "ENT-SOLE", agent, change, from)
            // the proposer, and the same identity written another way, under either role: refused, still pending
            listOf(sole, " $sole ", sole.lowercase()).forEach { reference ->
                roles[reference] = setOf("entity.admin", "tax.approver")
                assertThat(env.confirmedAs(tenantA, reference, "ENT-SOLE", pending.changeRef))
                    .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            }
            assertThat(env.triple(tenantA, "ENT-SOLE", agent, LocalDate.of(2026, 8, 1))).isEqualTo(before)
            // a different person holding tax.approver (the external advisor) confirms
            assertThat(env.confirmedAs(tenantA, advisor, "ENT-SOLE", pending.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.triple(tenantA, "ENT-SOLE", agent, LocalDate.of(2026, 8, 1))).isEqualTo(change)
        }

    @Test
    fun `INV-AC-2254 a subject outside the confirmer set is refused, an unknown or settled change is 404 or 409`() =
        run { env ->
            val pending = env.proposed(tenantA, "ENT-SET", agent, triple("0195:U-A2", "U-A2", "G-A2"))
            assertThat(env.confirmedAs(tenantA, outsider, "ENT-SET", pending.changeRef))
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(env.confirmedAs(tenantA, nobody, "ENT-SET", pending.changeRef))
                .isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            assertThat(
                env.confirmedAs(tenantA, otherAdmin, "ENT-SET", "ITC-unknown"),
            ).isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.confirmedAs(tenantA, otherAdmin, "ENT-OTHER", pending.changeRef))
                .isEqualTo(ClosedErrorCode.NOT_FOUND)
            assertThat(env.confirmedAs(tenantA, otherAdmin, "ENT-SET", pending.changeRef)).isEqualTo(HttpStatus.OK)
            // a second confirmation of the same change is 409 SRT-008; a superseded change is no longer pending either
            assertThat(env.confirmedAs(tenantA, advisor, "ENT-SET", pending.changeRef))
                .isEqualTo(ClosedErrorCode.STALE_AGGREGATE)
            val first = env.proposed(tenantA, "ENT-REP", agent, triple("0195:U-1", "U-1", "G-1"), longAgo.plusDays(9))
            val repeat = env.proposed(tenantA, "ENT-REP", agent, triple("0195:U-2", "U-2", "G-2"), longAgo.plusDays(9))
            assertThat(env.confirmedAs(tenantA, otherAdmin, "ENT-REP", first.changeRef))
                .isEqualTo(ClosedErrorCode.STALE_AGGREGATE)
            assertThat(env.confirmedAs(tenantA, otherAdmin, "ENT-REP", repeat.changeRef)).isEqualTo(HttpStatus.OK)
        }

    @Test
    fun `INV-AC-2254 the confirmer set is the one the ConfirmerPolicy port holds, compared by equality`() =
        run { env ->
            val policy =
                env.context.getBean(
                    com.dynokore.gst.masterdata.application.identity.ConfirmerPolicy::class.java,
                )
            assertThat(policy.confirmers().roles.map { it.wire })
                .containsExactlyInAnyOrder("entity.admin", "tenant.admin", "tax.approver")
            // the domain rule: roles are compared with the set by equality, a look-alike role does not count
            val rule = com.dynokore.gst.masterdata.domain.identity.ConfirmationRule
            val set = policy.confirmers()
            assertThat(rule.check(PersonRef(admin), PersonRef(otherAdmin), setOf("Entity.Admin", "tenant.admin2"), set))
                .isEqualTo(ConfirmationRefusal.ROLE_NOT_ALLOWED)
            assertThat(rule.check(PersonRef(admin), PersonRef(otherAdmin), setOf("tax.approver"), set)).isNull()
        }

    @Test
    fun `INV-AC-1603 INV-AC-1983 meanwhile the previous triple holds and the new one applies from its date`() =
        run { env ->
            val old = triple("0195:U-A", "U-A", "G-A")
            val new = triple("0195:U-A2", "U-A2", "G-A2")
            val from = LocalDate.of(2026, 7, 1)
            env.configured(tenantA, "ENT-CHG", agent, old)
            val pending = env.proposed(tenantA, "ENT-CHG", agent, new, from)
            assertThat(pending.state).isEqualTo(ChangeState.PENDING_CONFIRMATION)
            // the proposer cannot confirm; a document resolved meanwhile carries the previous triple
            assertThat(
                env.confirmedAs(tenantA, admin, "ENT-CHG", pending.changeRef),
            ).isEqualTo(ClosedErrorCode.ACCESS_DENIED)
            val meanwhile = env.triple(tenantA, "ENT-CHG", agent, from)
            assertThat(listOf(meanwhile.senderId, meanwhile.supplierId, meanwhile.supplierGstn))
                .containsExactly(old.senderId, old.supplierId, old.supplierGstn)
            // a different person confirms: before the date the previous triple, from the date the new one
            assertThat(env.confirmedAs(tenantA, otherAdmin, "ENT-CHG", pending.changeRef)).isEqualTo(HttpStatus.OK)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from.minusDays(1))).isEqualTo(old)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from)).isEqualTo(new)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from.plusMonths(3))).isEqualTo(new)
        }

    @Test
    fun `INV-AC-1603 a body that carries secondConfirmation is refused 400 and records nothing`() =
        run { env ->
            val before = env.rows("identity_triple", "ENT-BODY") to env.rows("identity_triple_change", "ENT-BODY")
            val request =
                ConfigureIdentityTripleRequest(
                    "0195:U-A",
                    "U-A",
                    "G-A",
                    LocalDate.of(2026, 7, 1),
                    SecondConfirmation(otherAdmin, "tenant.admin"),
                )
            val refusal =
                runCatching {
                    env.inTenant(
                        tenantA,
                    ) { env.identityApi.configureIdentityTriple("ENT-BODY", UUID.randomUUID(), request) }
                }.exceptionOrNull()
            assertThat(refusal).isInstanceOf(ApiRefusal::class.java)
            assertThat((refusal as ApiRefusal).code).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal.code.httpStatus).isEqualTo(400)
            assertThat(
                env.rows("identity_triple", "ENT-BODY") to env.rows("identity_triple_change", "ENT-BODY"),
            ).isEqualTo(before)
            assertThat(before).isEqualTo(0 to 0)
        }

    @Test
    fun `INV-AC-1603 a proposal answers 202 with the pending change and puts nothing in force`() =
        run { env ->
            val request = ConfigureIdentityTripleRequest("0195:U-A", "U-A", "G-A", LocalDate.of(2026, 7, 1))
            val response = env.inTenant(tenantA) { env.identityApi.propose("ENT-202", agent.code, request) }
            assertThat(response.statusCode).isEqualTo(HttpStatus.ACCEPTED)
            val body = response.body as Any as PendingIdentityTripleChange
            assertThat(body.state).isEqualTo(PendingIdentityTripleChange.State.PENDING_CONFIRMATION)
            assertThat(body.proposedBy).isEqualTo(admin)
            assertThat(env.resolved(tenantA, "ENT-202", agent, LocalDate.of(2026, 8, 1)))
                .isInstanceOf(Resolution.Blocked::class.java)
            assertThat(env.rows("identity_triple", "ENT-202")).isZero()
        }

    @Test
    fun `row-level security hides another tenant's rows and every row from an unbound runtime session`() =
        run { env ->
            env.configured(tenantA, "ENT-RLS", agent, triple("0195:U-A", "U-A", "G-A"))
            env.proposed(tenantA, "ENT-RLS", agent, triple("0195:U-B", "U-B", "G-B"), LocalDate.of(2026, 9, 1))
            assertThat(env.resolved(tenantB, "ENT-RLS", agent)).isInstanceOf(Resolution.Blocked::class.java)
            val other =
                env.inTenant(tenantB, otherAdmin) {
                    env.confirm.handle(ConfirmCommand(EntityRef("ENT-RLS"), "ITC-x", PersonRef(otherAdmin)))
                }
            assertThat(other).isEqualTo(ConfirmOutcome.UnknownChange)
            listOf("identity_triple", "identity_triple_change").forEach { table ->
                val unbound =
                    BoundaryFixture.asRuntime { c ->
                        c.createStatement().executeQuery("select count(*) from masterdata.$table").use { rs ->
                            rs.next()
                            rs.getInt(1)
                        }
                    }
                assertThat(unbound).isZero()
            }
        }

    private fun fieldNames(type: Class<*>): Set<String> =
        type.declaredFields
            .filter {
                !it.isSynthetic &&
                    !java.lang.reflect.Modifier
                        .isStatic(it.modifiers)
            }.map { it.name }
            .toSet()

    /** The column names the `identity_triple` migration declares (a line that starts a column definition). */
    private fun migrationColumns(): Set<String> {
        val dir =
            java.nio.file.Path
                .of("..", "masterdata-adapters", "src", "main", "resources", "db", "migration")
        val column = Regex("""^\s+([a-z_]+)\s+(uuid|text|date|bigint|timestamptz)\b""")
        return java.nio.file.Files
            .list(dir)
            .use { files -> files.toList() }
            .flatMap {
                java.nio.file.Files
                    .readAllLines(it)
            }.mapNotNull { column.find(it)?.groupValues?.get(1) }
            .toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun contractErrorCodes(operationId: String): List<String> {
        val text =
            checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/masterdata/openapi.yaml")) {
                "the masterdata contract is not on the classpath"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
        val paths =
            org.yaml.snakeyaml
                .Yaml()
                .load<Map<String, Any?>>(text)["paths"] as Map<String, Map<String, Any?>>
        return paths.values
            .flatMap { it.values }
            .filterIsInstance<Map<String, Any?>>()
            .first { it["operationId"] == operationId }["x-error-codes"] as List<String>
    }
}
