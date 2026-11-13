package com.dynokore.gst.transport.adapters

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.transport.adapters.http.AdministrationAdvice
import com.dynokore.gst.transport.adapters.http.ApiRefusalAdvice
import com.dynokore.gst.transport.adapters.http.CredentialRotationRefused
import com.dynokore.gst.transport.adapters.persistence.JooqAdapterCredentialStates
import com.dynokore.gst.transport.adapters.persistence.JooqCredentialRotationLog
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApConnection.Companion.AP_CONNECTION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialRotation.Companion.AP_CREDENTIAL_ROTATION
import com.dynokore.gst.transport.adapters.persistence.jooq.tables.ApCredentialState.Companion.AP_CREDENTIAL_STATE
import com.dynokore.gst.transport.application.AdapterCredentialState
import com.dynokore.gst.transport.application.CredentialRef
import com.dynokore.gst.transport.application.CredentialRotationAudit
import com.dynokore.gst.transport.application.CredentialVault
import com.dynokore.gst.transport.application.RotateCredential
import com.dynokore.gst.transport.application.RotateResult
import com.dynokore.gst.transport.application.RotationOutcome
import com.dynokore.gst.transport.application.RotationRefusal
import com.dynokore.gst.transport.application.Secret
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.platform.tenancyjooq.TableClass
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancyjooq.TenancyTables
import com.dynokore.platform.tenancykernel.BindingKeySource
import com.dynokore.platform.tenancykernel.TenantContextImpl
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * The credential rotation at its two boundaries (INV-T-18676; INV-REQ-837): the stores against the real database with a
 * repository tenant that differs from the tenant the database session is bound to, so that forced row level security
 * cannot mask a missing tenant predicate, and the mapping of each refusal of `RotateCredential` to its status and code.
 */
class CredentialRotationBoundaryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-14T10:00:00Z"), ZoneOffset.UTC)

    /** The database session binds [bound]; the repositories read their tenant from [repository]. */
    private class Session(
        val bound: UUID,
        val repository: UUID,
        clock: Clock,
    ) {
        private val bindings = TenantContextImpl()
        private val repositoryTenants = TenantContextImpl()
        private val source =
            PGSimpleDataSource().apply {
                setUrl(TransportDb.jdbcUrl)
                user = TransportDb.RUNTIME
                password = TransportDb.RUNTIME_PASSWORD
            }
        private val impl =
            TransactionRunnerImpl(
                source,
                bindings,
                null,
                object : BindingKeySource {
                    override fun currentKey(): ByteArray = TransportDb.bindingKey.copyOf()

                    override fun previousKey(): ByteArray? = null
                },
            )
        val dsl: DSLContext =
            TenancyJooq
                .configure(
                    DefaultConfiguration().set(SQLDialect.POSTGRES),
                    TenancyTables.declared(
                        mapOf(
                            DSL.name("transport", "ap_connection") to TableClass.TENANT,
                            DSL.name("transport", "ap_credential_state") to TableClass.TENANT,
                            DSL.name("transport", "ap_credential_rotation") to TableClass.TENANT,
                        ),
                    ),
                    bindings,
                    impl,
                ).dsl()

        /** Every transaction binds [bound] at the database and, around the repository call, [repository]. */
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T =
                    bindings.bind(TenantId(bound), SignedBindings.ACTOR) {
                        repositoryTenants.bind(TenantId(repository), SignedBindings.ACTOR) { impl.inTransaction(block) }
                    }
            }
        val states =
            JooqAdapterCredentialStates(dsl, runner, repositoryTenants, clock) { CredentialRef("ap/${it.value}") }
        val log = JooqCredentialRotationLog(dsl, runner, repositoryTenants)

        fun connect(adapter: String) =
            bindings.bind(TenantId(bound), SignedBindings.ACTOR) {
                impl.inTransaction {
                    dsl
                        .insertInto(AP_CONNECTION)
                        .set(AP_CONNECTION.TENANT_ID, bound)
                        .set(AP_CONNECTION.ADAPTER_ID, adapter)
                        .set(AP_CONNECTION.AGREED_RATE, 100.toBigDecimal())
                        .execute()
                }
            }

        /** Every column of the state and rotation rows of the bound tenant, as text, to compare field by field. */
        fun snapshot(adapter: String): Pair<List<String>, List<String>> =
            bindings.bind(TenantId(bound), SignedBindings.ACTOR) {
                impl.inTransaction {
                    dsl
                        .selectFrom(AP_CREDENTIAL_STATE)
                        .where(AP_CREDENTIAL_STATE.ADAPTER_ID.eq(adapter))
                        .fetch()
                        .map { it.intoMap().toSortedMap().toString() } to
                        dsl
                            .selectFrom(AP_CREDENTIAL_ROTATION)
                            .where(AP_CREDENTIAL_ROTATION.ADAPTER_ID.eq(adapter))
                            .fetch()
                            .map { it.intoMap().toSortedMap().toString() }
                }
            }
    }

    private fun outcome(
        adapter: String,
        new: String,
        previous: String,
    ) = RotationOutcome(
        AdapterId(adapter),
        CredentialRef(new),
        CredentialRef(previous),
        clock.instant().plusSeconds(3600),
    )

    private fun rotate(
        s: Session,
        adapter: String,
        new: String,
        user: String,
    ) {
        val result = outcome(adapter, new, "ap/$adapter")
        s.runner.inTransaction {
            s.states.save(
                result.adapter,
                AdapterCredentialState(result.credentialId, result.previous, result.overlapEnd),
            )
            s.log.record(CredentialRotationAudit(user, result, clock.instant()))
        }
    }

    private fun adapterId() = "a-${UUID.randomUUID().toString().take(12)}"

    @Test
    fun `a rotation by tenant B of an adapter id tenant A also has leaves the state and rotation rows of A equal`() {
        val adapter = adapterId()
        val a = UUID.randomUUID().let { Session(it, it, clock) }
        val b = UUID.randomUUID().let { Session(it, it, clock) }
        a.connect(adapter)
        b.connect(adapter)
        rotate(a, adapter, "vault/a-1", "user-a")
        val before = a.snapshot(adapter)
        before.first.size shouldBe 1
        before.second.size shouldBe 1

        rotate(b, adapter, "vault/b-1", "user-b")
        a.snapshot(adapter) shouldBe before
        b.snapshot(adapter).first.single() shouldContain "vault/b-1"
        a.states.currentRef(AdapterId(adapter)).value shouldBe "vault/a-1"
        b.states.currentRef(AdapterId(adapter)).value shouldBe "vault/b-1"
    }

    @Test
    fun `tenant B reading the current_ref of tenant A gets the initial reference, not the one of A`() {
        val adapter = adapterId()
        val a = UUID.randomUUID().let { Session(it, it, clock) }
        a.connect(adapter)
        rotate(a, adapter, "vault/a-1", "user-a")
        a.states.currentRef(AdapterId(adapter)).value shouldBe "vault/a-1"

        // the database session is bound to A while the repository tenant is B: only the predicate on tenant_id can
        // tell the rows apart, because forced row level security shows this session the rows of A
        val b = Session(a.bound, UUID.randomUUID(), clock)
        b.states.currentRef(AdapterId(adapter)).value shouldBe "ap/$adapter"
        b.states.load(AdapterId(adapter)) shouldBe AdapterCredentialState(CredentialRef("ap/$adapter"))
        b.log.find(AdapterId(adapter), CredentialRef("vault/a-1")) shouldBe null
        b.log.rotatedAt(AdapterId(adapter), CredentialRef("vault/a-1")) shouldBe null
        // the same session, repository tenant A, does read it
        Session(a.bound, a.bound, clock).log.find(AdapterId(adapter), CredentialRef("vault/a-1")) shouldBe
            outcome(adapter, "vault/a-1", "ap/$adapter").copy(overlapEnd = clock.instant().plusSeconds(3600))
    }

    @Test
    fun `a write by a repository tenant other than the bound one is refused by the database and A is unchanged`() {
        val adapter = adapterId()
        val a = UUID.randomUUID().let { Session(it, it, clock) }
        a.connect(adapter)
        rotate(a, adapter, "vault/a-1", "user-a")
        val before = a.snapshot(adapter)
        val b = Session(a.bound, UUID.randomUUID(), clock)
        shouldThrow<Exception> { rotate(b, adapter, "vault/b-1", "user-b") }
        a.snapshot(adapter) shouldBe before
    }

    private class CountingVault(
        private val held: Set<String>,
    ) : CredentialVault {
        var reads = 0

        override fun holds(ref: CredentialRef) = ref.value in held

        override fun read(ref: CredentialRef): Secret? {
            reads++
            return null
        }
    }

    private class Refused(
        val status: Int,
        val body: String,
    )

    private fun refusal(
        window: Duration?,
        new: String,
        preRotated: Boolean = false,
    ): Triple<Refused, Pair<List<String>, List<String>>, Int> {
        val adapter = adapterId()
        val tenant = UUID.randomUUID()
        val s = Session(tenant, tenant, clock)
        s.connect(adapter)
        if (preRotated) rotate(s, adapter, "vault/c2", "user-x")
        val vault = CountingVault(setOf("vault/c2", "vault/c3"))
        val before = s.snapshot(adapter)
        val result =
            RotateCredential(s.states, vault, s.log, clock)
                .rotate(AdapterId(adapter), CredentialRef(new.replace("{adapter}", adapter)), window, "user-1")
        val reason = (result as RotateResult.Refused).reason
        val response = AdministrationAdvice(ApiRefusalAdvice()).rotationRefused(CredentialRotationRefused(reason))
        s.snapshot(adapter) shouldBe before
        return Triple(Refused(response.statusCode.value(), response.body.orEmpty()), before, vault.reads)
    }

    private fun Refused.shouldBe(
        status: Int,
        code: String,
    ) {
        ErrorCode.entries.map { it.code }.contains(code) shouldBe true
        this.status shouldBe status
        body shouldContain "\"code\":\"$code\""
        // an exact code: the look-alikes with a zero or a missing zero are other strings
        body shouldNotContain "\"code\":\"${code}0\""
    }

    @Test
    fun `UNKNOWN_REFERENCE answers 422 SUB-026, not 400, 404, SRT-001 or SRT-005, and changes nothing`() {
        val (answer, _, reads) = refusal(Duration.ofHours(1), "vault/absent")
        answer.shouldBe(422, "SUB-026")
        answer.body shouldNotContain "SRT-001"
        answer.body shouldNotContain "SRT-005"
        reads shouldBe 0
    }

    @Test
    fun `SAME_AS_CURRENT answers 422 SUB-027, not 409 or SRT-008, and changes nothing`() {
        // the initial reference of a connection with no row is its current one
        val (answer, _, reads) = refusal(Duration.ofHours(1), "ap/{adapter}")
        answer.shouldBe(422, "SUB-027")
        answer.body shouldNotContain "SRT-008"
        reads shouldBe 0
    }

    @Test
    fun `ROTATION_IN_PROGRESS answers 409 SUB-028, not SRT-008, SRT-007 or 422, and changes nothing`() {
        val (answer, _, reads) = refusal(Duration.ofHours(1), "vault/c3", preRotated = true)
        answer.shouldBe(409, "SUB-028")
        answer.body shouldNotContain "SRT-008"
        answer.body shouldNotContain "SRT-007"
        reads shouldBe 0
    }

    @Test
    fun `NO_WINDOW answers 500 SRT-010 with no detail text, not 400, and changes nothing`() {
        val (answer, _, reads) = refusal(null, "vault/c2")
        answer.shouldBe(500, "SRT-010")
        answer.body shouldNotContain "\"detail\""
        reads shouldBe 0
        refusal(Duration.ZERO, "vault/c2").first.shouldBe(500, "SRT-010")
        refusal(Duration.ofSeconds(-1), "vault/c2").first.shouldBe(500, "SRT-010")
    }

    @Test
    fun `the look-alike codes SUB-0260 and SUB-26 are not catalogue codes and no refusal answers them`() {
        val codes = ErrorCode.entries.map { it.code }
        codes.contains("SUB-0260") shouldBe false
        codes.contains("SUB-26") shouldBe false
        listOf("SUB-026", "SUB-027", "SUB-028").forEach { codes.contains(it) shouldBe true }
        val answers =
            RotationRefusal.entries.map { reason ->
                AdministrationAdvice(
                    ApiRefusalAdvice(),
                ).rotationRefused(CredentialRotationRefused(reason)).body.orEmpty()
            }
        answers.forEach {
            it shouldNotContain "SUB-0260"
            it shouldNotContain "SUB-26\""
        }
    }
}
