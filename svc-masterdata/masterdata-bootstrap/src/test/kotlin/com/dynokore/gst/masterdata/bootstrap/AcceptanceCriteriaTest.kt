package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.application.identity.ConfigureCommand
import com.dynokore.gst.masterdata.application.identity.ConfigureIdentityTriple
import com.dynokore.gst.masterdata.application.identity.ConfigureOutcome
import com.dynokore.gst.masterdata.application.identity.ResolveCommand
import com.dynokore.gst.masterdata.application.identity.ResolveIdentityTriple
import com.dynokore.gst.masterdata.domain.identity.ConfirmationRefusal
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.Resolution
import com.dynokore.gst.masterdata.domain.identity.SecondConfirmation
import com.dynokore.gst.masterdata.domain.identity.SubmittingIdentity
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext
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
        val configure = context.getBean(ConfigureIdentityTriple::class.java)

        fun <T> inTenant(
            tenant: TenantId,
            actor: String = admin,
            block: () -> T,
        ): T = tenants.bind(tenant, actor) { block() }

        /** Configures through the real use case with a valid second confirmation. */
        fun configured(
            tenant: TenantId,
            entity: String,
            constitution: Constitution,
            triple: IdentityTriple,
            from: LocalDate = longAgo,
        ): ConfigureOutcome =
            inTenant(tenant) {
                val outcome =
                    configure.handle(
                        ConfigureCommand(
                            EntityRef(entity),
                            constitution,
                            triple,
                            from,
                            PersonRef(admin),
                            SecondConfirmation(PersonRef(otherAdmin), "tenant.admin"),
                        ),
                    )
                assertThat(outcome).isInstanceOf(ConfigureOutcome.Applied::class.java)
                outcome
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
            assertThat(contractErrorCodes("configureIdentityTriple")).contains(blocked.code)
            assertThat(contractErrorCodes("resolveCounterparty")).contains(blocked.code)
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
            listOf("tax.approver", "entity.admin", "tenant.admin").forEach { role ->
                listOf(admin, " $admin ", admin.lowercase()).forEach { sameReference ->
                    val outcome =
                        env.inTenant(tenantA) {
                            env.configure.handle(
                                ConfigureCommand(
                                    EntityRef("ENT-SOLE"),
                                    agent,
                                    change,
                                    from,
                                    PersonRef(admin),
                                    SecondConfirmation(PersonRef(sameReference), role),
                                ),
                            )
                        }
                    assertThat(outcome).isEqualTo(ConfigureOutcome.Refused(ConfirmationRefusal.SAME_PERSON))
                }
            }
            assertThat(env.triple(tenantA, "ENT-SOLE", agent, LocalDate.of(2026, 8, 1))).isEqualTo(before)
            // a different person holding tax.approver is accepted only when the tenant's confirmer set holds that role
            val approver =
                env.inTenant(tenantA) {
                    env.configure.handle(
                        ConfigureCommand(
                            EntityRef("ENT-SOLE"),
                            agent,
                            change,
                            from,
                            PersonRef(admin),
                            SecondConfirmation(PersonRef("IDN-ADVISOR"), "tax.approver"),
                        ),
                    )
                }
            assertThat(approver).isEqualTo(ConfigureOutcome.Refused(ConfirmationRefusal.ROLE_NOT_ALLOWED))
        }

    @Test
    fun `INV-AC-1603 INV-AC-1983 meanwhile the previous triple holds and the new one applies from its date`() =
        run { env ->
            val old = triple("0195:U-A", "U-A", "G-A")
            val new = triple("0195:U-A2", "U-A2", "G-A2")
            val from = LocalDate.of(2026, 7, 1)
            env.configured(tenantA, "ENT-CHG", agent, old)
            val refused =
                env.inTenant(tenantA) {
                    env.configure.handle(
                        ConfigureCommand(
                            EntityRef("ENT-CHG"),
                            agent,
                            new,
                            from,
                            PersonRef(admin),
                            SecondConfirmation(PersonRef(admin), "entity.admin"),
                        ),
                    )
                }
            assertThat(refused).isInstanceOf(ConfigureOutcome.Refused::class.java)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from)).isEqualTo(old)
            env.configured(tenantA, "ENT-CHG", agent, new, from)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from.minusDays(1))).isEqualTo(old)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from)).isEqualTo(new)
            assertThat(env.triple(tenantA, "ENT-CHG", agent, from.plusMonths(3))).isEqualTo(new)
        }

    @Test
    fun `row-level security hides another tenant's rows and every row from an unbound runtime session`() =
        run { env ->
            env.configured(tenantA, "ENT-RLS", agent, triple("0195:U-A", "U-A", "G-A"))
            assertThat(env.resolved(tenantB, "ENT-RLS", agent)).isInstanceOf(Resolution.Blocked::class.java)
            val unbound =
                BoundaryFixture.asRuntime { c ->
                    c.createStatement().executeQuery("select count(*) from masterdata.identity_triple").use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            assertThat(unbound).isZero()
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
